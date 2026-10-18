
package rs.raf.dlsm.common;

import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Fixed-width big-endian integer encoding and CRC32C checksums shared by
 * on-disk formats (CP1 §15: documented byte order, fixed-width
 * nonnegative integers, checksums over precisely documented bytes).
 *
 * <p>Unsigned values are returned in a wider Java type so callers never
 * see a negative number for a large stored bit pattern.
 */
public final class Bin {

    public static final int U8_MAX = 0xFF;
    public static final int U16_MAX = 0xFFFF;
    public static final long U32_MAX = 0xFFFF_FFFFL;

    private Bin() {
    }

    public static void putU8(byte[] dst, int offset, int value) {
        checkRange(dst, offset, 1);

        if (value < 0 || value > U8_MAX) {
            throw new IllegalArgumentException(
                    "Value out of u8 range: " + value);
        }

        dst[offset] = (byte) value;
    }

    public static int getU8(byte[] src, int offset) {
        checkRange(src, offset, 1);

        return src[offset] & 0xFF;
    }

    public static void putU16(byte[] dst, int offset, int value) {
        checkRange(dst, offset, 2);

        if (value < 0 || value > U16_MAX) {
            throw new IllegalArgumentException(
                    "Value out of u16 range: " + value);
        }

        dst[offset] = (byte) (value >>> 8);
        dst[offset + 1] = (byte) value;
    }

    public static int getU16(byte[] src, int offset) {
        checkRange(src, offset, 2);

        return ((src[offset] & 0xFF) << 8)
                | (src[offset + 1] & 0xFF);
    }

    public static void putU32(byte[] dst, int offset, long value) {
        checkRange(dst, offset, 4);

        if (value < 0 || value > U32_MAX) {
            throw new IllegalArgumentException(
                    "Value out of u32 range: " + value);
        }

        dst[offset] = (byte) (value >>> 24);
        dst[offset + 1] = (byte) (value >>> 16);
        dst[offset + 2] = (byte) (value >>> 8);
        dst[offset + 3] = (byte) value;
    }

    public static long getU32(byte[] src, int offset) {
        checkRange(src, offset, 4);

        return ((long) (src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }

    /**
     * Raw 64-bit pattern. Sequences and IDs are positive signed longs
     * (CP1 §15); range checks belong to the format that stores them.
     */
    public static void putI64(byte[] dst, int offset, long value) {
        checkRange(dst, offset, 8);

        for (int i = 0; i < 8; i++) {
            dst[offset + i] = (byte) (value >>> (56 - 8 * i));
        }
    }

    public static long getI64(byte[] src, int offset) {
        checkRange(src, offset, 8);

        long result = 0;

        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (src[offset + i] & 0xFF);
        }

        return result;
    }

    /**
     * CRC32C over exactly {@code data[offset .. offset + length - 1]},
     * returned as an unsigned u32 value comparable with {@link #getU32}.
     */
    public static long crc32c(byte[] data, int offset, int length) {
        checkRange(data, offset, length);

        CRC32C crc = new CRC32C();
        crc.update(data, offset, length);

        return crc.getValue();
    }

    /**
     * Computes CRC32C over {@code data[from .. from + length - 1]} and
     * stores it as u32 at {@code crcOffset}.
     */
    public static void putCrc32c(
            byte[] data, int from, int length, int crcOffset) {
        putU32(data, crcOffset, crc32c(data, from, length));
    }

    /**
     * Accepts a length decoded from untrusted bytes only when it lies in
     * {@code [min, max]}, so it can be used for allocation (CP1 §15:
     * invalid lengths are rejected before allocating memory).
     *
     * @return the declared length as an {@code int}
     * @throws IllegalArgumentException when the length is outside bounds
     */
    public static int checkLength(long declared, int min, int max) {
        if (min < 0 || min > max) {
            throw new IllegalArgumentException(
                    "Invalid length bounds [" + min + ", " + max + "]");
        }

        if (declared < min || declared > max) {
            throw new IllegalArgumentException(
                    "Declared length " + declared
                            + " outside [" + min + ", " + max + "]");
        }

        return (int) declared;
    }

    /**
     * True when {@code [offset, offset + length)} lies inside
     * {@code [0, limit)}, e.g. a declared region inside the physical file
     * (CP1 §15: never trust offsets or lengths beyond the real file).
     * Overflow-safe for any inputs; negative inputs never fit.
     */
    public static boolean fitsWithin(long offset, long length, long limit) {
        return offset >= 0 && length >= 0 && limit >= 0
                && offset <= limit - length;
    }

    private static void checkRange(byte[] data, int offset, int width) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, width, data.length);
    }
}
