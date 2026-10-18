package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;

/**
 * Byte layout of WAL segments: the recommended baseline of CP1 §16.3.1,
 * documented in FORMAT.md §3. Offsets are relative to the start of the
 * segment header or of one record frame.
 */
public final class WalFormat {

    // ===== Segment header (FORMAT.md §3.2) =====

    /** ASCII {@code LSMWAL01}. */
    public static final long MAGIC = 0x4C53_4D57_414C_3031L;
    public static final int FILE_FORMAT_VERSION = 1;
    public static final int SEGMENT_HEADER_BYTES = 24;

    public static final int HEADER_MAGIC_OFFSET = 0;
    public static final int HEADER_VERSION_OFFSET = 8;
    public static final int HEADER_RESERVED_OFFSET = 10;
    public static final int HEADER_SEGMENT_ID_OFFSET = 12;
    /** CRC32C over bytes {@code 0..19}. */
    public static final int HEADER_CRC_OFFSET = 20;

    // ===== Record frame (FORMAT.md §3.3) =====

    public static final int RECORD_VERSION = 1;

    public static final int FRAME_LENGTH_OFFSET = 0;
    public static final int FRAME_LENGTH_COMPLEMENT_OFFSET = 4;
    public static final int FRAME_VERSION_OFFSET = 8;
    public static final int FRAME_KIND_OFFSET = 10;
    public static final int FRAME_RESERVED_OFFSET = 11;
    public static final int FRAME_SEQUENCE_OFFSET = 12;
    public static final int FRAME_KEY_LENGTH_OFFSET = 20;
    public static final int FRAME_VALUE_LENGTH_OFFSET = 22;
    public static final int FRAME_KEY_OFFSET = 26;

    /** Length and its complement: checked before the frame is trusted. */
    public static final int FRAME_GUARD_BYTES = 8;
    /** First byte covered by the record CRC (the record version). */
    public static final int FRAME_CRC_START = FRAME_VERSION_OFFSET;
    public static final int FRAME_CRC_BYTES = 4;
    /** {@code totalFrameLength = FRAME_OVERHEAD_BYTES + k + v}. */
    public static final int FRAME_OVERHEAD_BYTES =
            FRAME_KEY_OFFSET + FRAME_CRC_BYTES;

    public static final int MIN_FRAME_BYTES = FRAME_OVERHEAD_BYTES + 1;
    public static final int MAX_FRAME_BYTES = FRAME_OVERHEAD_BYTES
            + Keys.MAX_LENGTH + InternalRecord.MAX_VALUE_BYTES;

    // ===== Diagnostic categories (FORMAT.md §3.4) =====

    public static final String CATEGORY_HEADER = "WAL_HEADER";
    public static final String CATEGORY_LENGTH_GUARD = "WAL_LENGTH_GUARD";
    public static final String CATEGORY_FRAME = "WAL_FRAME";
    public static final String CATEGORY_CHECKSUM = "CHECKSUM";
    public static final String CATEGORY_SEQUENCE_ORDER = "WAL_SEQUENCE_ORDER";

    private WalFormat() {
    }

    /** Exact frame length for a key of {@code k} and a value of {@code v} bytes. */
    public static int frameLength(int keyLength, int valueLength) {
        return FRAME_OVERHEAD_BYTES + keyLength + valueLength;
    }
}
