package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.Bin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One changed byte in a complete frame or in the segment header, with the
 * file length preserved (CP1-WAL-003). Such a file is corrupt: it is never
 * reported as a torn tail and nothing after the bad frame is interpreted.
 */
class WalReaderCorruptionTest {

    private static final Set<String> FRAME_CATEGORIES = Set.of(
            WalFormat.CATEGORY_LENGTH_GUARD,
            WalFormat.CATEGORY_FRAME,
            WalFormat.CATEGORY_CHECKSUM);

    @TempDir
    Path dir;

    private static WalSegmentFixture threeRecords() {
        return WalSegmentFixture.segment(1)
                .put("first", 1, "one")
                .delete("second", 2)
                .put("third", 3, "three");
    }

    // Subcases (a), (b), (c) on an interior frame: a complete frame follows it.
    @Test
    void everyByteOfInteriorFrameIsCorruption() throws IOException {
        assertEveryByteOfFrameIsCorruption(threeRecords(), 1);
    }

    // Variation: the damaged frame is physically last but fully present.
    @Test
    void everyByteOfLastCompleteFrameIsCorruption() throws IOException {
        assertEveryByteOfFrameIsCorruption(threeRecords(), 2);
    }

    // Subcase (c): a damaged length that now points past EOF still fails the
    // complement check first and is never mistaken for a torn tail.
    @Test
    void lengthPointingPastEofIsLengthGuardNotTornTail() throws IOException {
        WalSegmentFixture fixture = threeRecords();
        int target = fixture.frameOffset(2);
        byte[] bytes = fixture.bytes();
        bytes[target + 2] = 0x10; // totalFrameLength grows by 4096 bytes

        WalScanResult result = WalReader.scanSegment(WalSegmentFixture.write(dir, 1, bytes));

        assertEquals(WalScanResult.Status.CORRUPT, result.status());
        assertEquals(WalFormat.CATEGORY_LENGTH_GUARD, result.corruption().category());
        assertEquals(target, result.corruption().offset());
    }

    @Test
    void lengthBelowMinimumWithMatchingComplementIsLengthGuard() throws IOException {
        WalSegmentFixture fixture = threeRecords();
        int target = fixture.frameOffset(2);
        byte[] bytes = fixture.bytes();
        Bin.putU32(bytes, target, 30);
        Bin.putU32(bytes, target + 4, 30 ^ Bin.U32_MAX);

        WalScanResult result = WalReader.scanSegment(WalSegmentFixture.write(dir, 1, bytes));

        assertEquals(WalFormat.CATEGORY_LENGTH_GUARD, result.corruption().category());
        assertEquals(target, result.corruption().offset());
    }

    // Subcase (d): magic, version, reserved, segment ID or header checksum.
    @Test
    void everyByteOfSegmentHeaderIsHeaderCorruption() throws IOException {
        WalSegmentFixture fixture = threeRecords();

        for (int offset = 0; offset < WalFormat.SEGMENT_HEADER_BYTES; offset++) {
            byte[] bytes = fixture.bytes();
            bytes[offset] ^= 0x01;
            Path file = WalSegmentFixture.write(dir, 1, bytes);

            WalScanResult result = WalReader.scanSegment(file);

            assertEquals(WalScanResult.Status.CORRUPT, result.status(), "offset " + offset);
            assertEquals(WalFormat.CATEGORY_HEADER, result.corruption().category());
            assertEquals(0, result.corruption().offset());
            assertTrue(result.entries().isEmpty(), "offset " + offset);
        }
    }

    @Test
    void duplicateSequenceIsSequenceOrderCorruption() throws IOException {
        WalSegmentFixture fixture = WalSegmentFixture.segment(1)
                .put("a", 1, "x")
                .put("b", 2, "x")
                .put("c", 2, "x")
                .put("d", 3, "x");

        WalScanResult result = WalReader.scanSegment(fixture.writeTo(dir));

        assertEquals(WalScanResult.Status.CORRUPT, result.status());
        assertEquals(WalFormat.CATEGORY_SEQUENCE_ORDER, result.corruption().category());
        assertEquals(fixture.frameOffset(2), result.corruption().offset());
        assertEquals(fixture.records().subList(0, 2), result.records());
    }

    @Test
    void decreasingSequenceIsSequenceOrderCorruption() throws IOException {
        WalSegmentFixture fixture = WalSegmentFixture.segment(1)
                .put("a", 5, "x")
                .put("b", 4, "x");

        WalScanResult result = WalReader.scanSegment(fixture.writeTo(dir));

        assertEquals(WalFormat.CATEGORY_SEQUENCE_ORDER, result.corruption().category());
        assertEquals(fixture.frameOffset(1), result.corruption().offset());
    }

    @Test
    void sequenceNotAboveAfterSequenceIsSequenceOrderCorruption() throws IOException {
        Path file = WalSegmentFixture.segment(2).put("a", 6, "x").writeTo(dir);

        WalScanResult result = WalReader.scanSegment(file, 6);

        assertEquals(WalFormat.CATEGORY_SEQUENCE_ORDER, result.corruption().category());
        assertEquals(WalFormat.SEGMENT_HEADER_BYTES, result.corruption().offset());
    }

    private void assertEveryByteOfFrameIsCorruption(WalSegmentFixture fixture, int frameIndex)
            throws IOException {

        int target = fixture.frameOffset(frameIndex);

        for (int i = 0; i < fixture.frameLength(frameIndex); i++) {
            byte[] bytes = fixture.bytes();
            bytes[target + i] ^= 0x01;
            Path file = WalSegmentFixture.write(dir, 1, bytes);

            WalScanResult result = WalReader.scanSegment(file);
            String where = "frame " + frameIndex + ", byte " + i;

            assertEquals(WalScanResult.Status.CORRUPT, result.status(), where);
            assertTrue(FRAME_CATEGORIES.contains(result.corruption().category()),
                    where + ": " + result.corruption().category());
            assertEquals(target, result.corruption().offset(), where);
            assertEquals(target, result.validLength(), where);
            // Earlier frames are reported, nothing at or after the damage is.
            assertEquals(fixture.records().subList(0, frameIndex), result.records(), where);
            // Detection never shortens the file.
            assertEquals(bytes.length, Files.size(file), where);
        }
    }
}
