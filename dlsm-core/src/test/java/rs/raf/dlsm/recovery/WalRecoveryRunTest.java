package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.FlushScheduler;
import rs.raf.dlsm.engine.GetResult;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.RecoveredState;
import rs.raf.dlsm.wal.WalFileNames;
import rs.raf.dlsm.wal.WalIdAllocator;
import rs.raf.dlsm.wal.WalReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static rs.raf.dlsm.recovery.WalRecoveryHarness.*;

/** WalRecovery.run, CP1 §21.1 steps 7–13 together, and recovery after recovery. */
class WalRecoveryRunTest {

    private static final OpContext CTX = OpContext.background();
    private static final long MAX_BYTES = 1L << 20;

    @TempDir
    Path root;

    private WalRecoveryHarness h;
    private final List<WalRecoveryResult> results = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        h = new WalRecoveryHarness(root);
    }

    @AfterEach
    void closeActiveSegments() {
        results.forEach(r -> r.activeLog().close());
    }

    /** One startup: the allocator is built from disk first, as before cleanup. */
    private WalRecoveryResult recover(long flushedThrough) throws Exception {
        WalIdAllocator ids = WalIdAllocator.fromDisk(h.layout.walDir(), h.layout.tmpDir());
        WalRecoveryResult result = h.recovery.run(flushedThrough, ids, MAX_BYTES, CTX);
        results.add(result);
        return result;
    }

    private void writeWal007() throws Exception {
        h.writeSegment(9, segment(9, put("gamma", 5, "G"), put("alpha", 6, "A3")));
        h.writeSegment(5, segment(5, put("alpha", 3, "A2"), InternalRecord.delete("beta", 4)));
        h.writeSegment(2, segment(2, put("alpha", 1, "A1"), put("beta", 2, "B1")));
    }

    private static String segmentName(long id) {
        return WalFileNames.segmentFileName(id);
    }

    // ===== A new database =====

    @Test
    void emptyWalGetsFirstSegmentAndNothingElse() throws Exception {
        WalRecoveryResult result = recover(0);

        assertEquals(0, result.lastUsedSequence());
        assertNull(result.recoveredGeneration());
        assertEquals(1, result.activeLog().segmentId());
        assertEquals(List.of(), result.retiredSegmentIds());
        assertEquals(List.of(segmentName(1)), h.walFiles());
    }

    // ===== CP1-WAL-007: new segment above every old one =====

    @Test
    void newActiveSegmentIsAboveEveryRecoveredSegment() throws Exception {
        writeWal007();

        WalRecoveryResult result = recover(0);

        assertEquals(6, result.lastUsedSequence());
        assertEquals(10, result.activeLog().segmentId());
        assertEquals(List.of(2L, 5L, 9L), result.recoveredGeneration().walSegmentIds());
        assertEquals(List.of(), result.retiredSegmentIds());
        assertEquals(List.of(segmentName(2), segmentName(5), segmentName(9), segmentName(10)),
                h.walFiles());
    }

    // ===== Step 13 =====

    @Test
    void deletesEmptyAndCoveredSegmentsAfterCreatingTheNewOne() throws Exception {
        writeWal007();
        h.writeSegment(3, segment(3));

        WalRecoveryResult result = recover(2);

        assertEquals(List.of(2L, 3L), result.retiredSegmentIds());
        assertEquals(List.of(segmentName(5), segmentName(9), segmentName(10)), h.walFiles());

        List<String> calls = h.io.calls();
        int created = calls.indexOf("atomicMoveNoReplace " + segmentName(10));
        int firstDelete = calls.indexOf("delete " + segmentName(2));
        assertTrue(created >= 0 && firstDelete > created, calls.toString());
        assertEquals("fsyncDirectory wal", calls.getLast());
    }

    @Test
    void everythingCoveredLeavesOnlyTheNewSegment() throws Exception {
        writeWal007();

        WalRecoveryResult result = recover(6);

        assertNull(result.recoveredGeneration());
        assertEquals(List.of(2L, 5L, 9L), result.retiredSegmentIds());
        assertEquals(List.of(segmentName(10)), h.walFiles());
        assertEquals(6, result.lastUsedSequence());
    }

    // A segment with any record above the mark stays, even next to deleted ones.
    @Test
    void partlyCoveredSegmentStays() throws Exception {
        h.writeSegment(1, segment(1, put("a", 1, "x"), put("b", 2, "x")));
        h.writeSegment(2, segment(2, put("c", 3, "x"), put("d", 4, "x")));

        WalRecoveryResult result = recover(3);

        assertEquals(List.of(1L), result.retiredSegmentIds());
        assertEquals(List.of(2L), result.recoveredGeneration().walSegmentIds());
        assertEquals(1, result.recoveredGeneration().entryCount());
    }

    @Test
    void segmentLeftEmptyByTruncationIsDeleted() throws Exception {
        h.writeSegment(1, segment(1, put("base", 1, "x")));
        byte[] tornOnly = segment(2, put("torn", 2, "y".repeat(10)));
        h.writeSegment(2, Arrays.copyOf(tornOnly, 30));

        WalRecoveryResult result = recover(0);

        assertEquals(List.of(2L), result.retiredSegmentIds());
        assertEquals(1, result.lastUsedSequence());
        assertEquals(List.of(segmentName(1), segmentName(3)), h.walFiles());
    }

    // ===== Recovery after recovery (CP1-WAL-008, I6) =====

    @Test
    void repeatedRecoveryGivesTheSameLogicalState() throws Exception {
        writeWal007();
        byte[] withTornTail = segment(9, put("gamma", 5, "G"), put("alpha", 6, "A3"), put("late", 7, "L"));
        int tornAt = segment(9, put("gamma", 5, "G"), put("alpha", 6, "A3")).length;
        h.writeSegment(9, Arrays.copyOf(withTornTail, tornAt + 9));

        WalRecoveryResult first = recover(0);
        // Crash right after recovery: nothing was appended to the new segment.
        WalRecoveryResult second = recover(0);

        assertEquals(first.lastUsedSequence(), second.lastUsedSequence());
        assertEquals(entries(first), entries(second));
        assertTrue(second.activeLog().segmentId() > first.activeLog().segmentId());
        // The first run's unused active segment is empty and retired by the second.
        assertEquals(List.of(first.activeLog().segmentId()), second.retiredSegmentIds());
        assertEquals(1, h.counter("walTornTailsTruncated"));
        assertEquals(tornAt, Files.size(h.segmentFile(9)));
    }

    @Test
    void recordsAppendedAfterRecoverySurviveTheNextOne() throws Exception {
        writeWal007();
        WalRecoveryResult first = recover(0);
        first.activeLog().append(put("delta", first.lastUsedSequence() + 1, "D"), CTX);
        first.activeLog().force(CTX);

        WalRecoveryResult second = recover(0);

        assertEquals(7, second.lastUsedSequence());
        assertEquals("D", value(second, "delta"));
        assertEquals(List.of(2L, 5L, 9L, 10L), second.recoveredGeneration().walSegmentIds());
        assertEquals(11, second.activeLog().segmentId());
    }

    // ===== Failures =====

    @Test
    void failureToCreateTheActiveSegmentDeletesNothing() throws Exception {
        writeWal007();
        List<String> before = h.walFiles();
        h.io.failOn("openNew");

        assertThrows(IOException.class, () -> h.recovery.run(6,
                WalIdAllocator.fromDisk(h.layout.walDir(), h.layout.tmpDir()), MAX_BYTES, CTX));

        assertEquals(before, h.walFiles());
    }

    @Test
    void failedCleanupIsFinishedByTheNextRecovery() throws Exception {
        writeWal007();
        h.io.failOn("delete");
        assertThrows(IOException.class, () -> h.recovery.run(6,
                WalIdAllocator.fromDisk(h.layout.walDir(), h.layout.tmpDir()), MAX_BYTES, CTX));
        h.io.failOn(null);

        WalRecoveryResult next = recover(6);

        assertEquals(List.of(segmentName(next.activeLog().segmentId())), h.walFiles());
    }

    // ===== Hand-over to the engine =====

    @Test
    void resultOpensTheEngine() throws Exception {
        writeWal007();
        WalRecoveryResult result = recover(0);
        RecoveredState state = new RecoveredState(result.lastUsedSequence(),
                result.recoveredGeneration(), List.of(), result.activeLog(), result.walFactory());

        try (Engine engine = Engine.open(EngineConfig.defaults(), state, host -> FlushScheduler.NONE)) {
            OpContext ctx = OpContext.withTimeout("recovery-run-1", 5_000);

            GetResult alpha = engine.get("alpha", ctx);
            assertTrue(alpha.isFound());
            assertArrayEquals("A3".getBytes(StandardCharsets.US_ASCII), alpha.value());
            assertFalse(engine.get("beta", ctx).isFound());
            assertEquals(7, engine.put("delta", new byte[]{1}, ctx).sequence());
        }

        assertEquals(7, WalReader.scanLog(h.layout.walDir()).lastSequence().getAsLong());
    }

    private static List<InternalRecord> entries(WalRecoveryResult result) {
        List<InternalRecord> records = new ArrayList<>();
        result.recoveredGeneration().iterator().forEachRemaining(records::add);
        return records;
    }

    private static String value(WalRecoveryResult result, String key) {
        return new String(result.recoveredGeneration().get(key).orElseThrow().value(),
                StandardCharsets.US_ASCII);
    }
}
