package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 129-byte segment of FORMAT.md §3.6. The expected bytes are copied
 * from the document, not computed by production code, so this test pins
 * the codec to the shared contract (plan §5.2).
 */
class WalFormatExampleTest {

    private static final byte[] EXAMPLE_SEGMENT = HexFormat.ofDelimiter(" ").parseHex(
            "4C 53 4D 57 41 4C 30 31 00 01 00 00 00 00 00 00 "
                    + "00 00 00 01 E8 56 78 A9 00 00 00 24 FF FF FF DB "
                    + "00 01 01 00 00 00 00 00 00 00 00 01 00 05 00 00 "
                    + "00 01 61 6C 70 68 61 41 F2 20 F3 D8 00 00 00 22 "
                    + "FF FF FF DD 00 01 02 00 00 00 00 00 00 00 00 02 "
                    + "00 04 00 00 00 00 62 65 74 61 18 19 3F 8C 00 00 "
                    + "00 23 FF FF FF DC 00 01 01 00 00 00 00 00 00 00 "
                    + "00 03 00 05 00 00 00 00 67 61 6D 6D 61 7E 1E 14 "
                    + "BF");

    private static final InternalRecord PUT_ALPHA = InternalRecord.put(
            "alpha", 1, "A".getBytes(StandardCharsets.US_ASCII));
    private static final InternalRecord DELETE_BETA = InternalRecord.delete("beta", 2);
    private static final InternalRecord PUT_GAMMA_EMPTY = InternalRecord.put(
            "gamma", 3, new byte[0]);

    @Test
    void exampleHasDocumentedSize() {
        assertEquals(129, EXAMPLE_SEGMENT.length);
    }

    @Test
    void encoderProducesDocumentedBytes() {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();

        segment.writeBytes(WalCodec.encodeSegmentHeader(1));
        segment.writeBytes(WalCodec.encodeFrame(PUT_ALPHA));
        segment.writeBytes(WalCodec.encodeFrame(DELETE_BETA));
        segment.writeBytes(WalCodec.encodeFrame(PUT_GAMMA_EMPTY));

        assertArrayEquals(EXAMPLE_SEGMENT, segment.toByteArray());
    }

    @Test
    void framesStartAtDocumentedOffsets() {
        assertEquals(24, WalFormat.SEGMENT_HEADER_BYTES);
        assertEquals(36, WalCodec.encodeFrame(PUT_ALPHA).length);
        assertEquals(34, WalCodec.encodeFrame(DELETE_BETA).length);
        assertEquals(35, WalCodec.encodeFrame(PUT_GAMMA_EMPTY).length);
    }

    @Test
    void decoderReadsDocumentedHeader() throws StorageException {
        WalCodec.validateSegmentHeader(EXAMPLE_SEGMENT, 1);
    }

    @Test
    void decoderReadsDocumentedFrames() throws StorageException {
        assertEquals(36, WalCodec.checkFrameGuard(EXAMPLE_SEGMENT, 24));
        assertEquals(34, WalCodec.checkFrameGuard(EXAMPLE_SEGMENT, 60));
        assertEquals(35, WalCodec.checkFrameGuard(EXAMPLE_SEGMENT, 94));

        assertEquals(PUT_ALPHA, WalCodec.decodeFrame(frameAt(EXAMPLE_SEGMENT, 24, 36)));
        assertEquals(DELETE_BETA, WalCodec.decodeFrame(frameAt(EXAMPLE_SEGMENT, 60, 34)));
        assertEquals(PUT_GAMMA_EMPTY, WalCodec.decodeFrame(frameAt(EXAMPLE_SEGMENT, 94, 35)));
    }

    @Test
    void emptyPutAndTombstoneDifferOnlyInKind() throws StorageException {
        InternalRecord delete = WalCodec.decodeFrame(frameAt(EXAMPLE_SEGMENT, 60, 34));
        InternalRecord emptyPut = WalCodec.decodeFrame(frameAt(EXAMPLE_SEGMENT, 94, 35));

        assertTrue(delete.isTombstone());
        assertNull(delete.value());

        assertFalse(emptyPut.isTombstone());
        assertArrayEquals(new byte[0], emptyPut.value());
    }

    // Rows of the FORMAT.md §3.6 table that the codec alone can decide.

    @Test
    void changedLengthByteIsLengthGuardCorruption() {
        byte[] copy = EXAMPLE_SEGMENT.clone();
        assertEquals(0x23, copy[97]);
        copy[97] = 0x22;

        assertCategory(WalFormat.CATEGORY_LENGTH_GUARD,
                () -> WalCodec.checkFrameGuard(copy, 94));
    }

    @Test
    void changedValueByteIsChecksumCorruption() {
        byte[] copy = EXAMPLE_SEGMENT.clone();
        assertEquals(0x41, copy[55]);
        copy[55] = 0x42;

        assertCategory(WalFormat.CATEGORY_CHECKSUM,
                () -> WalCodec.decodeFrame(frameAt(copy, 24, 36)));
    }

    @Test
    void changedHeaderCrcIsHeaderCorruption() {
        byte[] copy = EXAMPLE_SEGMENT.clone();
        copy[20] ^= 0x01;

        assertCategory(WalFormat.CATEGORY_HEADER,
                () -> WalCodec.validateSegmentHeader(copy, 1));
    }

    private static byte[] frameAt(byte[] segment, int offset, int length) {
        return Arrays.copyOfRange(segment, offset, offset + length);
    }

    private static void assertCategory(String category, Executable call) {
        StorageException e = assertThrows(StorageException.class, call);

        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(category, e.category());
    }
}
