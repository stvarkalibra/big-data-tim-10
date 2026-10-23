package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.RecordingStorageIO;
import rs.raf.dlsm.wal.WalCodec;
import rs.raf.dlsm.wal.WalFileNames;
import rs.raf.dlsm.wal.WalIdAllocator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** CP1 §21.1 steps 5–6: temporary and orphan files, and what is never touched. */
class RecoveryCleanupTest {

    private static final OpContext CTX = OpContext.background();

    @TempDir
    Path root;

    private DataLayout layout;
    private RecordingStorageIO io;
    private StorageStats stats;
    private ByteArrayOutputStream logOutput;
    private RecoveryProcedure recovery;

    @BeforeEach
    void setUp() throws IOException {
        layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        FailpointRegistry failpoints = new FailpointRegistry(true);
        io = new RecordingStorageIO(failpoints);
        stats = new StorageStats();
        logOutput = new ByteArrayOutputStream();
        JsonLog log = new JsonLog("test",
                new PrintStream(logOutput, true, StandardCharsets.UTF_8), Clock.systemUTC());
        recovery = new RecoveryProcedure(layout, "standalone", io, failpoints, stats, log);
    }

    /** A manifest referencing the given tables; cleanup only reads their names. */
    private static Manifest manifestWithTables(long... ids) {
        List<SSTableMeta> tables = new ArrayList<>();
        long maxId = 0;
        for (long id : ids) {
            tables.add(new SSTableMeta(id, SSTableMeta.fileName(id), "a", "z", id, id, 1, 100));
            maxId = Math.max(maxId, id);
        }
        return new Manifest(Manifest.FORMAT_VERSION, 4, maxId + 1, maxId, tables);
    }

    private Path touch(Path dir, String name) throws IOException {
        return Files.write(dir.resolve(name), new byte[]{1, 2, 3});
    }

    private List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private long counter(String name) {
        return stats.snapshot().get(name);
    }

    private String log() {
        return logOutput.toString(StandardCharsets.UTF_8);
    }

    // ===== Temporary files =====

    @Test
    void everyTemporaryFileIsRemovedAndCounted() throws IOException {
        touch(layout.tmpDir(), WalFileNames.tempFileName(7));
        touch(layout.tmpDir(), TableIdAllocator.tempFileName(4));
        touch(layout.tmpDir(), "MANIFEST-00000000000000000005.tmp");
        touch(layout.tmpDir(), DataLayout.NODE_TEMP_FILE);
        touch(layout.tmpDir(), "anything-else.bin");

        RecoveryProcedure.Cleanup cleanup = recovery.removeTemporaryAndOrphanFiles(manifestWithTables(), CTX);

        assertEquals(5, cleanup.temporaryRemoved().size());
        assertEquals(List.of(), names(layout.tmpDir()));
        assertEquals(5, counter("temporaryFilesRemoved"));
        assertEquals(0, counter("orphanFilesRemoved"));
        assertTrue(io.calls().contains("fsyncDirectory tmp"), io.calls().toString());
    }

    // ===== Orphan tables =====

    // A final table the manifest does not reference is not part of the database (I4).
    @Test
    void unreferencedTableIsAnOrphanEvenIfValid() throws IOException {
        touch(layout.sstDir(), SSTableMeta.fileName(1));
        touch(layout.sstDir(), SSTableMeta.fileName(2));
        touch(layout.sstDir(), SSTableMeta.fileName(3));
        touch(layout.sstDir(), SSTableMeta.fileName(9));

        RecoveryProcedure.Cleanup cleanup = recovery.removeTemporaryAndOrphanFiles(manifestWithTables(1, 3), CTX);

        assertEquals(List.of(SSTableMeta.fileName(2), SSTableMeta.fileName(9)), cleanup.orphansRemoved());
        assertEquals(List.of(SSTableMeta.fileName(1), SSTableMeta.fileName(3)), names(layout.sstDir()));
        assertEquals(2, counter("orphanFilesRemoved"));
        assertTrue(io.calls().contains("fsyncDirectory sst"), io.calls().toString());
    }

    @Test
    void unrecognizedFilesInSstAreLeftAndLogged() throws IOException {
        touch(layout.sstDir(), "notes.txt");
        touch(layout.sstDir(), "sst-1.sst");

        RecoveryProcedure.Cleanup cleanup = recovery.removeTemporaryAndOrphanFiles(manifestWithTables(), CTX);

        assertEquals(List.of(), cleanup.orphansRemoved());
        assertEquals(List.of("notes.txt", "sst-1.sst"), names(layout.sstDir()));
        assertTrue(log().contains("\"event\":\"RECOVERY_UNKNOWN_FILE\""), log());
    }

    // ===== What is never touched here =====

    @Test
    void walDirectoryIsNotTouched() throws IOException {
        Files.write(layout.walDir().resolve(WalFileNames.segmentFileName(1)), WalCodec.encodeSegmentHeader(1));
        touch(layout.walDir(), "stray.bin");

        recovery.removeTemporaryAndOrphanFiles(manifestWithTables(), CTX);

        assertEquals(List.of("stray.bin", WalFileNames.segmentFileName(1)), names(layout.walDir()));
    }

    @Test
    void cleanDirectoryNeedsNoWork() throws IOException {
        touch(layout.sstDir(), SSTableMeta.fileName(1));

        RecoveryProcedure.Cleanup cleanup = recovery.removeTemporaryAndOrphanFiles(manifestWithTables(1), CTX);

        assertEquals(List.of(), cleanup.temporaryRemoved());
        assertEquals(List.of(), cleanup.orphansRemoved());
        assertEquals(List.of(), io.calls());
        assertFalse(log().contains("RECOVERY_CLEANUP"));
    }

    // ===== IDs and repetition =====

    // CP1 §16.1, §18.3: allocators are built before cleanup, so a removed
    // leftover's ID is never handed out again.
    @Test
    void idsOfRemovedLeftoversStayReserved() throws IOException {
        touch(layout.tmpDir(), WalFileNames.tempFileName(12));
        touch(layout.tmpDir(), TableIdAllocator.tempFileName(20));
        touch(layout.sstDir(), SSTableMeta.fileName(15));
        Manifest manifest = manifestWithTables(1);

        WalIdAllocator walIds = WalIdAllocator.fromDisk(layout.walDir(), layout.tmpDir());
        TableIdAllocator tableIds = TableIdAllocator.fromDisk(manifest, layout.sstDir(), layout.tmpDir());
        recovery.removeTemporaryAndOrphanFiles(manifest, CTX);

        assertEquals(13, walIds.next());
        assertEquals(21, tableIds.next());
    }

    @Test
    void secondCleanupFindsNothingMore() throws IOException {
        touch(layout.tmpDir(), WalFileNames.tempFileName(1));
        touch(layout.sstDir(), SSTableMeta.fileName(5));
        Manifest manifest = manifestWithTables();
        recovery.removeTemporaryAndOrphanFiles(manifest, CTX);
        io.clearCalls();

        RecoveryProcedure.Cleanup second = recovery.removeTemporaryAndOrphanFiles(manifest, CTX);

        assertEquals(List.of(), second.temporaryRemoved());
        assertEquals(List.of(), second.orphansRemoved());
        assertEquals(List.of(), io.calls());
    }

    // A crash midway leaves files the next recovery classifies and deletes the same way.
    @Test
    void interruptedCleanupIsFinishedNextTime() throws IOException {
        touch(layout.tmpDir(), WalFileNames.tempFileName(1));
        touch(layout.sstDir(), SSTableMeta.fileName(5));
        Manifest manifest = manifestWithTables();
        io.failOn("delete");

        assertThrows(IOException.class, () -> recovery.removeTemporaryAndOrphanFiles(manifest, CTX));
        io.failOn(null);
        recovery.removeTemporaryAndOrphanFiles(manifest, CTX);

        assertEquals(List.of(), names(layout.tmpDir()));
        assertEquals(List.of(), names(layout.sstDir()));
    }
}
