package rs.raf.dlsm.manifest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class TableIdAllocatorTest {

    @TempDir
    Path root;

    private Path sstDir;
    private Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
        sstDir = Files.createDirectories(root.resolve("sst"));
        tmpDir = Files.createDirectories(root.resolve("tmp"));
    }

    private static Manifest manifestWithNext(long nextTableId) {
        return new Manifest(1, 1, nextTableId, 0, List.of());
    }

    private static void touch(Path dir, String name) throws IOException {
        Files.write(dir.resolve(name), new byte[1]);
    }

    @Test
    void initialManifestOnEmptyDirectoriesAllocatesOne() throws IOException {
        TableIdAllocator allocator = TableIdAllocator.fromDisk(Manifest.initial(), sstDir, tmpDir);

        assertEquals(1, allocator.peek());
        assertEquals(1, allocator.next());
        assertEquals(2, allocator.next());
        assertEquals(3, allocator.peek());
    }

    @Test
    void manifestNextTableIdWinsWhenAboveFiles() throws IOException {
        touch(sstDir, SSTableMeta.fileName(4));

        assertEquals(12, TableIdAllocator.fromDisk(manifestWithNext(12), sstDir, tmpDir).next());
    }

    @Test
    void orphanTableAboveManifestMovesAllocationPastIt() throws IOException {
        touch(sstDir, SSTableMeta.fileName(12));

        assertEquals(13, TableIdAllocator.fromDisk(manifestWithNext(5), sstDir, tmpDir).next());
    }

    @Test
    void leftoverTempTableIsReserved() throws IOException {
        touch(tmpDir, TableIdAllocator.tempFileName(20));
        touch(sstDir, SSTableMeta.fileName(7));

        assertEquals(21, TableIdAllocator.fromDisk(manifestWithNext(3), sstDir, tmpDir).next());
    }

    @Test
    void misplacedNamesStillCount() throws IOException {
        touch(sstDir, TableIdAllocator.tempFileName(30));
        touch(tmpDir, SSTableMeta.fileName(40));

        assertEquals(41, TableIdAllocator.fromDisk(Manifest.initial(), sstDir, tmpDir).next());
    }

    @Test
    void ignoresNamesThatAreNotValidTableIds() throws IOException {
        touch(sstDir, "sst-12.sst");
        touch(sstDir, "sst-0000000000000000001x.sst");
        touch(sstDir, "sst-00000000000000000099.sst.bak");
        touch(tmpDir, "wal-00000000000000000088.log.tmp");
        touch(tmpDir, "MANIFEST-00000000000000000077.tmp");
        touch(tmpDir, "sst-99999999999999999999.sst.tmp");
        Files.createDirectory(sstDir.resolve("nested"));

        assertEquals(1, TableIdAllocator.fromDisk(Manifest.initial(), sstDir, tmpDir).next());
    }

    @Test
    void greatestPossibleIdOnDiskFailsExplicitly() throws IOException {
        touch(sstDir, SSTableMeta.fileName(Long.MAX_VALUE));

        assertThrows(IllegalStateException.class,
                () -> TableIdAllocator.fromDisk(Manifest.initial(), sstDir, tmpDir));
    }

    @Test
    void exhaustionFailsInsteadOfReusing() {
        TableIdAllocator allocator = TableIdAllocator.startingAt(Long.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, allocator.next());
        assertThrows(IllegalStateException.class, allocator::next);
        assertThrows(IllegalStateException.class, allocator::peek);
    }

    @Test
    void startingAtRejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> TableIdAllocator.startingAt(0));
    }

    @Test
    void fileNamesRoundTrip() {
        assertEquals("sst-00000000000000000011.sst.tmp", TableIdAllocator.tempFileName(11));
        assertEquals(OptionalLong.of(11), TableIdAllocator.parseTempId("sst-00000000000000000011.sst.tmp"));
        assertEquals(OptionalLong.of(11), TableIdAllocator.parseTableId(SSTableMeta.fileName(11)));
        assertEquals(OptionalLong.empty(), TableIdAllocator.parseTableId("sst-00000000000000000011.sst.tmp"));
        assertEquals(OptionalLong.empty(), TableIdAllocator.parseTempId(SSTableMeta.fileName(11)));
        assertThrows(IllegalArgumentException.class, () -> TableIdAllocator.tempFileName(0));
    }

    @Test
    void concurrentCallersNeverGetTheSameId() throws Exception {
        TableIdAllocator allocator = TableIdAllocator.startingAt(1);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        assertTrue(ids.add(allocator.next()));
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(8000, ids.size());
        assertEquals(8001, allocator.peek());
    }
}
