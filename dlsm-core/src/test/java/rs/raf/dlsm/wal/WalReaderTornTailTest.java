package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Torn tails as left by a crash on {@code wal.after-record-prefix}
 * (CP1-WAL-002): the last frame is a strict prefix of a complete frame.
 * {@code L} is the file length before the interrupted append.
 */
class WalReaderTornTailTest {

    @TempDir
    Path dir;

    private static WalSegmentFixture baseWithTornCandidate(InternalRecord candidate) {
        return WalSegmentFixture.segment(1)
                .put("base1", 1, "V1")
                .put("base2", 2, "V2")
                .record(candidate);
    }

    // Every cut inside the frame: inside the guarded header (1..7 bytes),
    // right after it (8 bytes) and up to one byte before the end.
    @Test
    void everyCutInsideLastFrameIsTornAtFrameStart() throws IOException {
        WalSegmentFixture fixture = baseWithTornCandidate(
                InternalRecord.put("torn", 3, "T".repeat(20).getBytes()));
        int frameStart = fixture.frameOffset(2);
        int frameLength = fixture.frameLength(2);

        for (int prefix = 1; prefix < frameLength; prefix++) {
            Path file = WalSegmentFixture.write(dir, 1,
                    Arrays.copyOf(fixture.bytes(), frameStart + prefix));

            WalScanResult result = WalReader.scanSegment(file);

            assertEquals(WalScanResult.Status.TORN_TAIL, result.status(), "prefix " + prefix);
            assertEquals(frameStart, result.tornFrameOffset(), "prefix " + prefix);
            assertEquals(frameStart + prefix, result.fileSize(), "prefix " + prefix);
            assertEquals(fixture.records().subList(0, 2), result.records(), "prefix " + prefix);
            assertNull(result.corruption(), "prefix " + prefix);
        }
    }

    @Test
    void cutExactlyAtFrameStartIsValid() throws IOException {
        WalSegmentFixture fixture = baseWithTornCandidate(
                InternalRecord.put("torn", 3, "T".getBytes()));
        int frameStart = fixture.frameOffset(2);

        Path file = WalSegmentFixture.write(dir, 1,
                Arrays.copyOf(fixture.bytes(), frameStart));
        WalScanResult result = WalReader.scanSegment(file);

        assertEquals(WalScanResult.Status.VALID, result.status());
        assertEquals(2, result.entries().size());
        assertThrows(IllegalStateException.class, result::tornFrameOffset);
    }

    @Test
    void tornDeleteFrame() throws IOException {
        WalSegmentFixture fixture = baseWithTornCandidate(InternalRecord.delete("base1", 3));
        int frameStart = fixture.frameOffset(2);

        for (int prefix : new int[]{1, 7, 8, fixture.frameLength(2) - 1}) {
            Path file = WalSegmentFixture.write(dir, 1,
                    Arrays.copyOf(fixture.bytes(), frameStart + prefix));

            assertEquals(frameStart, WalReader.scanSegment(file).tornFrameOffset(),
                    "prefix " + prefix);
        }
    }

    @Test
    void tornLargestFrame() throws IOException {
        WalSegmentFixture fixture = baseWithTornCandidate(InternalRecord.put(
                "k".repeat(128), 3, new byte[InternalRecord.MAX_VALUE_BYTES]));
        int frameStart = fixture.frameOffset(2);

        for (int prefix : new int[]{4, 8, 1_000, WalFormat.MAX_FRAME_BYTES - 1}) {
            Path file = WalSegmentFixture.write(dir, 1,
                    Arrays.copyOf(fixture.bytes(), frameStart + prefix));

            WalScanResult result = WalReader.scanSegment(file);
            assertEquals(WalScanResult.Status.TORN_TAIL, result.status(), "prefix " + prefix);
            assertEquals(frameStart, result.tornFrameOffset(), "prefix " + prefix);
        }
    }

    @Test
    void tornFirstFrameLeavesNoRecords() throws IOException {
        WalSegmentFixture fixture = WalSegmentFixture.segment(1).put("only", 1, "x");

        for (int prefix : new int[]{1, 8, fixture.frameLength(0) - 1}) {
            Path file = WalSegmentFixture.write(dir, 1,
                    Arrays.copyOf(fixture.bytes(), WalFormat.SEGMENT_HEADER_BYTES + prefix));

            WalScanResult result = WalReader.scanSegment(file);
            assertEquals(WalScanResult.Status.TORN_TAIL, result.status(), "prefix " + prefix);
            assertEquals(WalFormat.SEGMENT_HEADER_BYTES, result.tornFrameOffset());
            assertTrue(result.entries().isEmpty());
        }
    }

    // The reader only classifies; truncating is recovery's job (CP1 §21.1 step 9).
    @Test
    void scanningTornTailDoesNotTruncate() throws IOException {
        WalSegmentFixture fixture = baseWithTornCandidate(InternalRecord.put("torn", 3, "T".getBytes()));
        byte[] torn = Arrays.copyOf(fixture.bytes(), fixture.frameOffset(2) + 5);
        Path file = WalSegmentFixture.write(dir, 1, torn);

        WalReader.scanSegment(file);
        WalReader.scanSegment(file);

        assertArrayEquals(torn, Files.readAllBytes(file));
    }

    // After recovery truncates to the torn frame start, a second scan finds
    // nothing more to cut (CP1-WAL-008 idempotence).
    @Test
    void truncatingToTornOffsetGivesValidSegment() throws IOException {
        WalSegmentFixture fixture = baseWithTornCandidate(InternalRecord.put("torn", 3, "T".getBytes()));
        Path file = WalSegmentFixture.write(dir, 1,
                Arrays.copyOf(fixture.bytes(), fixture.frameOffset(2) + 9));

        long cut = WalReader.scanSegment(file).tornFrameOffset();
        WalSegmentFixture.write(dir, 1, Arrays.copyOf(Files.readAllBytes(file), (int) cut));

        WalScanResult second = WalReader.scanSegment(file);
        assertEquals(WalScanResult.Status.VALID, second.status());
        assertEquals(cut, second.validLength());
        assertEquals(fixture.records().subList(0, 2), second.records());
    }
}
