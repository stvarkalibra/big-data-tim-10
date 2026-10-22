package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.ImmutableMemTable;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.wal.WalLogScan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static rs.raf.dlsm.recovery.WalRecoveryHarness.*;

/** CP1 §21.1 steps 10–11 and §21.3: replay above the high-water mark, next sequence. */
class WalRecoveryReplayTest {

    private static final long MAX_BYTES = 1L << 20;

    @TempDir
    Path root;

    private WalRecoveryHarness h;

    @BeforeEach
    void setUp() throws Exception {
        h = new WalRecoveryHarness(root);
    }

    /** CP1-WAL-007 prepared volume: segments 2, 5 and 9, sequences 1..6. */
    private void writeWal007() throws Exception {
        h.writeSegment(9, segment(9, put("gamma", 5, "G"), put("alpha", 6, "A3")));
        h.writeSegment(5, segment(5, put("alpha", 3, "A2"), InternalRecord.delete("beta", 4)));
        h.writeSegment(2, segment(2, put("alpha", 1, "A1"), put("beta", 2, "B1")));
    }

    private static String value(ImmutableMemTable table, String key) {
        InternalRecord record = table.get(key).orElseThrow();
        return record.isTombstone() ? "<tombstone>" : new String(record.value(), StandardCharsets.US_ASCII);
    }

    // ===== CP1-WAL-007 =====

    @Test
    void replaysAllSegmentsByIdWithGreatestSequenceWinning() throws Exception {
        writeWal007();
        WalLogScan scan = h.recovery.scanLog();

        ImmutableMemTable recovered = h.recovery.replay(scan, 0, MAX_BYTES);

        assertEquals("A3", value(recovered, "alpha"));
        assertEquals("<tombstone>", value(recovered, "beta"));
        assertEquals("G", value(recovered, "gamma"));
        assertEquals(3, recovered.entryCount());
        assertEquals(WalRecovery.RECOVERED_GENERATION, recovered.generation());
        assertEquals(List.of(2L, 5L, 9L), recovered.walSegmentIds());
        assertEquals(1, recovered.minSequence());
        assertEquals(6, recovered.maxSequence());
        assertEquals(6, h.counter("walRecordsReplayed"));
        assertEquals(6, WalRecovery.lastUsedSequence(scan, 0));
    }

    // ===== High-water mark =====

    @Test
    void skipsRecordsTheManifestAlreadyCovers() throws Exception {
        writeWal007();
        WalLogScan scan = h.recovery.scanLog();

        ImmutableMemTable recovered = h.recovery.replay(scan, 2, MAX_BYTES);

        // Segment 2 holds only sequences 1 and 2: it protects nothing any more.
        assertEquals(List.of(5L, 9L), recovered.walSegmentIds());
        assertEquals(3, recovered.minSequence());
        assertEquals(4, h.counter("walRecordsReplayed"));
        assertEquals("A3", value(recovered, "alpha"));
        assertEquals("<tombstone>", value(recovered, "beta"));
    }

    @Test
    void nothingAboveTheMarkGivesNoRecoveredGeneration() throws Exception {
        writeWal007();
        WalLogScan scan = h.recovery.scanLog();

        assertNull(h.recovery.replay(scan, 6, MAX_BYTES));
        assertEquals(0, h.counter("walRecordsReplayed"));
        assertEquals(6, WalRecovery.lastUsedSequence(scan, 6));
    }

    @Test
    void emptyLogGivesNoRecoveredGeneration() throws Exception {
        WalLogScan scan = h.recovery.scanLog();

        assertNull(h.recovery.replay(scan, 0, MAX_BYTES));
        assertEquals(0, WalRecovery.lastUsedSequence(scan, 0));
    }

    // ===== CP1 §21 worked example: manifest at 40, WAL 41–47, torn frame for 48 =====

    @Test
    void workedExampleFromTheSpecification() throws Exception {
        List<InternalRecord> records = new ArrayList<>();
        for (long sequence = 41; sequence <= 47; sequence++) {
            records.add(put("key" + sequence, sequence, "v" + sequence));
        }
        InternalRecord torn = put("key48", 48, "v48");
        byte[] full = segment(6, concat(records, torn));
        int base = segment(6, records.toArray(InternalRecord[]::new)).length;
        h.writeSegment(6, Arrays.copyOf(full, base + 11));

        WalLogScan scan = h.recovery.scanLog();
        assertTrue(h.recovery.truncateTornTail(scan, OpContext.background()));
        ImmutableMemTable recovered = h.recovery.replay(scan, 40, MAX_BYTES);

        assertEquals(7, recovered.entryCount());
        assertEquals(41, recovered.minSequence());
        assertEquals(47, recovered.maxSequence());
        assertTrue(recovered.get("key48").isEmpty());
        assertEquals(47, WalRecovery.lastUsedSequence(scan, 40));
    }

    // ===== Next sequence (step 11) =====

    @Test
    void nextSequenceIsAboveManifestWhenWalIsOlder() throws Exception {
        h.writeSegment(1, segment(1, put("a", 1, "x"), put("b", 2, "y")));

        assertEquals(40, WalRecovery.lastUsedSequence(h.recovery.scanLog(), 40));
    }

    // A replaced version still owns its sequence on disk.
    @Test
    void replacedVersionsStillCountForTheNextSequence() throws Exception {
        h.writeSegment(1, segment(1, put("a", 1, "x"), put("a", 2, "y"), put("a", 3, "z")));
        WalLogScan scan = h.recovery.scanLog();

        ImmutableMemTable recovered = h.recovery.replay(scan, 0, MAX_BYTES);

        assertEquals(1, recovered.entryCount());
        assertEquals(3, WalRecovery.lastUsedSequence(scan, 0));
    }

    @Test
    void rejectsNegativeHighWaterMark() throws Exception {
        WalLogScan scan = h.recovery.scanLog();

        assertThrows(IllegalArgumentException.class, () -> h.recovery.replay(scan, -1, MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> WalRecovery.lastUsedSequence(scan, -1));
    }

    // ===== What replay must keep and must not do =====

    @Test
    void keepsEmptyValueApartFromTombstone() throws Exception {
        h.writeSegment(1, segment(1, put("empty", 1, ""), InternalRecord.delete("gone", 2)));

        ImmutableMemTable recovered = h.recovery.replay(h.recovery.scanLog(), 0, MAX_BYTES);

        InternalRecord empty = recovered.get("empty").orElseThrow();
        assertFalse(empty.isTombstone());
        assertArrayEquals(new byte[0], empty.value());
        assertTrue(recovered.get("gone").orElseThrow().isTombstone());
    }

    // CP1 §21.3: replay never drops a valid record, whatever MEMTABLE_MAX_BYTES is.
    @Test
    void recoveredGenerationMayExceedTheMemtableLimit() throws Exception {
        List<InternalRecord> records = new ArrayList<>();
        for (long sequence = 1; sequence <= 200; sequence++) {
            records.add(put("key" + sequence, sequence, "x".repeat(100)));
        }
        h.writeSegment(1, segment(1, records.toArray(InternalRecord[]::new)));

        ImmutableMemTable recovered = h.recovery.replay(h.recovery.scanLog(), 0, 1_000);

        assertEquals(200, recovered.entryCount());
        assertTrue(recovered.logicalBytes() > 1_000);
    }

    // Replay reads the existing records; it never appends a recovery copy.
    @Test
    void replayWritesNothing() throws Exception {
        writeWal007();
        byte[] before = Files.readAllBytes(h.segmentFile(9));
        WalLogScan scan = h.recovery.scanLog();

        h.recovery.replay(scan, 0, MAX_BYTES);

        assertEquals(List.of(), h.io.calls());
        assertArrayEquals(before, Files.readAllBytes(h.segmentFile(9)));
    }

    @Test
    void recoveredGenerationIsInKeyOrder() throws Exception {
        writeWal007();

        Iterator<InternalRecord> records = h.recovery.replay(h.recovery.scanLog(), 0, MAX_BYTES).iterator();

        assertEquals("alpha", records.next().key());
        assertEquals("beta", records.next().key());
        assertEquals("gamma", records.next().key());
        assertFalse(records.hasNext());
    }

    private static InternalRecord[] concat(List<InternalRecord> records, InternalRecord last) {
        List<InternalRecord> all = new ArrayList<>(records);
        all.add(last);
        return all.toArray(InternalRecord[]::new);
    }
}
