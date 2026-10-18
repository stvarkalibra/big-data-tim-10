package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import rs.raf.dlsm.common.Bin;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WalSegmentHeaderTest {

    @Test
    void encodesTwentyFourByteHeaderLayout() {
        byte[] header = WalCodec.encodeSegmentHeader(5);

        assertEquals(WalFormat.SEGMENT_HEADER_BYTES, header.length);
        assertArrayEquals("LSMWAL01".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOfRange(header, 0, 8));
        assertEquals(1, Bin.getU16(header, 8));
        assertEquals(0, Bin.getU16(header, 10));
        assertEquals(5, Bin.getI64(header, 12));
        assertEquals(Bin.crc32c(header, 0, 20), Bin.getU32(header, 20));
    }

    @Test
    void roundTripsSegmentIds() throws StorageException {
        for (long id : new long[]{1, 2, 9, 1_000_000, Long.MAX_VALUE}) {
            byte[] header = WalCodec.encodeSegmentHeader(id);

            assertEquals(id, WalCodec.decodeSegmentHeader(header));
            assertDoesNotThrow(() -> WalCodec.validateSegmentHeader(header, id));
        }
    }

    @Test
    void decodesHeaderAtStartOfLongerSegment() throws StorageException {
        byte[] segment = Arrays.copyOf(WalCodec.encodeSegmentHeader(3), 100);

        assertEquals(3, WalCodec.decodeSegmentHeader(segment));
    }

    @Test
    void rejectsIdThatDoesNotMatchFileName() {
        byte[] header = WalCodec.encodeSegmentHeader(4);

        assertHeaderError(() -> WalCodec.validateSegmentHeader(header, 5));
    }

    @Test
    void rejectsSegmentShorterThanHeader() {
        byte[] header = WalCodec.encodeSegmentHeader(1);

        assertHeaderError(() -> WalCodec.decodeSegmentHeader(null));
        assertHeaderError(() -> WalCodec.decodeSegmentHeader(new byte[0]));
        assertHeaderError(() -> WalCodec.decodeSegmentHeader(
                Arrays.copyOf(header, 23)));
    }

    // CP1-WAL-003 (d): one changed byte anywhere in the header is corruption.
    @Test
    void everySingleByteChangeIsHeaderCorruption() {
        byte[] original = WalCodec.encodeSegmentHeader(1);

        for (int offset = 0; offset < original.length; offset++) {
            for (int mask : new int[]{0x01, 0x80, 0xFF}) {
                byte[] corrupted = original.clone();
                corrupted[offset] ^= (byte) mask;

                assertHeaderError(
                        () -> WalCodec.validateSegmentHeader(corrupted, 1));
            }
        }
    }

    @Test
    void rejectsWrongMagicEvenWithValidChecksum() {
        byte[] header = WalCodec.encodeSegmentHeader(1);
        header[7] = '2';

        assertHeaderMessage(withFixedCrc(header), "magic");
    }

    @Test
    void rejectsUnsupportedVersionEvenWithValidChecksum() {
        byte[] header = WalCodec.encodeSegmentHeader(1);
        Bin.putU16(header, 8, 2);

        assertHeaderMessage(withFixedCrc(header), "version");
    }

    @Test
    void rejectsNonzeroReservedFieldEvenWithValidChecksum() {
        byte[] header = WalCodec.encodeSegmentHeader(1);
        Bin.putU16(header, 10, 1);

        assertHeaderMessage(withFixedCrc(header), "reserved");
    }

    @Test
    void rejectsNonpositiveSegmentIdEvenWithValidChecksum() {
        for (long id : new long[]{0, -1, Long.MIN_VALUE}) {
            byte[] header = WalCodec.encodeSegmentHeader(1);
            Bin.putI64(header, 12, id);

            assertHeaderMessage(withFixedCrc(header), "segment ID");
        }
    }

    @Test
    void rejectsNonpositiveIdsAsProgrammingErrors() {
        byte[] header = WalCodec.encodeSegmentHeader(1);

        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.encodeSegmentHeader(0));
        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.encodeSegmentHeader(-1));
        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.validateSegmentHeader(header, 0));
    }

    private static byte[] withFixedCrc(byte[] header) {
        Bin.putCrc32c(header, 0, 20, 20);
        return header;
    }

    private static void assertHeaderMessage(byte[] header, String fragment) {
        StorageException e = assertHeaderError(
                () -> WalCodec.decodeSegmentHeader(header));

        assertTrue(e.getMessage().contains(fragment),
                () -> "message '" + e.getMessage() + "' lacks '" + fragment + "'");
    }

    private static StorageException assertHeaderError(Executable call) {
        StorageException e = assertThrows(StorageException.class, call);

        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(WalFormat.CATEGORY_HEADER, e.category());

        return e;
    }
}
