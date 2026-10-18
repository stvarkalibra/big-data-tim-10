package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class WalLogScanTest {

    @TempDir
    Path dir;

    /** The valid prepared volume of CP1-WAL-007: segments 2, 5 and 9. */
    private Path[] writeWal007Segments() throws IOException {
        // Written newest first, so creation order never matches log order.
        Path s9 = WalSegmentFixture.segment(9).put("gamma", 5, "G").put("alpha", 6, "A3").writeTo(dir);
        Path s5 = WalSegmentFixture.segment(5).put("alpha", 3, "A2").delete("beta", 4).writeTo(dir);
        Path s2 = WalSegmentFixture.segment(2).put("alpha", 1, "A1").put("beta", 2, "B1").writeTo(dir);
        return new Path[]{s2, s5, s9};
    }

    // ===== Order and gaps (CP1-WAL-007) =====

    @Test
    void readsWal007SegmentsBySegmentIdWithIdGaps() throws IOException {
        writeWal007Segments();

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(WalScanResult.Status.VALID, log.status());
        assertEquals(List.of(2L, 5L, 9L), log.segmentIds());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
                log.records().stream().map(InternalRecord::sequence).toList());
        assertEquals(OptionalLong.of(6), log.lastSequence());

        InternalRecord last = log.records().getLast();
        assertEquals("alpha", last.key());
        assertArrayEquals("A3".getBytes(StandardCharsets.US_ASCII), last.value());
        assertTrue(log.records().get(3).isTombstone());
    }

    @Test
    void givenFileOrderNeverDecidesLogOrder() throws IOException {
        Path[] segments = writeWal007Segments();

        WalLogScan log = WalReader.scanSegments(List.of(segments[2], segments[0], segments[1]));

        assertEquals(List.of(2L, 5L, 9L), log.segmentIds());
        assertEquals(WalScanResult.Status.VALID, log.status());
    }

    @Test
    void emptyWalDirectoryIsValidAndEmpty() throws IOException {
        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(WalScanResult.Status.VALID, log.status());
        assertTrue(log.segments().isEmpty());
        assertEquals(OptionalLong.empty(), log.lastSequence());
    }

    @Test
    void headerOnlySegmentsBetweenRecordsAreAllowed() throws IOException {
        WalSegmentFixture.segment(1).put("a", 1, "x").writeTo(dir);
        WalSegmentFixture.segment(2).writeTo(dir);
        WalSegmentFixture.segment(3).put("b", 2, "y").writeTo(dir);

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(WalScanResult.Status.VALID, log.status());
        assertEquals(List.of(1L, 2L, 3L), log.segmentIds());
        assertEquals(OptionalLong.of(2), log.lastSequence());
    }

    @Test
    void ignoresFilesThatAreNotFinalSegments() throws IOException {
        WalSegmentFixture.segment(1).put("a", 1, "x").writeTo(dir);
        byte[] other = WalSegmentFixture.segment(2).put("b", 1, "y").bytes();
        Files.write(dir.resolve("notes.txt"), other);
        Files.write(dir.resolve(WalFileNames.tempFileName(2)), other);
        Files.write(dir.resolve("wal-00000000000000000000.log"), other);
        Files.createDirectory(dir.resolve(WalFileNames.segmentFileName(3)));

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(List.of(1L), log.segmentIds());
        assertEquals(WalScanResult.Status.VALID, log.status());
    }

    // ===== Invalid order across segments (CP1-WAL-007 subcase) =====

    @Test
    void duplicateSequenceAcrossSegmentBoundaryIsCorrupt() throws IOException {
        WalSegmentFixture.segment(2).put("alpha", 1, "A1").put("beta", 2, "B1").writeTo(dir);
        WalSegmentFixture.segment(5).put("alpha", 2, "A2").writeTo(dir);
        WalSegmentFixture.segment(9).put("gamma", 5, "G").writeTo(dir);

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(WalScanResult.Status.CORRUPT, log.status());
        WalScanResult corrupt = log.corruptSegment().orElseThrow();
        assertEquals(5, corrupt.segmentId());
        assertEquals(WalFormat.CATEGORY_SEQUENCE_ORDER, corrupt.corruption().category());
        assertEquals(WalFormat.SEGMENT_HEADER_BYTES, corrupt.corruption().offset());
        // Nothing is sorted or applied past the first invalid boundary.
        assertEquals(List.of(2L, 5L), log.segmentIds());
    }

    @Test
    void decreasingSequenceAcrossSegmentBoundaryIsCorrupt() throws IOException {
        WalSegmentFixture.segment(2).put("a", 5, "x").writeTo(dir);
        WalSegmentFixture.segment(3).writeTo(dir);
        WalSegmentFixture.segment(4).put("b", 3, "y").writeTo(dir);

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(4, log.corruptSegment().orElseThrow().segmentId());
        assertEquals(WalFormat.CATEGORY_SEQUENCE_ORDER,
                log.corruptSegment().orElseThrow().corruption().category());
    }

    @Test
    void corruptSegmentStopsTheScan() throws IOException {
        WalSegmentFixture.segment(1).put("a", 1, "x").writeTo(dir);
        byte[] middle = WalSegmentFixture.segment(2).put("b", 2, "y").bytes();
        middle[0] ^= 0x01;
        WalSegmentFixture.write(dir, 2, middle);
        WalSegmentFixture.segment(3).put("c", 3, "z").writeTo(dir);

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(WalScanResult.Status.CORRUPT, log.status());
        assertEquals(List.of(1L, 2L), log.segmentIds());
        assertEquals(WalFormat.CATEGORY_HEADER,
                log.corruptSegment().orElseThrow().corruption().category());
    }

    // ===== Torn tail only at the end of the whole log =====

    @Test
    void tornTailInLastSegmentIsAllowed() throws IOException {
        WalSegmentFixture.segment(1).put("a", 1, "x").writeTo(dir);
        WalSegmentFixture last = WalSegmentFixture.segment(2).put("b", 2, "y").put("c", 3, "z");
        WalSegmentFixture.write(dir, 2, Arrays.copyOf(last.bytes(), last.size() - 1));

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(WalScanResult.Status.TORN_TAIL, log.status());
        WalScanResult torn = log.tornSegment().orElseThrow();
        assertEquals(2, torn.segmentId());
        assertEquals(last.frameOffset(1), torn.tornFrameOffset());
        assertEquals(OptionalLong.of(2), log.lastSequence());
    }

    // Rotation may create a new segment before switching to it.
    @Test
    void tornTailFollowedByHeaderOnlySegmentsIsAllowed() throws IOException {
        WalSegmentFixture torn = WalSegmentFixture.segment(1).put("a", 1, "x").put("b", 2, "y");
        WalSegmentFixture.write(dir, 1, Arrays.copyOf(torn.bytes(), torn.size() - 5));
        WalSegmentFixture.segment(2).writeTo(dir);
        WalSegmentFixture.segment(3).writeTo(dir);

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(WalScanResult.Status.TORN_TAIL, log.status());
        assertEquals(1, log.tornSegment().orElseThrow().segmentId());
        assertEquals(List.of(1L, 2L, 3L), log.segmentIds());
    }

    @Test
    void tornTailFollowedByRecordsIsFrameCorruption() throws IOException {
        WalSegmentFixture torn = WalSegmentFixture.segment(1).put("a", 1, "x").put("b", 2, "y");
        WalSegmentFixture.write(dir, 1, Arrays.copyOf(torn.bytes(), torn.size() - 5));
        WalSegmentFixture.segment(2).writeTo(dir);
        WalSegmentFixture.segment(3).put("c", 3, "z").writeTo(dir);

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(WalScanResult.Status.CORRUPT, log.status());
        WalScanResult corrupt = log.corruptSegment().orElseThrow();
        assertEquals(1, corrupt.segmentId());
        assertEquals(WalFormat.CATEGORY_FRAME, corrupt.corruption().category());
        assertEquals(torn.frameOffset(1), corrupt.corruption().offset());
        assertEquals(List.of(1L), log.segmentIds());
        assertEquals(List.of("a"), log.records().stream().map(InternalRecord::key).toList());
    }

    @Test
    void tornTailFollowedByAnotherTornSegmentIsFrameCorruption() throws IOException {
        WalSegmentFixture first = WalSegmentFixture.segment(1).put("a", 1, "x").put("b", 2, "y");
        WalSegmentFixture.write(dir, 1, Arrays.copyOf(first.bytes(), first.size() - 5));
        WalSegmentFixture second = WalSegmentFixture.segment(2).put("c", 3, "z");
        WalSegmentFixture.write(dir, 2, Arrays.copyOf(second.bytes(), second.size() - 5));

        WalLogScan log = WalReader.scanLog(dir);

        assertEquals(1, log.corruptSegment().orElseThrow().segmentId());
        assertEquals(WalFormat.CATEGORY_FRAME,
                log.corruptSegment().orElseThrow().corruption().category());
    }

    @Test
    void logScanRejectsSegmentsOutOfOrder() {
        WalScanResult second = WalScanResult.valid(2, List.of(), 24);
        WalScanResult first = WalScanResult.valid(1, List.of(), 24);

        assertThrows(IllegalArgumentException.class,
                () -> new WalLogScan(List.of(second, first)));
        assertThrows(IllegalArgumentException.class,
                () -> new WalLogScan(List.of(first, first)));
    }
}
