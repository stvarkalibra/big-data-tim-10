package rs.raf.dlsm.wal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.failpoint.FailpointRegistry.State;
import rs.raf.dlsm.failpoint.InjectedFailureException;
import rs.raf.dlsm.io.InjectedIOException;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.stats.StorageStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three WAL failpoints through the real {@link FailpointRegistry} and
 * {@code DefaultStorageIO}, as in production (CP1 §26). {@code CRASH} halts
 * the JVM, so it is covered by the process-level scenarios; here
 * {@code IO_ERROR} and {@code PAUSE} prove what is on disk at each boundary.
 */
class WalSegmentFailpointTest {

    private static final OpContext CTX = OpContext.background();

    @TempDir
    Path root;

    private final FailpointRegistry failpoints = new FailpointRegistry(true);
    private final ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
    private StorageStats stats;
    private WalSegment segment;
    private Path file;
    /** File length L before the candidate mutation: base1 and base2 are durable. */
    private long baseLength;

    @BeforeEach
    void setUp() throws IOException {
        Path walDir = Files.createDirectories(root.resolve("lsm/wal"));
        Path tmpDir = Files.createDirectories(root.resolve("lsm/tmp"));
        stats = new StorageStats();
        JsonLog log = new JsonLog("test",
                new PrintStream(logOutput, true, StandardCharsets.UTF_8), Clock.systemUTC());
        WalSegments segments = new WalSegments(walDir, tmpDir, new RecordingStorageIO(failpoints),
                failpoints, stats, WalIdAllocator.startingAt(1), log);

        segment = segments.createSegment(CTX);
        file = walDir.resolve(WalFileNames.segmentFileName(1));
        segment.append(InternalRecord.put("base1", 1, bytes("V1")), CTX);
        segment.append(InternalRecord.put("base2", 2, bytes("V2")), CTX);
        segment.force(CTX);
        baseLength = Files.size(file);
        stats.reset();
    }

    @AfterEach
    void tearDown() {
        failpoints.clear();
        segment.close();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static final InternalRecord CANDIDATE = InternalRecord.put("candidate", 3, bytes("C".repeat(20)));
    private static final int CANDIDATE_LENGTH = WalCodec.encodeFrame(CANDIDATE).length;

    private void arm(String name, Action action, Map<String, String> parameters) {
        failpoints.addRule(new FailpointRegistry.Rule(
                "tok-" + name, name, action, 0, 1, 0, parameters));
    }

    private long counter(String name) {
        return stats.snapshot().get(name);
    }

    private List<InternalRecord> baseRecords() {
        return List.of(InternalRecord.put("base1", 1, bytes("V1")),
                InternalRecord.put("base2", 2, bytes("V2")));
    }

    // ===== CP1-WAL-004 (a): IO_ERROR on wal.before-append =====

    @Test
    void ioErrorBeforeAppendWritesNoCandidateByte() throws IOException {
        arm(FailpointNames.WAL_BEFORE_APPEND, Action.IO_ERROR, Map.of());

        assertThrows(InjectedIOException.class, () -> segment.append(CANDIDATE, CTX));

        assertEquals(baseLength, Files.size(file));
        assertEquals(1, counter("walAppendAttempts"));
        assertEquals(0, counter("walAppendsCompleted"));
        assertEquals(0, counter("walBytesWritten"));
        assertTrue(segment.hasUncertainTail());
        assertEquals(WalScanResult.Status.VALID, WalReader.scanSegment(file).status());
    }

    // ===== CP1-WAL-004 (b): IO_ERROR on wal.after-record-prefix =====

    @Test
    void ioErrorAfterPrefixLeavesExactlyThePrefix() throws IOException {
        arm(FailpointNames.WAL_AFTER_RECORD_PREFIX, Action.IO_ERROR, Map.of("prefixBytes", "17"));

        assertThrows(InjectedIOException.class, () -> segment.append(CANDIDATE, CTX));

        assertEquals(baseLength + 17, Files.size(file));
        assertEquals(17, counter("walBytesWritten"));
        assertEquals(0, counter("walAppendsCompleted"));

        WalScanResult scan = WalReader.scanSegment(file);
        assertEquals(WalScanResult.Status.TORN_TAIL, scan.status());
        assertEquals(baseLength, scan.tornFrameOffset());
        assertEquals(baseRecords(), scan.records());
    }

    // CP1-WAL-002 cut points: inside the guarded header, right after it, one byte before the end.
    @Test
    void everyPrefixLengthIsCutExactlyAndReadAsTornTail() throws IOException {
        for (int prefix : new int[]{1, 7, 8, 9, CANDIDATE_LENGTH - 1}) {
            setUpFreshSegment();
            arm(FailpointNames.WAL_AFTER_RECORD_PREFIX, Action.IO_ERROR,
                    Map.of("prefixBytes", Integer.toString(prefix)));

            assertThrows(InjectedIOException.class, () -> segment.append(CANDIDATE, CTX));

            assertEquals(baseLength + prefix, Files.size(file), "prefix " + prefix);
            WalScanResult scan = WalReader.scanSegment(file);
            assertEquals(WalScanResult.Status.TORN_TAIL, scan.status(), "prefix " + prefix);
            assertEquals(baseLength, scan.tornFrameOffset(), "prefix " + prefix);
        }
    }

    @Test
    void prefixOutsideFrameIsKeptStrictAndLogged() throws IOException {
        Map<Integer, Integer> configuredToUsed = Map.of(
                0, 1,
                -5, 1,
                CANDIDATE_LENGTH, CANDIDATE_LENGTH - 1,
                100_000, CANDIDATE_LENGTH - 1);

        for (Map.Entry<Integer, Integer> entry : configuredToUsed.entrySet()) {
            setUpFreshSegment();
            logOutput.reset();
            arm(FailpointNames.WAL_AFTER_RECORD_PREFIX, Action.IO_ERROR,
                    Map.of("prefixBytes", Integer.toString(entry.getKey())));

            assertThrows(InjectedIOException.class, () -> segment.append(CANDIDATE, CTX));

            assertEquals(baseLength + entry.getValue(), Files.size(file), "configured " + entry.getKey());
            String log = logOutput.toString(StandardCharsets.UTF_8);
            assertTrue(log.contains("\"event\":\"WAL_RECORD_PREFIX\""), log);
            assertTrue(log.contains("prefixBytes=" + entry.getValue() + " (configured " + entry.getKey()
                    + "), frameLength=" + CANDIDATE_LENGTH), log);
            assertTrue(log.contains("\"fileOffset\":" + baseLength), log);
        }
    }

    // ===== CP1-WAL-004 (c): IO_ERROR on wal.after-append.before-sync =====

    @Test
    void ioErrorBeforeForceLeavesCompleteUnforcedFrame() throws IOException {
        arm(FailpointNames.WAL_AFTER_APPEND_BEFORE_SYNC, Action.IO_ERROR, Map.of());

        segment.append(CANDIDATE, CTX);
        assertThrows(InjectedIOException.class, () -> segment.force(CTX));

        assertEquals(1, counter("walAppendsCompleted"));
        assertEquals(1, counter("walForceAttempts"));
        assertEquals(0, counter("walForcesSucceeded"));
        assertTrue(segment.hasUncertainTail());
        // The frame is complete; whether it survives a crash is unknown.
        assertEquals(baseLength + CANDIDATE_LENGTH, Files.size(file));
    }

    // ===== No append after an uncertain tail (CP1 §9.3) =====

    @Test
    void laterMutationIsRefusedAfterEachInjectedFailure() throws IOException {
        for (String name : new String[]{FailpointNames.WAL_BEFORE_APPEND,
                FailpointNames.WAL_AFTER_RECORD_PREFIX, FailpointNames.WAL_AFTER_APPEND_BEFORE_SYNC}) {
            setUpFreshSegment();
            arm(name, Action.IO_ERROR, Map.of());
            try {
                segment.append(CANDIDATE, CTX);
                segment.force(CTX);
            } catch (InjectedIOException expected) {
                // the injected failure; the segment must now refuse everything
            }
            long sizeAfterFailure = Files.size(file);
            failpoints.clear();

            IOException refused = assertThrows(IOException.class,
                    () -> segment.append(InternalRecord.delete("later", 4), CTX), name);
            assertFalse(refused instanceof InjectedIOException, name);
            assertThrows(IOException.class, () -> segment.force(CTX), name);
            assertEquals(sizeAfterFailure, Files.size(file), name);
        }
    }

    // ===== PAUSE =====

    @Test
    void pauseAfterPrefixHoldsExactlyThePrefixUntilReleased() throws Exception {
        arm(FailpointNames.WAL_AFTER_RECORD_PREFIX, Action.PAUSE, Map.of("prefixBytes", "12"));

        CompletableFuture<Void> append = CompletableFuture.runAsync(() -> {
            try {
                segment.append(CANDIDATE, CTX);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        awaitState("tok-" + FailpointNames.WAL_AFTER_RECORD_PREFIX, State.TRIGGERED);
        assertEquals(baseLength + 12, Files.size(file));
        assertFalse(append.isDone());

        assertTrue(failpoints.releaseRule("tok-" + FailpointNames.WAL_AFTER_RECORD_PREFIX));
        append.get(10, TimeUnit.SECONDS);
        segment.force(CTX);

        WalScanResult scan = WalReader.scanSegment(file);
        assertEquals(WalScanResult.Status.VALID, scan.status());
        assertEquals(CANDIDATE, scan.records().getLast());
    }

    @Test
    void unreleasedPauseFailsTheAppendAndLeavesUncertainTail() throws IOException {
        failpoints.addRule(new FailpointRegistry.Rule("tok-pause",
                FailpointNames.WAL_AFTER_RECORD_PREFIX, Action.PAUSE, 0, 1, 50, Map.of()));

        assertThrows(InjectedFailureException.class, () -> segment.append(CANDIDATE, CTX));

        assertTrue(segment.hasUncertainTail());
        assertEquals(baseLength + WalSegment.DEFAULT_PREFIX_BYTES, Files.size(file));
    }

    // ===== Filters and test mode =====

    @Test
    void ruleForAnotherRequestDoesNotFire() throws IOException {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("requestId", "CP1-WAL-004-other");
        arm(FailpointNames.WAL_BEFORE_APPEND, Action.IO_ERROR, parameters);

        segment.append(CANDIDATE, OpContext.withTimeout("CP1-WAL-004-put", 5_000));
        segment.force(CTX);

        assertEquals(CANDIDATE, WalReader.scanSegment(file).records().getLast());
    }

    @Test
    void failpointsDoNothingOutsideTestMode() throws IOException {
        FailpointRegistry disabled = new FailpointRegistry(false);
        disabled.addRule(new FailpointRegistry.Rule("tok", FailpointNames.WAL_BEFORE_APPEND,
                Action.IO_ERROR, 0, 1, 0, Map.of()));
        Path walDir = Files.createDirectories(root.resolve("other/wal"));
        Path tmpDir = Files.createDirectories(root.resolve("other/tmp"));
        WalSegments segments = new WalSegments(walDir, tmpDir, new RecordingStorageIO(disabled),
                disabled, new StorageStats(), WalIdAllocator.startingAt(1),
                new JsonLog("test", new PrintStream(new ByteArrayOutputStream()), Clock.systemUTC()));

        WalSegment other = segments.createSegment(CTX);
        try {
            other.append(CANDIDATE, CTX);
            other.force(CTX);
        } finally {
            other.close();
        }

        assertEquals(List.of(CANDIDATE), WalReader.scanSegment(
                walDir.resolve(WalFileNames.segmentFileName(1))).records());
    }

    // ===== Helpers =====

    /** Replaces the segment with a new one holding only base1 and base2. */
    private void setUpFreshSegment() throws IOException {
        failpoints.clear();
        segment.close();
        for (Path dir : List.of(root.resolve("lsm/wal"), root.resolve("lsm/tmp"))) {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                }
            }
        }
        setUp();
    }

    /** Waits for proof that the failpoint was reached; never a fixed sleep. */
    private void awaitState(String token, State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (failpoints.stateOf(token) != expected) {
            if (System.nanoTime() > deadline) {
                fail("Failpoint " + token + " did not reach " + expected);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
