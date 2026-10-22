package rs.raf.dlsm.wal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WalIdAllocatorTest {

    @TempDir
    Path root;

    private Path walDir;
    private Path tmpDir;

    @BeforeEach
    void createDirectories() throws IOException {
        walDir = Files.createDirectories(root.resolve("lsm/wal"));
        tmpDir = Files.createDirectories(root.resolve("lsm/tmp"));
    }

    private void touch(Path dir, String name) throws IOException {
        Files.write(dir.resolve(name), new byte[0]);
    }

    @Test
    void emptyDirectoriesStartAtOne() throws IOException {
        assertEquals(1, WalIdAllocator.fromDisk(walDir, tmpDir).next());
    }

    @Test
    void startsAboveGreatestFinalSegment() throws IOException {
        touch(walDir, WalFileNames.segmentFileName(2));
        touch(walDir, WalFileNames.segmentFileName(9));
        touch(walDir, WalFileNames.segmentFileName(5));

        assertEquals(10, WalIdAllocator.fromDisk(walDir, tmpDir).next());
    }

    // A crash before finalization leaves a temp file; its ID stays reserved.
    @Test
    void startsAboveGreatestTemporarySegment() throws IOException {
        touch(walDir, WalFileNames.segmentFileName(3));
        touch(tmpDir, WalFileNames.tempFileName(7));

        assertEquals(8, WalIdAllocator.fromDisk(walDir, tmpDir).next());
    }

    @Test
    void misplacedNamesStillReserveTheirIds() throws IOException {
        touch(tmpDir, WalFileNames.segmentFileName(12));
        touch(walDir, WalFileNames.tempFileName(15));

        assertEquals(16, WalIdAllocator.fromDisk(walDir, tmpDir).next());
    }

    @Test
    void ignoresNamesThatAreNotWalIds() throws IOException {
        touch(walDir, "wal-1.log");
        touch(walDir, "notes.txt");
        touch(tmpDir, "sst-00000000000000000099.sst.tmp");
        touch(tmpDir, "MANIFEST-00000000000000000099.tmp");
        touch(tmpDir, "wal-99999999999999999999.log.tmp");

        assertEquals(1, WalIdAllocator.fromDisk(walDir, tmpDir).next());
    }

    @Test
    void zeroIdOnDiskStillStartsAtOne() throws IOException {
        touch(walDir, "wal-00000000000000000000.log");

        assertEquals(1, WalIdAllocator.fromDisk(walDir, tmpDir).next());
    }

    @Test
    void handsOutIncreasingIdsAndPeekDoesNotReserve() {
        WalIdAllocator ids = WalIdAllocator.startingAt(4);

        assertEquals(4, ids.peek());
        assertEquals(4, ids.peek());
        assertEquals(4, ids.next());
        assertEquals(5, ids.next());
        assertEquals(6, ids.peek());
    }

    @Test
    void neverReturnsTheSameIdUnderConcurrency() throws InterruptedException {
        WalIdAllocator ids = WalIdAllocator.startingAt(1);
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);

        for (int i = 0; i < 8_000; i++) {
            pool.execute(() -> seen.add(ids.next()));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(8_000, seen.size());
        assertEquals(8_001, ids.peek());
    }

    @Test
    void lastIdIsUsableOnceThenExhausted() {
        WalIdAllocator ids = WalIdAllocator.startingAt(Long.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, ids.next());
        assertThrows(IllegalStateException.class, ids::next);
        assertThrows(IllegalStateException.class, ids::peek);
    }

    @Test
    void segmentWithLargestIdOnDiskExhaustsAllocator() throws IOException {
        touch(walDir, WalFileNames.segmentFileName(Long.MAX_VALUE));

        assertThrows(IllegalStateException.class,
                () -> WalIdAllocator.fromDisk(walDir, tmpDir));
    }

    @Test
    void rejectsNonPositiveStart() {
        assertThrows(IllegalArgumentException.class, () -> WalIdAllocator.startingAt(0));
        assertThrows(IllegalArgumentException.class, () -> WalIdAllocator.startingAt(-3));
    }

    @Test
    void missingDirectoryIsAnError() {
        Path missing = root.resolve("nope");

        assertThrows(NoSuchFileException.class, () -> WalIdAllocator.fromDisk(missing, tmpDir));
        assertThrows(NoSuchFileException.class, () -> WalIdAllocator.fromDisk(walDir, missing));
    }

    @Test
    void readingIdsLeavesFilesInPlace() throws IOException {
        touch(walDir, WalFileNames.segmentFileName(2));
        touch(tmpDir, WalFileNames.tempFileName(3));

        WalIdAllocator.fromDisk(walDir, tmpDir);

        Set<String> names = new HashSet<>();
        try (var stream = Files.list(walDir)) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        }
        try (var stream = Files.list(tmpDir)) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        }
        assertEquals(Set.of(WalFileNames.segmentFileName(2), WalFileNames.tempFileName(3)), names);
    }
}
