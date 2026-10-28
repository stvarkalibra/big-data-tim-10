package rs.raf.dlsm.wal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.MutationLog;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.stats.StorageStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class WalSegmentsTest {

    private static final OpContext CTX = OpContext.background();

    @TempDir
    Path root;

    private Path walDir;
    private Path tmpDir;

    @BeforeEach
    void createDirectories() throws IOException {
        walDir = Files.createDirectories(root.resolve("lsm/wal"));
        tmpDir = Files.createDirectories(root.resolve("lsm/tmp"));
    }

    private WalSegments segments(StorageIO io, WalIdAllocator ids) {
        JsonLog log = new JsonLog("test",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        return new WalSegments(walDir, tmpDir, io, new NoOpFailpoints(),
                new StorageStats(), ids, log);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ===== Successful creation =====

    @Test
    void createsDurableHeaderOnlySegment() throws IOException {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints());

        try (WalSegmentHandle segment = new WalSegmentHandle(
                segments(io, WalIdAllocator.startingAt(1)).createSegment(CTX))) {

            assertEquals(1, segment.log().segmentId());
            Path file = walDir.resolve(WalFileNames.segmentFileName(1));
            assertArrayEquals(WalCodec.encodeSegmentHeader(1), Files.readAllBytes(file));
            assertEquals(WalScanResult.Status.VALID, WalReader.scanSegment(file).status());
            assertEquals(List.of(), names(tmpDir));
        }
    }

    // FORMAT.md §3.1: temp file, force, atomic move, force of lsm/wal, in this order.
    @Test
    void followsPublishProtocolInOrder() throws IOException {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints());

        try (WalSegmentHandle ignored = new WalSegmentHandle(
                segments(io, WalIdAllocator.startingAt(7)).createSegment(CTX))) {

            assertEquals(List.of(
                    "openNew " + WalFileNames.tempFileName(7),
                    "writeFully",
                    "force",
                    "atomicMoveNoReplace " + WalFileNames.segmentFileName(7),
                    "fsyncDirectory wal"), io.calls());
        }
    }

    @Test
    void consecutiveSegmentsGetIncreasingIdsAboveDisk() throws IOException {
        Files.write(walDir.resolve(WalFileNames.segmentFileName(4)), WalCodec.encodeSegmentHeader(4));
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints());
        WalSegments segments = segments(io, WalIdAllocator.fromDisk(walDir, tmpDir));

        try (WalSegmentHandle first = new WalSegmentHandle(segments.createSegment(CTX));
             WalSegmentHandle second = new WalSegmentHandle(segments.createSegment(CTX))) {

            assertEquals(5, first.log().segmentId());
            assertEquals(6, second.log().segmentId());
            assertEquals(List.of(WalFileNames.segmentFileName(4), WalFileNames.segmentFileName(5),
                    WalFileNames.segmentFileName(6)), names(walDir));
        }
    }

    @Test
    void appendsStartRightAfterTheHeader() throws IOException {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints());

        try (WalSegmentHandle segment = new WalSegmentHandle(
                segments(io, WalIdAllocator.startingAt(1)).createSegment(CTX))) {
            segment.log().append(InternalRecord.put("alpha", 1, new byte[]{1}), CTX);
            segment.log().force(CTX);
        }

        WalScanResult scan = WalReader.scanSegment(walDir.resolve(WalFileNames.segmentFileName(1)));
        assertEquals(WalScanResult.Status.VALID, scan.status());
        assertEquals(WalFormat.SEGMENT_HEADER_BYTES, scan.entries().getFirst().offset());
    }

    // ===== Failure at each step: no segment, and what stays on disk =====

    @Test
    void failedHeaderWriteLeavesOnlyTemporaryFile() throws IOException {
        assertFailureLeaves("writeFully", List.of(), List.of(WalFileNames.tempFileName(1)));
    }

    @Test
    void failedFileForceLeavesOnlyTemporaryFile() throws IOException {
        assertFailureLeaves("force", List.of(), List.of(WalFileNames.tempFileName(1)));
    }

    @Test
    void failedMoveLeavesOnlyTemporaryFile() throws IOException {
        assertFailureLeaves("atomicMoveNoReplace", List.of(), List.of(WalFileNames.tempFileName(1)));
    }

    // The name exists but is not known to be durable: never handed out.
    @Test
    void failedDirectoryForceLeavesUnusedFinalSegment() throws IOException {
        assertFailureLeaves("fsyncDirectory", List.of(WalFileNames.segmentFileName(1)), List.of());
    }

    @Test
    void failedCreationNeverReusesItsId() throws IOException {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints()).failOn("force");
        WalSegments segments = segments(io, WalIdAllocator.startingAt(1));

        assertThrows(IOException.class, () -> segments.createSegment(CTX));
        io.failOn(null);

        try (WalSegmentHandle next = new WalSegmentHandle(segments.createSegment(CTX))) {
            assertEquals(2, next.log().segmentId());
        }
    }

    @Test
    void existingFinalSegmentIsNeverReplaced() throws IOException {
        Path existing = walDir.resolve(WalFileNames.segmentFileName(1));
        byte[] original = "keep me".getBytes(StandardCharsets.US_ASCII);
        Files.write(existing, original);
        WalSegments segments = segments(new RecordingStorageIO(new NoOpFailpoints()),
                WalIdAllocator.startingAt(1));

        assertThrows(FileAlreadyExistsException.class, () -> segments.createSegment(CTX));
        assertArrayEquals(original, Files.readAllBytes(existing));
    }

    @Test
    void existingTemporaryFileIsNeverOverwritten() throws IOException {
        Path leftover = tmpDir.resolve(WalFileNames.tempFileName(3));
        Files.write(leftover, new byte[]{9});
        WalSegments segments = segments(new RecordingStorageIO(new NoOpFailpoints()),
                WalIdAllocator.startingAt(3));

        assertThrows(FileAlreadyExistsException.class, () -> segments.createSegment(CTX));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(leftover));
        assertEquals(List.of(), names(walDir));
    }

    // ===== Catalog of segments on disk =====

    @Test
    void createdSegmentIsInTheCatalogAsHeaderOnly() throws IOException {
        WalSegments segments = segments(new RecordingStorageIO(new NoOpFailpoints()),
                WalIdAllocator.startingAt(3));

        try (WalSegmentHandle ignored = new WalSegmentHandle(segments.createSegment(CTX))) {
            assertEquals(List.of(new WalCatalog.Segment(3, WalFormat.SEGMENT_HEADER_BYTES,
                    null, null)), segments.catalog().snapshot());
        }
    }

    @Test
    void failedMoveLeavesNothingInTheCatalog() {
        WalSegments segments = segments(new RecordingStorageIO(new NoOpFailpoints())
                .failOn("atomicMoveNoReplace"), WalIdAllocator.startingAt(1));

        assertThrows(IOException.class, () -> segments.createSegment(CTX));
        assertEquals(List.of(), segments.catalog().snapshot());
    }

    // The final name exists in wal/, so the status must show it until recovery deletes it.
    @Test
    void failedDirectoryForceKeepsTheFinalSegmentInTheCatalog() {
        WalSegments segments = segments(new RecordingStorageIO(new NoOpFailpoints())
                .failOn("fsyncDirectory"), WalIdAllocator.startingAt(1));

        assertThrows(IOException.class, () -> segments.createSegment(CTX));
        assertEquals(List.of(1L), segments.catalog().snapshot().stream()
                .map(WalCatalog.Segment::segmentId).toList());
    }

    // ===== Retiring covered segments (CP1 §19.1 steps 12–13) =====

    @Test
    void retireDeletesSegmentsThenForcesTheWalDirectory() throws IOException {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints());
        WalSegments segments = segments(io, WalIdAllocator.startingAt(1));
        segments.createSegment(CTX).close();
        segments.createSegment(CTX).close();
        try (WalSegmentHandle active = new WalSegmentHandle(segments.createSegment(CTX))) {
            io.clearCalls();

            segments.retire(List.of(1L, 2L), CTX);

            assertEquals(List.of(
                    "delete " + WalFileNames.segmentFileName(1),
                    "delete " + WalFileNames.segmentFileName(2),
                    "fsyncDirectory wal"), io.calls());
            assertEquals(List.of(WalFileNames.segmentFileName(3)), names(walDir));
            assertEquals(List.of(3L), segments.catalog().snapshot().stream()
                    .map(WalCatalog.Segment::segmentId).toList());
        }
    }

    // A segment that is still on disk stays visible, e.g. as COVERED_PENDING_DELETE.
    @Test
    void failedDeleteKeepsTheSegmentInTheCatalog() throws IOException {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints());
        WalSegments segments = segments(io, WalIdAllocator.startingAt(1));
        segments.createSegment(CTX).close();
        io.failOn("delete");

        assertThrows(IOException.class, () -> segments.retire(List.of(1L), CTX));

        assertEquals(List.of(WalFileNames.segmentFileName(1)), names(walDir));
        assertEquals(List.of(1L), segments.catalog().snapshot().stream()
                .map(WalCatalog.Segment::segmentId).toList());
    }

    // ===== Real storage, including the directory force (Linux and Docker) =====

    @Test
    @EnabledOnOs(OS.LINUX)
    void createsSegmentWithRealDirectoryForce() throws IOException {
        WalSegments segments = segments(new DefaultStorageIO(new NoOpFailpoints()),
                WalIdAllocator.startingAt(1));

        try (WalSegmentHandle segment = new WalSegmentHandle(segments.createSegment(CTX))) {
            segment.log().append(InternalRecord.delete("gone", 1), CTX);
            segment.log().force(CTX);
        }

        assertEquals(WalScanResult.Status.VALID,
                WalReader.scanSegment(walDir.resolve(WalFileNames.segmentFileName(1))).status());
    }

    private void assertFailureLeaves(String failingCall, List<String> inWal, List<String> inTmp)
            throws IOException {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints()).failOn(failingCall);
        WalSegments segments = segments(io, WalIdAllocator.startingAt(1));

        assertThrows(IOException.class, () -> segments.createSegment(CTX));

        assertEquals(inWal, names(walDir));
        assertEquals(inTmp, names(tmpDir));
    }

    /** Closes the segment's file handle at the end of a test. */
    private record WalSegmentHandle(MutationLog log) implements AutoCloseable {
        @Override
        public void close() {
            log.close();
        }
    }
}
