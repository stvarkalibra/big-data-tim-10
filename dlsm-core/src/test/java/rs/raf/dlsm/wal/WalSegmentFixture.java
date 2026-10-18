package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.InternalRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds WAL segment bytes for reader tests and writes them under their
 * proper file name. Frames come from {@link WalCodec}, whose output is
 * pinned to the FORMAT.md hex example by {@code WalFormatExampleTest}.
 */
final class WalSegmentFixture {

    private final long segmentId;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final List<Integer> frameOffsets = new ArrayList<>();
    private final List<InternalRecord> records = new ArrayList<>();

    private WalSegmentFixture(long segmentId) {
        this.segmentId = segmentId;
        bytes.writeBytes(WalCodec.encodeSegmentHeader(segmentId));
    }

    static WalSegmentFixture segment(long segmentId) {
        return new WalSegmentFixture(segmentId);
    }

    WalSegmentFixture put(String key, long sequence, String value) {
        return put(key, sequence, value.getBytes(StandardCharsets.US_ASCII));
    }

    WalSegmentFixture put(String key, long sequence, byte[] value) {
        return record(InternalRecord.put(key, sequence, value));
    }

    WalSegmentFixture delete(String key, long sequence) {
        return record(InternalRecord.delete(key, sequence));
    }

    WalSegmentFixture record(InternalRecord record) {
        frameOffsets.add(bytes.size());
        records.add(record);
        bytes.writeBytes(WalCodec.encodeFrame(record));
        return this;
    }

    long segmentId() {
        return segmentId;
    }

    /** A fresh copy of the full segment, safe to truncate or patch. */
    byte[] bytes() {
        return bytes.toByteArray();
    }

    int size() {
        return bytes.size();
    }

    /** File offset where frame {@code index} (0-based) starts. */
    int frameOffset(int index) {
        return frameOffsets.get(index);
    }

    /** Encoded length of frame {@code index}. */
    int frameLength(int index) {
        int end = index + 1 < frameOffsets.size() ? frameOffsets.get(index + 1) : size();
        return end - frameOffsets.get(index);
    }

    List<InternalRecord> records() {
        return List.copyOf(records);
    }

    /** Writes the segment as {@code dir/wal-<id>.log}. */
    Path writeTo(Path dir) throws IOException {
        return write(dir, segmentId, bytes());
    }

    /** Writes arbitrary bytes, e.g. a truncated or patched copy, as segment {@code id}. */
    static Path write(Path dir, long segmentId, byte[] contents) throws IOException {
        return Files.write(dir.resolve(WalFileNames.segmentFileName(segmentId)), contents);
    }
}
