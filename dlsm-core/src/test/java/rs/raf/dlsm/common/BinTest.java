
package rs.raf.dlsm.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BinTest {

    @Test
    void u8RoundTripAtBounds() {
        byte[] data = new byte[1];

        for (int value : new int[]{0, 1, 0x7F, 0x80, Bin.U8_MAX}) {
            Bin.putU8(data, 0, value);
            assertEquals(value, Bin.getU8(data, 0));
        }
    }

    @Test
    void u16RoundTripAtBounds() {
        byte[] data = new byte[2];

        for (int value : new int[]{0, 1, 0x7FFF, 0x8000, Bin.U16_MAX}) {
            Bin.putU16(data, 0, value);
            assertEquals(value, Bin.getU16(data, 0));
        }
    }

    @Test
    void u32RoundTripAtBounds() {
        byte[] data = new byte[4];

        for (long value : new long[]{
                0, 1, 0x7FFF_FFFFL, 0x8000_0000L, Bin.U32_MAX}) {
            Bin.putU32(data, 0, value);
            assertEquals(value, Bin.getU32(data, 0));
        }
    }

    @Test
    void i64RoundTripAtBounds() {
        byte[] data = new byte[8];

        for (long value : new long[]{
                0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE}) {
            Bin.putI64(data, 0, value);
            assertEquals(value, Bin.getI64(data, 0));
        }
    }

    @Test
    void writesMostSignificantByteFirst() {
        byte[] u16 = new byte[2];
        Bin.putU16(u16, 0, 0x0102);
        assertArrayEquals(new byte[]{0x01, 0x02}, u16);

        byte[] u32 = new byte[4];
        Bin.putU32(u32, 0, 0x01020304L);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, u32);

        byte[] i64 = new byte[8];
        Bin.putI64(i64, 0, 0x0102030405060708L);
        assertArrayEquals(
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, i64);
    }

    @Test
    void highBitPatternsReadAsUnsigned() {
        byte[] data = {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        assertEquals(0xFF, Bin.getU8(data, 0));
        assertEquals(0xFFFF, Bin.getU16(data, 0));
        assertEquals(0xFFFF_FFFFL, Bin.getU32(data, 0));
    }

    @Test
    void writesOnlyAtGivenOffset() {
        byte[] data = new byte[12];

        Bin.putU32(data, 4, Bin.U32_MAX);

        for (int i = 0; i < data.length; i++) {
            byte expected = (i >= 4 && i < 8) ? (byte) 0xFF : 0;
            assertEquals(expected, data[i], "byte " + i);
        }

        assertEquals(Bin.U32_MAX, Bin.getU32(data, 4));
    }

    @Test
    void rejectsValuesOutsideUnsignedRange() {
        byte[] data = new byte[8];

        assertThrows(IllegalArgumentException.class,
                () -> Bin.putU8(data, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Bin.putU8(data, 0, Bin.U8_MAX + 1));

        assertThrows(IllegalArgumentException.class,
                () -> Bin.putU16(data, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Bin.putU16(data, 0, Bin.U16_MAX + 1));

        assertThrows(IllegalArgumentException.class,
                () -> Bin.putU32(data, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Bin.putU32(data, 0, Bin.U32_MAX + 1));
    }

    @Test
    void rejectedValueLeavesBufferUntouched() {
        byte[] data = new byte[4];

        assertThrows(IllegalArgumentException.class,
                () -> Bin.putU32(data, 0, -1));

        assertArrayEquals(new byte[4], data);
    }

    @Test
    void rejectsAccessOutsideArray() {
        byte[] data = new byte[7];

        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.getU8(data, 7));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.getU16(data, 6));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.getU32(data, 4));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.getI64(data, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.putU16(data, -1, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.putI64(data, 0, 0));
    }

    @Test
    void rejectsNullArray() {
        assertThrows(NullPointerException.class,
                () -> Bin.getU32(null, 0));
        assertThrows(NullPointerException.class,
                () -> Bin.putU32(null, 0, 0));
        assertThrows(NullPointerException.class,
                () -> Bin.crc32c(null, 0, 0));
    }

    @Test
    void crc32cMatchesStandardCheckValue() {
        byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);

        assertEquals(0xE306_9283L, Bin.crc32c(data, 0, data.length));
    }

    // RFC 3720 (iSCSI), appendix B.4 CRC32C test vectors.
    @Test
    void crc32cMatchesRfc3720Vectors() {
        byte[] zeros = new byte[32];

        byte[] ones = new byte[32];
        Arrays.fill(ones, (byte) 0xFF);

        byte[] ascending = new byte[32];
        byte[] descending = new byte[32];

        for (int i = 0; i < 32; i++) {
            ascending[i] = (byte) i;
            descending[i] = (byte) (31 - i);
        }

        assertEquals(0x8A91_36AAL, Bin.crc32c(zeros, 0, 32));
        assertEquals(0x62A8_AB43L, Bin.crc32c(ones, 0, 32));
        assertEquals(0x46DD_794EL, Bin.crc32c(ascending, 0, 32));
        assertEquals(0x113F_DB5CL, Bin.crc32c(descending, 0, 32));
    }

    @Test
    void crc32cOfEmptyRangeIsZero() {
        assertEquals(0, Bin.crc32c(new byte[4], 2, 0));
    }

    @Test
    void crc32cCoversOnlyRequestedRange() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);

        byte[] padded = new byte[check.length + 6];
        Arrays.fill(padded, (byte) 0x5A);
        System.arraycopy(check, 0, padded, 3, check.length);

        assertEquals(0xE306_9283L,
                Bin.crc32c(padded, 3, check.length));

        padded[0] ^= 0x01;
        padded[padded.length - 1] ^= 0x01;

        assertEquals(0xE306_9283L,
                Bin.crc32c(padded, 3, check.length));
    }

    @Test
    void crc32cDetectsSingleBitFlip() {
        byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
        long original = Bin.crc32c(data, 0, data.length);

        for (int bit = 0; bit < data.length * 8; bit++) {
            data[bit / 8] ^= (byte) (1 << (bit % 8));

            assertNotEquals(original,
                    Bin.crc32c(data, 0, data.length), "bit " + bit);

            data[bit / 8] ^= (byte) (1 << (bit % 8));
        }
    }

    @Test
    void putCrc32cStoresBigEndianChecksumAtOffset() {
        byte[] data = new byte[13];
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(check, 0, data, 0, check.length);

        Bin.putCrc32c(data, 0, check.length, 9);

        assertArrayEquals(
                new byte[]{(byte) 0xE3, 0x06, (byte) 0x92, (byte) 0x83},
                Arrays.copyOfRange(data, 9, 13));
        assertEquals(Bin.crc32c(data, 0, 9), Bin.getU32(data, 9));
    }

    @Test
    void crc32cRejectsRangeOutsideArray() {
        byte[] data = new byte[8];

        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.crc32c(data, 0, 9));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.crc32c(data, 4, 5));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.crc32c(data, -1, 2));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.crc32c(data, 0, -1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Bin.putCrc32c(data, 0, 4, 5));
    }

    @Test
    void checkLengthAcceptsBoundsInclusive() {
        assertEquals(1, Bin.checkLength(1, 1, 128));
        assertEquals(64, Bin.checkLength(64, 1, 128));
        assertEquals(128, Bin.checkLength(128, 1, 128));
        assertEquals(0, Bin.checkLength(0, 0, 262_144));
        assertEquals(262_144, Bin.checkLength(262_144, 0, 262_144));
    }

    @Test
    void checkLengthRejectsValuesOutsideBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(0, 1, 128));
        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(129, 1, 128));
        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(-1, 0, 128));
    }

    @Test
    void checkLengthRejectsCorruptedU32BeforeAllocation() {
        byte[] corrupted = {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        long declared = Bin.getU32(corrupted, 0);

        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(declared, 31, 262_302));
    }

    @Test
    void checkLengthRejectsValuesBeyondIntRange() {
        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(
                        (long) Integer.MAX_VALUE + 1, 0, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(Long.MAX_VALUE, 0, 128));
        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(Long.MIN_VALUE, 0, 128));
    }

    @Test
    void checkLengthRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(5, -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> Bin.checkLength(5, 10, 1));
    }

    @Test
    void fitsWithinAcceptsRegionsInsideLimit() {
        assertTrue(Bin.fitsWithin(0, 0, 0));
        assertTrue(Bin.fitsWithin(0, 10, 10));
        assertTrue(Bin.fitsWithin(4, 6, 10));
        assertTrue(Bin.fitsWithin(10, 0, 10));
    }

    @Test
    void fitsWithinRejectsRegionsPastLimit() {
        assertFalse(Bin.fitsWithin(0, 11, 10));
        assertFalse(Bin.fitsWithin(5, 6, 10));
        assertFalse(Bin.fitsWithin(11, 0, 10));
    }

    @Test
    void fitsWithinRejectsNegativeInputs() {
        assertFalse(Bin.fitsWithin(-1, 1, 10));
        assertFalse(Bin.fitsWithin(0, -1, 10));
        assertFalse(Bin.fitsWithin(0, 0, -1));
    }

    @Test
    void fitsWithinDoesNotOverflow() {
        assertFalse(Bin.fitsWithin(Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertFalse(Bin.fitsWithin(1, Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(Bin.fitsWithin(Long.MAX_VALUE, Long.MAX_VALUE, 100));
        assertTrue(Bin.fitsWithin(0, Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
