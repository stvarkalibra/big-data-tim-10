package rs.raf.dlsm.wal;

import java.util.OptionalLong;

/**
 * File names of WAL segments (FORMAT.md §2, §3.1): the final segment
 * {@code wal-<id>.log} in {@code lsm/wal/} and its temporary twin
 * {@code wal-<id>.log.tmp} in {@code lsm/tmp/}, where {@code <id>} is a
 * decimal number of exactly 20 digits, zero-padded on the left.
 */
public final class WalFileNames {

    public static final String PREFIX = "wal-";
    public static final String SEGMENT_SUFFIX = ".log";
    public static final String TEMP_SUFFIX = ".log.tmp";
    public static final int ID_DIGITS = 20;

    private WalFileNames() {
    }

    public static String segmentFileName(long segmentId) {
        return PREFIX + formatId(segmentId) + SEGMENT_SUFFIX;
    }

    public static String tempFileName(long segmentId) {
        return PREFIX + formatId(segmentId) + TEMP_SUFFIX;
    }

    /**
     * ID of a syntactically valid final segment name, or empty for any
     * other name. A syntactically valid ID may still be rejected later,
     * e.g. {@code 0} never matches a valid segment header.
     */
    public static OptionalLong parseSegmentId(String fileName) {
        return parseId(fileName, SEGMENT_SUFFIX);
    }

    /** Same as {@link #parseSegmentId} for temporary segment names. */
    public static OptionalLong parseTempId(String fileName) {
        return parseId(fileName, TEMP_SUFFIX);
    }

    private static String formatId(long segmentId) {
        if (segmentId <= 0) {
            throw new IllegalArgumentException(
                    "Segment ID must be positive: " + segmentId);
        }
        return "%020d".formatted(segmentId);
    }

    private static OptionalLong parseId(String fileName, String suffix) {
        if (fileName == null
                || fileName.length() != PREFIX.length() + ID_DIGITS + suffix.length()
                || !fileName.startsWith(PREFIX)
                || !fileName.endsWith(suffix)) {
            return OptionalLong.empty();
        }

        String digits = fileName.substring(
                PREFIX.length(), PREFIX.length() + ID_DIGITS);

        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return OptionalLong.empty();
            }
        }

        try {
            return OptionalLong.of(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            // 20 digits can exceed Long.MAX_VALUE; such a name carries no usable ID.
            return OptionalLong.empty();
        }
    }
}
