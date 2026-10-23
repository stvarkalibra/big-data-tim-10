package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.TableSource;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.failpoint.FailpointRegistry.State;
import rs.raf.dlsm.io.InjectedIOException;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableRecordAdapter;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.RecordingStorageIO;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static rs.raf.dlsm.recovery.WalRecoveryHarness.put;
import static rs.raf.dlsm.recovery.WalRecoveryHarness.segment;

/**
 * All three recovery failpoints of CP1 §26.1 inside the full procedure.
 * {@code CRASH} halts the JVM, so {@code IO_ERROR} stands in for a crash at
 * the same boundary, and the process "dies" by releasing its lock. However
 * often recovery is interrupted, the final state must equal a recovery of
 * an untouched copy (CP1-WAL-008, I6).
 */
class RecoveryFailpointTest {

    private static final OpContext CTX = OpContext.background();
    private static final long MAX_BYTES = 1L << 20;
    private static final String MANIFEST_LOAD = FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD;
    private static final String TAIL_TRUNCATE = FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE;
    private static final String BEFORE_READY = FailpointNames.RECOVERY_AFTER_WAL_REPLAY_BEFORE_READY;

    @TempDir
    Path tempDir;

    private final FailpointRegistry failpoints = new FailpointRegistry(true);
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        failpoints.clear();
        for (AutoCloseable resource : toClose.reversed()) {
            resource.close();
        }
    }

    /**
     * A volume after some work: table 1 published through sequence 2,
     * WAL suffix 3–4 and a torn frame for 5, a temp leftover and an orphan.
     * Returns the offset where the torn frame starts.
     */
    private int prepareVolume(DataLayout layout) throws Exception {
        RecordingStorageIO io = new RecordingStorageIO(new FailpointRegistry(true));
        try (RecoveryProcedure first = new RecoveryProcedure(layout, "standalone", io,
                new FailpointRegistry(true), new StorageStats(), quietLog())) {
            first.recover(MAX_BYTES, CTX).wal().activeLog().close();
        }

        SSTableWriter.Result table = SSTableWriter.build(1, SSTableRecordAdapter.adapt(
                List.of(put("alpha", 1, "A1"), put("beta", 2, "B1")).iterator()), 2);
        Files.write(layout.sstDir().resolve(table.meta().file()), table.bytes());
        ManifestManager manifests = new ManifestManager(layout, io);
        manifests.loadAndValidate();
        manifests.publish(base -> new Manifest(base.formatVersion(), base.generation() + 1, 2, 2,
                List.of(table.meta())), CTX);

        InternalRecord[] suffix = {put("alpha", 3, "A2"), InternalRecord.delete("beta", 4)};
        byte[] full = segment(2, suffix[0], suffix[1], put("gamma", 5, "G"));
        int tornAt = segment(2, suffix).length;
        Files.write(layout.walDir().resolve(WalFileNames.segmentFileName(2)), Arrays.copyOf(full, tornAt + 7));

        Files.write(layout.tmpDir().resolve(WalFileNames.tempFileName(6)), new byte[]{1});
        Files.write(layout.sstDir().resolve(SSTableMeta.fileName(9)), new byte[]{1});
        return tornAt;
    }

    private static JsonLog quietLog() {
        return new JsonLog("test", new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
    }

    private RecoveryProcedure procedure(DataLayout layout) {
        RecoveryProcedure procedure = new RecoveryProcedure(layout, "standalone",
                new RecordingStorageIO(failpoints), failpoints, new StorageStats(), quietLog());
        toClose.add(procedure);
        return procedure;
    }

    private RecoveryResult recover(DataLayout layout) throws Exception {
        RecoveryResult result = procedure(layout).recover(MAX_BYTES, CTX);
        toClose.add(result.wal().activeLog()::close);
        return result;
    }

    /** Recovery stopped by an injected failure; the "process" then dies and frees the lock. */
    private void crashAt(DataLayout layout, String failpoint) throws Exception {
        failpoints.addRule(new FailpointRegistry.Rule("tok-" + failpoint, failpoint,
                Action.IO_ERROR, 0, 1, 0, Map.of()));
        RecoveryProcedure procedure = procedure(layout);
        assertThrows(InjectedIOException.class, () -> procedure.recover(MAX_BYTES, CTX), failpoint);
        failpoints.clear();
        procedure.close();
    }

    private void arm(String failpoint, Action action) {
        failpoints.addRule(new FailpointRegistry.Rule("tok-" + failpoint, failpoint, action, 0, 1, 0, Map.of()));
    }

    private void awaitTriggered(String failpoint) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (failpoints.stateOf("tok-" + failpoint) != State.TRIGGERED) {
            assertTrue(System.nanoTime() < deadline, failpoint + " was not reached");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static List<String> names(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static List<InternalRecord> recoveredRecords(RecoveryResult result) {
        List<InternalRecord> records = new ArrayList<>();
        result.wal().recoveredGeneration().iterator().forEachRemaining(records::add);
        return records;
    }

    private static List<Long> tableIds(RecoveryResult result) {
        return result.tables().stream().map(TableSource::tableId).toList();
    }

    // ===== PAUSE at recovery.after-manifest-load =====

    // CP1 §26.2: the manifest is read, but nothing is cleaned or replayed yet.
    @Test
    void afterManifestLoadNothingIsCleanedOrRepaired() throws Exception {
        DataLayout layout = new DataLayout(tempDir.resolve("db"));
        int tornAt = prepareVolume(layout);
        long tornSize = Files.size(layout.walDir().resolve(WalFileNames.segmentFileName(2)));
        arm(MANIFEST_LOAD, Action.PAUSE);

        CompletableFuture<RecoveryResult> running = CompletableFuture.supplyAsync(() -> {
            try {
                return recover(layout);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        awaitTriggered(MANIFEST_LOAD);

        assertTrue(Files.exists(layout.tmpDir().resolve(WalFileNames.tempFileName(6))));
        assertTrue(Files.exists(layout.sstDir().resolve(SSTableMeta.fileName(9))));
        assertEquals(tornSize, Files.size(layout.walDir().resolve(WalFileNames.segmentFileName(2))));

        failpoints.releaseRule("tok-" + MANIFEST_LOAD);
        RecoveryResult result = running.get(10, TimeUnit.SECONDS);
        assertEquals(tornAt, Files.size(layout.walDir().resolve(WalFileNames.segmentFileName(2))));
        assertEquals(4, result.wal().lastUsedSequence());
    }

    // ===== One crash at each boundary, then a clean start =====

    @Test
    void eachRecoveryFailpointIsSafeToCrashAt() throws Exception {
        DataLayout control = new DataLayout(tempDir.resolve("control"));
        prepareVolume(control);
        RecoveryResult expected = recover(control);

        for (String failpoint : List.of(MANIFEST_LOAD, TAIL_TRUNCATE, BEFORE_READY)) {
            DataLayout layout = new DataLayout(tempDir.resolve("db-" + failpoint));
            prepareVolume(layout);

            crashAt(layout, failpoint);
            RecoveryResult actual = recover(layout);

            assertEquals(expected.wal().lastUsedSequence(), actual.wal().lastUsedSequence(), failpoint);
            assertEquals(recoveredRecords(expected), recoveredRecords(actual), failpoint);
            assertEquals(tableIds(expected), tableIds(actual), failpoint);
            assertEquals(List.of(), names(layout.tmpDir()), failpoint);
        }
    }

    // ===== CP1-WAL-008: repeated crashes during recovery =====

    @Test
    void repeatedCrashesEndInTheSameStateAsACleanRecovery() throws Exception {
        DataLayout control = new DataLayout(tempDir.resolve("control"));
        prepareVolume(control);
        RecoveryResult expected = recover(control);

        DataLayout layout = new DataLayout(tempDir.resolve("db"));
        int tornAt = prepareVolume(layout);
        crashAt(layout, MANIFEST_LOAD);
        crashAt(layout, TAIL_TRUNCATE);
        crashAt(layout, BEFORE_READY);
        crashAt(layout, BEFORE_READY);
        RecoveryResult actual = recover(layout);

        assertEquals(expected.wal().lastUsedSequence(), actual.wal().lastUsedSequence());
        assertEquals(recoveredRecords(expected), recoveredRecords(actual));
        assertEquals(tableIds(expected), tableIds(actual));
        // The torn tail was cut exactly once and no complete frame was ever lost.
        assertEquals(tornAt, Files.size(layout.walDir().resolve(WalFileNames.segmentFileName(2))));
        // Only the segment protecting sequences 3–4 and the newest active one remain.
        List<String> wal = names(layout.walDir());
        assertEquals(List.of(WalFileNames.segmentFileName(2),
                WalFileNames.segmentFileName(actual.wal().activeLog().segmentId())), wal);
    }
}
