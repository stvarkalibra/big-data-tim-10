
package rs.raf.dlsm.common;

import org.junit.jupiter.api.Test;

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
    }
}
