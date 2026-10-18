package rs.raf.dlsm.inspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.Bin;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.wal.WalCodec;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InspectWalTest {

    @TempDir
    Path dataDir;

    /** The FORMAT.md §3.6 example: 129 bytes, frames at 24, 60 and 94. */
    private static byte[] exampleSegment() {
        return segment(1,
                InternalRecord.put("alpha", 1, "A".getBytes(StandardCharsets.US_ASCII)),
                InternalRecord.delete("beta", 2),
                InternalRecord.put("gamma", 3, new byte[0]));
    }

    private static byte[] segment(long id, InternalRecord... records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(WalCodec.encodeSegmentHeader(id));
        for (InternalRecord record : records) {
            out.writeBytes(WalCodec.encodeFrame(record));
        }
        return out.toByteArray();
    }

    private Path writeSegment(long id, byte[] bytes) throws IOException {
        Path walDir = Files.createDirectories(dataDir.resolve("lsm/wal"));
        return Files.write(walDir.resolve(WalFileNames.segmentFileName(id)), bytes);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) {
        return (Map<String, Object>) map.get(key);
    }

    // ===== One segment =====

    @Test
    void describesValidSegmentHeaderAndRecords() throws IOException {
        Map<String, Object> report = InspectWal.inspectSegment(writeSegment(1, exampleSegment()));

        assertEquals("wal-00000000000000000001.log", report.get("file"));
        assertEquals(1L, report.get("segmentId"));
        assertEquals("4C 53 4D 57 41 4C 30 31", report.get("headerMagic"));
        assertEquals("LSMWAL01", report.get("format"));
        assertEquals(1, report.get("formatVersion"));
        assertEquals(1L, report.get("headerSegmentId"));
        assertEquals("VALID", report.get("headerCrcStatus"));
        assertEquals(129L, report.get("sizeBytes"));
        assertEquals("VALID", report.get("status"));
        assertEquals(129L, report.get("validLength"));
        assertNull(report.get("tornTail"));
        assertNull(report.get("corruption"));
        assertEquals(3, report.get("recordCount"));
        assertEquals(1L, report.get("minSequence"));
        assertEquals(3L, report.get("maxSequence"));

        List<Map<String, Object>> records = list(report, "records");
        assertEquals(List.of(24L, 60L, 94L), records.stream().map(r -> r.get("offset")).toList());
        assertEquals(List.of(36, 34, 35), records.stream().map(r -> r.get("length")).toList());
        assertEquals(List.of(1L, 2L, 3L), records.stream().map(r -> r.get("sequence")).toList());
        assertEquals(List.of("PUT", "DELETE", "PUT"), records.stream().map(r -> r.get("kind")).toList());
        records.forEach(r -> assertEquals("VALID", r.get("crcStatus")));
    }

    // CP1 §25: a clear difference between a tombstone and an empty value.
    @Test
    void distinguishesTombstoneFromEmptyValue() throws IOException {
        List<Map<String, Object>> records = list(
                InspectWal.inspectSegment(writeSegment(1, exampleSegment())), "records");

        Map<String, Object> tombstone = records.get(1);
        assertEquals("beta", tombstone.get("key"));
        assertEquals(true, tombstone.get("tombstone"));
        assertTrue(tombstone.containsKey("valueLength"));
        assertNull(tombstone.get("valueLength"));

        Map<String, Object> emptyPut = records.get(2);
        assertEquals("gamma", emptyPut.get("key"));
        assertEquals(false, emptyPut.get("tombstone"));
        assertEquals(0, emptyPut.get("valueLength"));
    }

    @Test
    void neverPrintsValueBytes() throws IOException {
        byte[] secret = "do-not-print-this-value".getBytes(StandardCharsets.US_ASCII);
        Path file = writeSegment(1, segment(1, InternalRecord.put("k", 1, secret)));

        Map<String, Object> report = InspectWal.inspectSegment(file);

        assertFalse(report.toString().contains("do-not-print"));
        assertEquals(secret.length, list(report, "records").getFirst().get("valueLength"));
    }

    @Test
    void reportsTornTailWithFrameOffsetAndEof() throws IOException {
        Path file = writeSegment(1, Arrays.copyOf(exampleSegment(), 120));

        Map<String, Object> report = InspectWal.inspectSegment(file);

        assertEquals("TORN_TAIL", report.get("status"));
        assertEquals(94L, report.get("validLength"));
        Map<String, Object> torn = map(report, "tornTail");
        assertEquals(94L, torn.get("frameOffset"));
        assertEquals(120L, torn.get("eof"));
        assertEquals(26L, torn.get("strayBytes"));
        assertNull(report.get("corruption"));
        assertEquals(2, report.get("recordCount"));
    }

    @Test
    void reportsCorruptFrameWithOffsetAndCategory() throws IOException {
        byte[] bytes = exampleSegment();
        bytes[55] = 0x42;

        Map<String, Object> report = InspectWal.inspectSegment(writeSegment(1, bytes));

        assertEquals("CORRUPT", report.get("status"));
        Map<String, Object> corruption = map(report, "corruption");
        assertEquals(24L, corruption.get("offset"));
        assertEquals("CHECKSUM", corruption.get("category"));
        assertEquals(0, report.get("recordCount"));
        assertNull(report.get("tornTail"));
    }

    @Test
    void reportsInvalidHeaderChecksum() throws IOException {
        byte[] bytes = exampleSegment();
        bytes[20] ^= 0x01;

        Map<String, Object> report = InspectWal.inspectSegment(writeSegment(1, bytes));

        assertEquals("CORRUPT", report.get("status"));
        assertEquals("INVALID", report.get("headerCrcStatus"));
        assertEquals("LSMWAL01", report.get("format"));
        assertEquals("WAL_HEADER", map(report, "corruption").get("category"));
    }

    @Test
    void showsClaimedVersionOfUnsupportedFormat() throws IOException {
        byte[] bytes = exampleSegment();
        Bin.putU16(bytes, 8, 2);
        Bin.putCrc32c(bytes, 0, 20, 20);

        Map<String, Object> report = InspectWal.inspectSegment(writeSegment(1, bytes));

        assertEquals("CORRUPT", report.get("status"));
        assertEquals(2, report.get("formatVersion"));
        assertEquals("VALID", report.get("headerCrcStatus"));
        assertEquals("WAL_HEADER", map(report, "corruption").get("category"));
    }

    @Test
    void reportsTruncatedHeader() throws IOException {
        Map<String, Object> report = InspectWal.inspectSegment(
                writeSegment(1, Arrays.copyOf(exampleSegment(), 5)));

        assertEquals("CORRUPT", report.get("status"));
        assertEquals("TRUNCATED", report.get("headerCrcStatus"));
        assertEquals("4C 53 4D 57 41", report.get("headerMagic"));
        assertNull(report.get("format"));
        assertNull(report.get("formatVersion"));
        assertNull(report.get("headerSegmentId"));
    }

    // ===== Whole WAL =====

    @Test
    void ordersSegmentsByNumericIdAndSummarizesTheLog() throws IOException {
        writeSegment(10, segment(10, InternalRecord.put("c", 3, new byte[1])));
        writeSegment(9, segment(9, InternalRecord.put("b", 2, new byte[1])));
        writeSegment(2, segment(2, InternalRecord.put("a", 1, new byte[1])));

        Map<String, Object> report = InspectWal.inspectLog(dataDir);

        assertEquals("lsm/wal", report.get("walDirectory"));
        assertEquals("VALID", report.get("status"));
        assertEquals(3, report.get("segmentCount"));
        assertEquals(3, report.get("recordCount"));
        assertEquals(3L, report.get("lastSequence"));
        assertNull(report.get("tornSegmentId"));
        assertNull(report.get("corruptSegmentId"));
        assertEquals(List.of(2L, 9L, 10L),
                list(report, "segments").stream().map(s -> s.get("segmentId")).toList());
    }

    @Test
    void listsOtherAndTemporaryFilesSorted() throws IOException {
        writeSegment(1, segment(1));
        Files.write(dataDir.resolve("lsm/wal/zeta.txt"), new byte[1]);
        Files.write(dataDir.resolve("lsm/wal/alpha.txt"), new byte[1]);
        Path tmp = Files.createDirectories(dataDir.resolve("lsm/tmp"));
        Files.write(tmp.resolve(WalFileNames.tempFileName(7)), new byte[1]);
        Files.write(tmp.resolve(WalFileNames.tempFileName(3)), new byte[1]);
        Files.write(tmp.resolve("sst-00000000000000000001.sst.tmp"), new byte[1]);

        Map<String, Object> report = InspectWal.inspectLog(dataDir);

        assertEquals(List.of("alpha.txt", "zeta.txt"), report.get("otherFiles"));
        assertEquals(List.of(WalFileNames.tempFileName(3), WalFileNames.tempFileName(7)),
                report.get("temporarySegments"));
    }

    @Test
    void segmentsAfterCorruptionAreNotScanned() throws IOException {
        writeSegment(1, segment(1, InternalRecord.put("a", 1, new byte[1])));
        byte[] middle = segment(2, InternalRecord.put("b", 2, new byte[1]));
        middle[30] ^= 0x01;
        writeSegment(2, middle);
        writeSegment(3, segment(3, InternalRecord.put("c", 3, new byte[1])));

        Map<String, Object> report = InspectWal.inspectLog(dataDir);

        assertEquals("CORRUPT", report.get("status"));
        assertEquals(2L, report.get("corruptSegmentId"));
        List<Map<String, Object>> segments = list(report, "segments");
        assertEquals("VALID", segments.get(0).get("status"));
        assertEquals("CORRUPT", segments.get(1).get("status"));
        assertEquals("NOT_SCANNED", segments.get(2).get("status"));
        assertFalse(segments.get(2).containsKey("records"));
    }

    @Test
    void tornTailBeforeLaterRecordsIsReportedAsCorrupt() throws IOException {
        byte[] first = segment(1,
                InternalRecord.put("a", 1, new byte[1]),
                InternalRecord.put("b", 2, new byte[1]));
        writeSegment(1, Arrays.copyOf(first, first.length - 3));
        writeSegment(2, segment(2, InternalRecord.put("c", 3, new byte[1])));

        Map<String, Object> report = InspectWal.inspectLog(dataDir);

        assertEquals("CORRUPT", report.get("status"));
        assertEquals(1L, report.get("corruptSegmentId"));
        assertEquals("WAL_FRAME",
                map(list(report, "segments").get(0), "corruption").get("category"));
        assertEquals("NOT_SCANNED", list(report, "segments").get(1).get("status"));
    }

    @Test
    void emptyWalDirectoryIsValid() throws IOException {
        Files.createDirectories(dataDir.resolve("lsm/wal"));

        Map<String, Object> report = InspectWal.inspectLog(dataDir);

        assertEquals("VALID", report.get("status"));
        assertEquals(0, report.get("segmentCount"));
        assertNull(report.get("lastSequence"));
    }

    @Test
    void inspectionIsDeterministic() throws IOException {
        writeSegment(5, segment(5, InternalRecord.put("d", 4, new byte[3])));
        writeSegment(1, exampleSegment());

        assertEquals(InspectWal.inspectLog(dataDir), InspectWal.inspectLog(dataDir));
    }
}
