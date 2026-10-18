package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.Bin;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static rs.raf.dlsm.wal.WalFormat.*;

/**
 * Encoder and decoder for the WAL byte layout of FORMAT.md §3.
 *
 * <p>Pure functions over byte arrays: no file I/O, no failpoints. Every
 * rejection is a {@link StorageException} with code {@code STORAGE_IO}
 * and one of the WAL categories from {@link WalFormat}.
 */
public final class WalCodec {

    private WalCodec() {
    }

    // ===== Segment header (FORMAT.md §3.2) =====

    public static byte[] encodeSegmentHeader(long segmentId) {
        if (segmentId <= 0) {
            throw new IllegalArgumentException(
                    "Segment ID must be positive: " + segmentId);
        }

        byte[] header = new byte[SEGMENT_HEADER_BYTES];

        Bin.putI64(header, HEADER_MAGIC_OFFSET, MAGIC);
        Bin.putU16(header, HEADER_VERSION_OFFSET, FILE_FORMAT_VERSION);
        Bin.putU16(header, HEADER_RESERVED_OFFSET, 0);
        Bin.putI64(header, HEADER_SEGMENT_ID_OFFSET, segmentId);
        Bin.putCrc32c(header, 0, HEADER_CRC_OFFSET, HEADER_CRC_OFFSET);

        return header;
    }

    /**
     * Validates the first {@value WalFormat#SEGMENT_HEADER_BYTES} bytes of
     * a segment in the order of FORMAT.md §3.2 and returns its segment ID.
     * Does not compare the ID with a file name; see
     * {@link #validateSegmentHeader}.
     *
     * @param data the start of the segment file; may be shorter than a header
     */
    public static long decodeSegmentHeader(byte[] data) throws StorageException {
        if (data == null || data.length < SEGMENT_HEADER_BYTES) {
            // Never a torn tail: a final name implies a forced full header.
            throw headerError("Segment shorter than its "
                    + SEGMENT_HEADER_BYTES + "-byte header");
        }

        long storedCrc = Bin.getU32(data, HEADER_CRC_OFFSET);
        if (storedCrc != Bin.crc32c(data, 0, HEADER_CRC_OFFSET)) {
            throw headerError("Segment header checksum mismatch");
        }

        if (Bin.getI64(data, HEADER_MAGIC_OFFSET) != MAGIC) {
            throw headerError("Invalid segment magic");
        }

        int version = Bin.getU16(data, HEADER_VERSION_OFFSET);
        if (version != FILE_FORMAT_VERSION) {
            throw headerError("Unsupported segment format version " + version);
        }

        if (Bin.getU16(data, HEADER_RESERVED_OFFSET) != 0) {
            throw headerError("Nonzero reserved header field");
        }

        long segmentId = Bin.getI64(data, HEADER_SEGMENT_ID_OFFSET);
        if (segmentId <= 0) {
            throw headerError("Nonpositive segment ID " + segmentId);
        }

        return segmentId;
    }

    /**
     * Full header check for a segment whose file name carries
     * {@code expectedSegmentId}; the IDs must match (CP1 §16.4).
     */
    public static void validateSegmentHeader(
            byte[] data, long expectedSegmentId) throws StorageException {

        if (expectedSegmentId <= 0) {
            throw new IllegalArgumentException(
                    "Expected segment ID must be positive: " + expectedSegmentId);
        }

        long segmentId = decodeSegmentHeader(data);

        if (segmentId != expectedSegmentId) {
            throw headerError("Header segment ID " + segmentId
                    + " does not match file name ID " + expectedSegmentId);
        }
    }

    // ===== Record frame (FORMAT.md §3.3) =====

    /**
     * Encodes one mutation as a complete frame of {@code 30 + k + v} bytes.
     * The record itself already guarantees a valid key, a positive sequence
     * and a bounded value, so every frame produced here passes decoding.
     */
    public static byte[] encodeFrame(InternalRecord record) {
        Objects.requireNonNull(record, "record");

        byte[] key = record.key().getBytes(StandardCharsets.US_ASCII);
        byte[] value = record.isTombstone() ? new byte[0] : record.value();

        int total = frameLength(key.length, value.length);
        byte[] frame = new byte[total];

        Bin.putU32(frame, FRAME_LENGTH_OFFSET, total);
        Bin.putU32(frame, FRAME_LENGTH_COMPLEMENT_OFFSET,
                total ^ Bin.U32_MAX);
        Bin.putU16(frame, FRAME_VERSION_OFFSET, RECORD_VERSION);
        Bin.putU8(frame, FRAME_KIND_OFFSET, record.kind().code());
        Bin.putU8(frame, FRAME_RESERVED_OFFSET, 0);
        Bin.putI64(frame, FRAME_SEQUENCE_OFFSET, record.sequence());
        Bin.putU16(frame, FRAME_KEY_LENGTH_OFFSET, key.length);
        Bin.putU32(frame, FRAME_VALUE_LENGTH_OFFSET, value.length);

        System.arraycopy(key, 0, frame, FRAME_KEY_OFFSET, key.length);
        System.arraycopy(value, 0, frame,
                FRAME_KEY_OFFSET + key.length, value.length);

        // CRC covers bytes 8..(25 + k + v): version through the last value byte.
        int crcOffset = total - FRAME_CRC_BYTES;
        Bin.putCrc32c(frame, FRAME_CRC_START,
                crcOffset - FRAME_CRC_START, crcOffset);

        return frame;
    }

    private static StorageException headerError(String message) {
        return new StorageException(
                ErrorCode.STORAGE_IO, CATEGORY_HEADER, message);
    }
}
