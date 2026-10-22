package rs.raf.dlsm.recovery;

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
import rs.raf.dlsm.io.InjectedIOException;
import rs.raf.dlsm.wal.WalFileNames;
import rs.raf.dlsm.wal.WalIdAllocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static rs.raf.dlsm.recovery.WalRecoveryHarness.*;

/**
 * The two WAL recovery failpoints (CP1 §26.1–26.2). {@code CRASH} halts the
 * JVM, so here {@code PAUSE} shows the exact state at each boundary and
 * {@code IO_ERROR} stands in for a crash there: the next start must reach
 * the same logical state as a start without any failure (CP1-WAL-008).
 */
class WalRecoveryFailpointTest {

    private static final OpContext CTX = OpContext.background();
    private static final long MAX_BYTES = 1L << 20;
    private static final String TRUNCATE = FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE;
    private static final String BEFORE_READY = FailpointNames.RECOVERY_AFTER_WAL_REPLAY_BEFORE_READY;

    @TempDir
    Path root;

    private WalRecoveryHarness h;
    private int tornAt;
    private final List<WalRecoveryResult> results = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        h = new WalRecoveryHarness(root);
        tornAt = writeVolume(h);
    }

    @AfterEach
    void tearDown() {
        h.failpoints.clear();
        results.forEach(r -> r.activeLog().close());
    }

    /**
     * Segment 1 fully covered by the manifest (sequences 1–2), segment 2 with
     * 3–4 and a torn frame for 5. Returns where the torn frame starts.
     */
    private static int writeVolume(WalRecoveryHarness harness) throws Exception {
        harness.writeSegment(1, segment(1, put("a", 1, "A1"), put("b", 2, "B1")));
        InternalRecord[] kept = {put("a", 3, "A2"), InternalRecord.delete("b", 4)};
        byte[] full = segment(2, kept[0], kept[1], put("c", 5, "C"));
        int base = segment(2, kept).length;
        harness.writeSegment(2, Arrays.copyOf(full, base + 6));
        return base;
    }

    private WalRecoveryResult recover(WalRecoveryHarness harness) throws Exception {
        WalRecoveryResult result = harness.recovery.run(2,
                WalIdAllocator.fromDisk(harness.layout.walDir(), harness.layout.tmpDir()), MAX_BYTES, CTX);
        results.add(result);
        return result;
    }

    private void arm(String name, Action action) {
        h.failpoints.addRule(new FailpointRegistry.Rule("tok-" + name, name, action, 0, 1, 0, Map.of()));
    }

    private CompletableFuture<WalRecoveryResult> recoverAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return recover(h);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ===== PAUSE shows the state at each boundary =====

    @Test
    void afterTailTruncateTheTailIsRepairedAndNothingElseHappened() throws Exception {
        arm(TRUNCATE, Action.PAUSE);
        CompletableFuture<WalRecoveryResult> running = recoverAsync();

        awaitTriggered("tok-" + TRUNCATE);

        assertEquals(tornAt, Files.size(h.segmentFile(2)));
        assertEquals(1, h.counter("walTornTailsTruncated"));
        assertEquals(0, h.counter("walRecordsReplayed"));
        assertEquals(List.of(WalFileNames.segmentFileName(1), WalFileNames.segmentFileName(2)), h.walFiles());

        h.failpoints.releaseRule("tok-" + TRUNCATE);
        WalRecoveryResult result = running.get(10, TimeUnit.SECONDS);
        assertEquals(3, result.activeLog().segmentId());
    }

    @Test
    void tailTruncateFailpointIsReachedEvenWithoutATornTail() throws Exception {
        h.writeSegment(2, segment(2, put("a", 3, "A2"), InternalRecord.delete("b", 4)));
        arm(TRUNCATE, Action.PAUSE);
        CompletableFuture<WalRecoveryResult> running = recoverAsync();

        awaitTriggered("tok-" + TRUNCATE);
        assertEquals(0, h.counter("walTornTailsTruncated"));

        h.failpoints.releaseRule("tok-" + TRUNCATE);
        running.get(10, TimeUnit.SECONDS);
    }

    @Test
    void beforeReadyTheNewSegmentExistsAndCoveredOnesAreGone() throws Exception {
        arm(BEFORE_READY, Action.PAUSE);
        CompletableFuture<WalRecoveryResult> running = recoverAsync();

        awaitTriggered("tok-" + BEFORE_READY);

        assertEquals(List.of(WalFileNames.segmentFileName(2), WalFileNames.segmentFileName(3)), h.walFiles());
        assertEquals(2, h.counter("walRecordsReplayed"));
        assertFalse(running.isDone());

        h.failpoints.releaseRule("tok-" + BEFORE_READY);
        WalRecoveryResult result = running.get(10, TimeUnit.SECONDS);
        assertEquals(4, result.lastUsedSequence());
    }

    // ===== A failure at a boundary, then a clean start =====

    @Test
    void failureAfterTailTruncateIsRecoveredWithoutCuttingAgain() throws Exception {
        arm(TRUNCATE, Action.IO_ERROR);
        assertThrows(InjectedIOException.class, () -> recover(h));
        h.failpoints.clear();

        WalRecoveryResult next = recover(h);

        assertEquals(1, h.counter("walTornTailsTruncated"));
        assertEquals(tornAt, Files.size(h.segmentFile(2)));
        assertEquals(4, next.lastUsedSequence());
    }

    @Test
    void failureBeforeReadyLeavesASegmentTheNextStartRetires() throws Exception {
        arm(BEFORE_READY, Action.IO_ERROR);
        assertThrows(InjectedIOException.class, () -> recover(h));
        h.failpoints.clear();
        assertTrue(h.walFiles().contains(WalFileNames.segmentFileName(3)));

        WalRecoveryResult next = recover(h);

        assertEquals(List.of(3L), next.retiredSegmentIds());
        assertEquals(4, next.activeLog().segmentId());
        assertEquals(List.of(WalFileNames.segmentFileName(2), WalFileNames.segmentFileName(4)), h.walFiles());
    }

    // CP1-WAL-008: repeated failures during recovery end in the same content
    // as a recovery of an untouched copy of the same volume.
    @Test
    void repeatedFailuresEndInTheSameStateAsACleanRecovery() throws Exception {
        WalRecoveryHarness control = new WalRecoveryHarness(Files.createDirectory(root.resolve("control")));
        writeVolume(control);
        WalRecoveryResult expected = recover(control);

        arm(TRUNCATE, Action.IO_ERROR);
        assertThrows(InjectedIOException.class, () -> recover(h));
        arm(BEFORE_READY, Action.IO_ERROR);
        h.failpoints.removeRule("tok-" + TRUNCATE);
        assertThrows(InjectedIOException.class, () -> recover(h));
        h.failpoints.clear();
        WalRecoveryResult actual = recover(h);

        assertEquals(expected.lastUsedSequence(), actual.lastUsedSequence());
        assertEquals(entries(expected), entries(actual));
        assertEquals(expected.recoveredGeneration().walSegmentIds(),
                actual.recoveredGeneration().walSegmentIds());
        assertEquals(1, h.counter("walTornTailsTruncated"));
    }

    private static List<InternalRecord> entries(WalRecoveryResult result) {
        List<InternalRecord> records = new ArrayList<>();
        result.recoveredGeneration().iterator().forEachRemaining(records::add);
        return records;
    }

    /** Waits for proof that the failpoint was reached; never a fixed sleep. */
    private void awaitTriggered(String token) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (h.failpoints.stateOf(token) != State.TRIGGERED) {
            if (System.nanoTime() > deadline) {
                fail("Failpoint " + token + " was not reached");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
