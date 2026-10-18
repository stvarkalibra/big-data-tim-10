package rs.raf.dlsm.inspect;

import rs.raf.dlsm.common.Bin;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.wal.WalFormat;
import rs.raf.dlsm.wal.WalReader;
import rs.raf.dlsm.wal.WalScanResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline view of WAL segments for {@code dbctl inspect wal} (CP1 §25).
 *
 * <p>Read-only: reuses the production decoders through {@link WalReader}
 * and never truncates, repairs or deletes anything. A torn tail is
 * reported with its offsets but left in place. Value bytes are never
 * printed, only their length; a tombstone has no value length at all,
 * while an empty PUT has length {@code 0}.
 */
public final class InspectWal {

    private InspectWal() {
    }

    /** Report for one segment file named {@code wal-<id>.log}. */
    public static Map<String, Object> inspectSegment(Path file) throws IOException {
        return describe(file, WalReader.scanSegment(file));
    }

    /**
     * Report for a segment that was already scanned, e.g. as part of the
     * whole log, where sequence order across segments is also checked.
     */
    static Map<String, Object> describe(Path file, WalScanResult scan) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("file", file.getFileName().toString());
        result.put("segmentId", scan.segmentId());
        putHeader(result, file);
        result.put("sizeBytes", scan.fileSize());
        result.put("status", scan.status().name());
        result.put("validLength", scan.validLength());

        result.put("tornTail", scan.status() == WalScanResult.Status.TORN_TAIL
                ? tornTail(scan)
                : null);
        result.put("corruption", scan.corruption() == null
                ? null
                : corruption(scan.corruption()));

        List<WalScanResult.Entry> entries = scan.entries();
        result.put("recordCount", entries.size());
        result.put("minSequence", entries.isEmpty()
                ? null
                : entries.getFirst().record().sequence());
        result.put("maxSequence", entries.isEmpty()
                ? null
                : entries.getLast().record().sequence());

        List<Map<String, Object>> records = new ArrayList<>();
        for (WalScanResult.Entry entry : entries) {
            records.add(record(entry));
        }
        result.put("records", records);

        return result;
    }

    /**
     * Raw header fields as found on disk, so even a damaged header shows
     * which format and version it claims to be.
     */
    private static void putHeader(Map<String, Object> result, Path file) throws IOException {
        byte[] header;
        try (InputStream in = Files.newInputStream(file)) {
            header = in.readNBytes(WalFormat.SEGMENT_HEADER_BYTES);
        }

        HexFormat hex = HexFormat.ofDelimiter(" ").withUpperCase();
        int magicBytes = Math.min(header.length, 8);
        boolean complete = header.length == WalFormat.SEGMENT_HEADER_BYTES;

        result.put("headerMagic", hex.formatHex(header, 0, magicBytes));
        result.put("format", magicBytes == 8
                && Bin.getI64(header, WalFormat.HEADER_MAGIC_OFFSET) == WalFormat.MAGIC
                ? "LSMWAL01"
                : null);
        result.put("formatVersion", header.length >= WalFormat.HEADER_VERSION_OFFSET + 2
                ? Bin.getU16(header, WalFormat.HEADER_VERSION_OFFSET)
                : null);
        result.put("headerSegmentId", complete
                ? Bin.getI64(header, WalFormat.HEADER_SEGMENT_ID_OFFSET)
                : null);
        result.put("headerCrcStatus", !complete
                ? "TRUNCATED"
                : Bin.getU32(header, WalFormat.HEADER_CRC_OFFSET)
                        == Bin.crc32c(header, 0, WalFormat.HEADER_CRC_OFFSET)
                        ? "VALID"
                        : "INVALID");
    }

    private static Map<String, Object> tornTail(WalScanResult scan) {
        Map<String, Object> torn = new LinkedHashMap<>();
        torn.put("frameOffset", scan.tornFrameOffset());
        torn.put("eof", scan.fileSize());
        torn.put("strayBytes", scan.fileSize() - scan.tornFrameOffset());
        return torn;
    }

    private static Map<String, Object> corruption(WalScanResult.Corruption corruption) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offset", corruption.offset());
        result.put("category", corruption.category());
        result.put("message", corruption.message());
        return result;
    }

    private static Map<String, Object> record(WalScanResult.Entry entry) {
        InternalRecord record = entry.record();
        Map<String, Object> item = new LinkedHashMap<>();

        item.put("offset", entry.offset());
        item.put("length", entry.length());
        item.put("sequence", record.sequence());
        item.put("kind", record.kind().name());
        item.put("key", record.key());
        item.put("tombstone", record.isTombstone());
        item.put("valueLength", record.isTombstone() ? null : record.valueLength());
        item.put("crcStatus", "VALID");

        return item;
    }
}
