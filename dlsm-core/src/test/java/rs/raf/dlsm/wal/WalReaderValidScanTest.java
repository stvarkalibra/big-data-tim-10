package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class WalReaderValidScanTest {

    @TempDir
    Path dir;

    @Test
    void headerOnlySegmentIsValidAndEmpty() throws IOException {
        Path file = WalSegmentFixture.segment(1).writeTo(dir);

        WalScanResult result = WalReader.scanSegment(file);

        assertEquals(WalScanResult.Status.VALID, result.status());
        assertEquals(1, result.segmentId());
        assertTrue(result.entries().isEmpty());
        assertEquals(24, result.validLength());
        assertEquals(24, result.fileSize());
        assertEquals(OptionalLong.empty(), result.lastSequence());
        assertNull(result.corruption());
    }

    @Test
    void readsEveryRecordWithItsOffset() throws IOException {
        WalSegmentFixture fixture = WalSegmentFixture.segment(1)
                .put("alpha", 1, "A")
                .delete("beta", 2)
                .put("gamma", 3, "");

        WalScanResult result = WalReader.scanSegment(fixture.writeTo(dir));

        assertEquals(WalScanResult.Status.VALID, result.status());
        assertEquals(fixture.records(), result.records());
        assertEquals(129, result.validLength());
        assertEquals(129, result.fileSize());
        assertEquals(OptionalLong.of(3), result.lastSequence());

        for (int i = 0; i < 3; i++) {
            assertEquals(fixture.frameOffset(i), result.entries().get(i).offset());
            assertEquals(fixture.frameLength(i), result.entries().get(i).length());
        }
        assertEquals(24, result.entries().get(0).offset());
        assertEquals(60, result.entries().get(1).offset());
        assertEquals(94, result.entries().get(2).offset());
    }

    @Test
    void readsManyRecordsBackToBack() throws IOException {
        WalSegmentFixture fixture = WalSegmentFixture.segment(42);
        for (int i = 1; i <= 1_000; i++) {
            if (i % 7 == 0) {
                fixture.delete("key" + (i % 50), i);
            } else {
                fixture.put("key" + (i % 50), i, "v" + i);
            }
        }

        WalScanResult result = WalReader.scanSegment(fixture.writeTo(dir));

        assertEquals(WalScanResult.Status.VALID, result.status());
        assertEquals(42, result.segmentId());
        assertEquals(1_000, result.entries().size());
        assertEquals(fixture.records(), result.records());
        assertEquals(fixture.size(), result.validLength());
    }

    @Test
    void readsLargestPossibleFrame() throws IOException {
        byte[] value = new byte[InternalRecord.MAX_VALUE_BYTES];
        value[0] = 1;
        value[value.length - 1] = 2;
        WalSegmentFixture fixture = WalSegmentFixture.segment(1)
                .put("k".repeat(128), 1, value)
                .put("after", 2, "x");

        WalScanResult result = WalReader.scanSegment(fixture.writeTo(dir));

        assertEquals(WalScanResult.Status.VALID, result.status());
        assertEquals(WalFormat.MAX_FRAME_BYTES, result.entries().get(0).length());
        assertArrayEquals(value, result.records().get(0).value());
        assertEquals("after", result.records().get(1).key());
    }

    @Test
    void sequenceGapsAreAllowed() throws IOException {
        Path file = WalSegmentFixture.segment(1)
                .put("a", 1, "x")
                .put("b", 5, "x")
                .put("c", 100, "x")
                .writeTo(dir);

        assertEquals(WalScanResult.Status.VALID, WalReader.scanSegment(file).status());
    }

    @Test
    void continuesAfterSequenceOfPreviousSegment() throws IOException {
        Path file = WalSegmentFixture.segment(5)
                .put("a", 7, "x")
                .put("b", 8, "x")
                .writeTo(dir);

        assertEquals(WalScanResult.Status.VALID,
                WalReader.scanSegment(file, 6).status());
        assertEquals(WalScanResult.Status.VALID,
                WalReader.scanSegment(file, 0).status());
    }

    @Test
    void readsHeaderOnlyThroughReadSegmentHeader() throws IOException, StorageException {
        Path file = WalSegmentFixture.segment(9).put("a", 1, "x").writeTo(dir);

        assertEquals(9, WalReader.readSegmentHeader(file));
    }

    @Test
    void headerIdMustMatchFileName() throws IOException {
        Path file = WalSegmentFixture.write(dir, 3,
                WalSegmentFixture.segment(2).bytes());

        StorageException e = assertThrows(StorageException.class,
                () -> WalReader.readSegmentHeader(file));
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(WalFormat.CATEGORY_HEADER, e.category());

        WalScanResult result = WalReader.scanSegment(file);
        assertEquals(WalScanResult.Status.CORRUPT, result.status());
        assertEquals(WalFormat.CATEGORY_HEADER, result.corruption().category());
        assertEquals(0, result.corruption().offset());
    }

    @Test
    void rejectsFilesThatAreNotSegments() throws IOException {
        byte[] bytes = WalSegmentFixture.segment(1).bytes();
        Path temp = Files.write(dir.resolve(WalFileNames.tempFileName(1)), bytes);
        Path zero = Files.write(dir.resolve("wal-00000000000000000000.log"), bytes);
        Path other = Files.write(dir.resolve("notes.txt"), bytes);

        for (Path file : new Path[]{temp, zero, other}) {
            assertThrows(IllegalArgumentException.class, () -> WalReader.scanSegment(file));
            assertThrows(IllegalArgumentException.class, () -> WalReader.readSegmentHeader(file));
        }
    }

    @Test
    void rejectsNegativeAfterSequence() throws IOException {
        Path file = WalSegmentFixture.segment(1).writeTo(dir);

        assertThrows(IllegalArgumentException.class,
                () -> WalReader.scanSegment(file, -1));
    }

    @Test
    void missingFileIsAnIoErrorNotAClassification() {
        Path missing = dir.resolve(WalFileNames.segmentFileName(1));

        assertThrows(IOException.class, () -> WalReader.scanSegment(missing));
    }

    @Test
    void scanningNeverChangesTheFile() throws IOException {
        WalSegmentFixture fixture = WalSegmentFixture.segment(1)
                .put("a", 1, "x")
                .delete("b", 2);
        Path file = fixture.writeTo(dir);

        WalReader.scanSegment(file);

        assertArrayEquals(fixture.bytes(), Files.readAllBytes(file));
    }
}
