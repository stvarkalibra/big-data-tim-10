package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import rs.raf.dlsm.common.Bin;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WalFrameCodecTest {

    private static final InternalRecord PUT = InternalRecord.put(
            "key-1", 7, "value".getBytes(StandardCharsets.US_ASCII));

    // ===== Round trip =====

    @Test
    void roundTripsPutDeleteAndEmptyPut() throws StorageException {
        for (InternalRecord record : new InternalRecord[]{
                PUT,
                InternalRecord.delete("gone", 8),
                InternalRecord.put("empty", 9, new byte[0])}) {

            assertEquals(record, WalCodec.decodeFrame(WalCodec.encodeFrame(record)));
        }
    }

    @Test
    void roundTripsEveryByteValue() throws StorageException {
        byte[] value = new byte[256];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) i;
        }
        InternalRecord record = InternalRecord.put("binary", 1, value);

        assertArrayEquals(value,
                WalCodec.decodeFrame(WalCodec.encodeFrame(record)).value());
    }

    @Test
    void smallestFrameIsThirtyOneBytes() throws StorageException {
        InternalRecord record = InternalRecord.delete("k", 1);
        byte[] frame = WalCodec.encodeFrame(record);

        assertEquals(WalFormat.MIN_FRAME_BYTES, frame.length);
        assertEquals(31, frame.length);
        assertEquals(record, WalCodec.decodeFrame(frame));
    }

    @Test
    void largestFrameIs262302Bytes() throws StorageException {
        InternalRecord record = InternalRecord.put("k".repeat(128), Long.MAX_VALUE,
                new byte[InternalRecord.MAX_VALUE_BYTES]);
        byte[] frame = WalCodec.encodeFrame(record);

        assertEquals(WalFormat.MAX_FRAME_BYTES, frame.length);
        assertEquals(262_302, frame.length);
        assertEquals(record, WalCodec.decodeFrame(frame));
    }

    // ===== Guarded header (steps 2–3) =====

    // CP1-WAL-003 (c): any changed byte in the length or its complement.
    @Test
    void everyChangeInGuardedHeaderIsLengthGuardCorruption() {
        byte[] original = WalCodec.encodeFrame(PUT);

        for (int offset = 0; offset < WalFormat.FRAME_GUARD_BYTES; offset++) {
            for (int mask : new int[]{0x01, 0x80, 0xFF}) {
                byte[] corrupted = original.clone();
                corrupted[offset] ^= (byte) mask;

                assertCategory(WalFormat.CATEGORY_LENGTH_GUARD,
                        () -> WalCodec.checkFrameGuard(corrupted, 0));
                assertCategory(WalFormat.CATEGORY_LENGTH_GUARD,
                        () -> WalCodec.decodeFrame(corrupted));
            }
        }
    }

    @Test
    void rejectsLengthsOutsideBoundsEvenWithMatchingComplement() {
        for (long total : new long[]{0, 30, 262_303, Bin.U32_MAX}) {
            byte[] guard = guard(total);

            assertCategory(WalFormat.CATEGORY_LENGTH_GUARD,
                    () -> WalCodec.checkFrameGuard(guard, 0));
        }
    }

    @Test
    void acceptsBoundaryLengthsWithMatchingComplement() throws StorageException {
        assertEquals(31, WalCodec.checkFrameGuard(guard(31), 0));
        assertEquals(262_302, WalCodec.checkFrameGuard(guard(262_302), 0));
    }

    @Test
    void readsGuardAtOffsetInsideLargerBuffer() throws StorageException {
        byte[] buffer = new byte[20];
        System.arraycopy(guard(100), 0, buffer, 12, 8);

        assertEquals(100, WalCodec.checkFrameGuard(buffer, 12));
    }

    @Test
    void shortTailMustBeClassifiedByCaller() {
        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.checkFrameGuard(new byte[7], 0));
        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.checkFrameGuard(new byte[10], 3));
    }

    @Test
    void decodeRequiresBufferOfDeclaredLength() {
        byte[] frame = WalCodec.encodeFrame(PUT);

        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.decodeFrame(Arrays.copyOf(frame, frame.length + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> WalCodec.decodeFrame(Arrays.copyOf(frame, frame.length - 1)));
    }

    // ===== Structure (steps 5–7), CRC fixed so structure checks are reached =====

    @Test
    void rejectsUnsupportedRecordVersion() {
        byte[] frame = WalCodec.encodeFrame(PUT);
        Bin.putU16(frame, WalFormat.FRAME_VERSION_OFFSET, 2);

        assertFrameError(withFixedCrc(frame));
    }

    @Test
    void rejectsNonzeroReservedByte() {
        byte[] frame = WalCodec.encodeFrame(PUT);
        Bin.putU8(frame, WalFormat.FRAME_RESERVED_OFFSET, 1);

        assertFrameError(withFixedCrc(frame));
    }

    @Test
    void rejectsUnknownKinds() {
        for (int kind : new int[]{0, 3, 0xFF}) {
            byte[] frame = WalCodec.encodeFrame(PUT);
            Bin.putU8(frame, WalFormat.FRAME_KIND_OFFSET, kind);

            assertFrameError(withFixedCrc(frame));
        }
    }

    @Test
    void rejectsDeleteCarryingValueBytes() {
        byte[] frame = WalCodec.encodeFrame(PUT);
        Bin.putU8(frame, WalFormat.FRAME_KIND_OFFSET, 2);

        assertFrameError(withFixedCrc(frame));
    }

    @Test
    void rejectsKeyLengthsOutsideBounds() {
        for (int keyLength : new int[]{0, 129, Bin.U16_MAX}) {
            byte[] frame = WalCodec.encodeFrame(PUT);
            Bin.putU16(frame, WalFormat.FRAME_KEY_LENGTH_OFFSET, keyLength);

            assertFrameError(withFixedCrc(frame));
        }
    }

    @Test
    void rejectsValueLengthAboveMaximum() {
        byte[] frame = WalCodec.encodeFrame(PUT);
        Bin.putU32(frame, WalFormat.FRAME_VALUE_LENGTH_OFFSET,
                InternalRecord.MAX_VALUE_BYTES + 1L);

        assertFrameError(withFixedCrc(frame));
    }

    @Test
    void rejectsInnerLengthsThatDoNotExplainFrame() {
        byte[] frame = WalCodec.encodeFrame(PUT);
        // k changes from 5 to 4 while v stays 5, so 30 + k + v = 39 != 40.
        Bin.putU16(frame, WalFormat.FRAME_KEY_LENGTH_OFFSET, 4);

        assertFrameError(withFixedCrc(frame));
    }

    @Test
    void rejectsInvalidKeyBytes() {
        for (int bad : new int[]{'/', ' ', 0x00, 0x80, 0xFF}) {
            byte[] frame = WalCodec.encodeFrame(PUT);
            frame[WalFormat.FRAME_KEY_OFFSET] = (byte) bad;

            assertFrameError(withFixedCrc(frame));
        }
    }

    // ===== CRC (step 8) =====

    // CP1-WAL-003 (a): bytes covered by the checksum.
    @Test
    void changedSequenceKeyOrValueIsChecksumCorruption() {
        byte[] original = WalCodec.encodeFrame(PUT);
        int keyOffset = WalFormat.FRAME_KEY_OFFSET;
        int valueOffset = keyOffset + 5;

        for (int offset : new int[]{
                WalFormat.FRAME_SEQUENCE_OFFSET + 7, keyOffset + 1, valueOffset}) {
            byte[] corrupted = original.clone();
            corrupted[offset] = (byte) (corrupted[offset] == 'a' ? 'b' : 'a');

            assertCategory(WalFormat.CATEGORY_CHECKSUM,
                    () -> WalCodec.decodeFrame(corrupted));
        }
    }

    // CP1-WAL-003 (b): the stored checksum itself.
    @Test
    void changedStoredChecksumIsChecksumCorruption() {
        byte[] original = WalCodec.encodeFrame(PUT);

        for (int offset = original.length - 4; offset < original.length; offset++) {
            byte[] corrupted = original.clone();
            corrupted[offset] ^= 0x01;

            assertCategory(WalFormat.CATEGORY_CHECKSUM,
                    () -> WalCodec.decodeFrame(corrupted));
        }
    }

    // CP1-WAL-003: a complete frame with one changed byte is never accepted
    // and always reported with one of the allowed categories.
    @Test
    void everySingleByteChangeIsRejectedWithAllowedCategory() {
        Set<String> allowed = Set.of(WalFormat.CATEGORY_LENGTH_GUARD,
                WalFormat.CATEGORY_FRAME, WalFormat.CATEGORY_CHECKSUM);

        for (InternalRecord record : new InternalRecord[]{
                PUT, InternalRecord.delete("gone", 8)}) {
            byte[] original = WalCodec.encodeFrame(record);

            for (int offset = 0; offset < original.length; offset++) {
                byte[] corrupted = original.clone();
                corrupted[offset] ^= 0x01;

                StorageException e = assertThrows(StorageException.class,
                        () -> WalCodec.decodeFrame(corrupted), "offset " + offset);
                assertTrue(allowed.contains(e.category()),
                        "offset " + offset + ": " + e.category());
            }
        }
    }

    // ===== Sequence (step 9, first half) =====

    @Test
    void rejectsNonpositiveSequenceAfterChecksum() {
        for (long sequence : new long[]{0, -1, Long.MIN_VALUE}) {
            byte[] frame = WalCodec.encodeFrame(PUT);
            Bin.putI64(frame, WalFormat.FRAME_SEQUENCE_OFFSET, sequence);

            assertCategory(WalFormat.CATEGORY_SEQUENCE_ORDER,
                    () -> WalCodec.decodeFrame(withFixedCrc(frame)));
        }
    }

    // ===== Helpers =====

    private static byte[] guard(long total) {
        byte[] guard = new byte[WalFormat.FRAME_GUARD_BYTES];
        Bin.putU32(guard, 0, total);
        Bin.putU32(guard, 4, total ^ Bin.U32_MAX);
        return guard;
    }

    /** Recomputes the record CRC so a test reaches the check before it. */
    private static byte[] withFixedCrc(byte[] frame) {
        int crcOffset = frame.length - WalFormat.FRAME_CRC_BYTES;
        Bin.putCrc32c(frame, WalFormat.FRAME_CRC_START,
                crcOffset - WalFormat.FRAME_CRC_START, crcOffset);
        return frame;
    }

    private static void assertFrameError(byte[] frame) {
        assertCategory(WalFormat.CATEGORY_FRAME, () -> WalCodec.decodeFrame(frame));
    }

    private static void assertCategory(String category, Executable call) {
        StorageException e = assertThrows(StorageException.class, call);

        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(category, e.category());
    }
}
