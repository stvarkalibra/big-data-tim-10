
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.io.DefaultStorageIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SSTablePersistenceTest {

    @TempDir
    Path directory;

    private record Paths(Path temporary, Path finished) {
    }

    private Paths paths(long id) {
        return new Paths(
                directory.resolve("lsm/tmp")
                        .resolve(
                                SSTableMeta.fileName(id) + ".tmp"),
                directory.resolve("lsm/sst")
                        .resolve(SSTableMeta.fileName(id))
        );
    }

    private static List<InternalRecord> records(int count) {
        List<InternalRecord> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            result.add(
                    InternalRecord.put(
                            String.format("key%05d", i),
                            i + 1L,
                            new byte[100]
                    )
            );
        }

        return result;
    }

    private static SSTableDiskWriter writer(
            FailpointRegistry registry) {

        return new SSTableDiskWriter(
                new DefaultStorageIO(registry)
        );
    }

    private static OpContext context() {
        return OpContext.background();
    }

    private void armIoFailure(
            FailpointRegistry registry,
            String failpoint) {

        registry.addRule(
                new FailpointRegistry.Rule(
                        UUID.randomUUID().toString(),
                        failpoint,
                        FailpointRegistry.Action.IO_ERROR,
                        0,
                        1,
                        5000,
                        Map.of()
                )
        );
    }

    @Test
    void writesValidSstableOnDisk() throws Exception {
        Paths p = paths(101);

        List<InternalRecord> data = records(300);

        SSTableMeta meta = writer(
                new FailpointRegistry(false)
        ).writeAndFinalize(
                p.temporary(),
                p.finished(),
                101,
                data.iterator(),
                data.size(),
                context()
        );

        assertFalse(Files.exists(p.temporary()));
        assertTrue(Files.isRegularFile(p.finished()));

        assertEquals(
                Files.size(p.finished()),
                meta.sizeBytes()
        );

        assertDoesNotThrow(
                () -> SSTableValidator.validateFully(
                        p.finished(), meta)
        );

        var source = DiskSSTableTableSource.open(
                p.finished(), meta);

        assertEquals(
                1L,
                source.get("key00000")
                        .orElseThrow().sequence()
        );

        assertEquals(
                300L,
                source.get("key00299")
                        .orElseThrow().sequence()
        );
    }

    @Test
    void preservesDeleteAndEmptyPut() throws Exception {
        Paths p = paths(102);

        List<InternalRecord> data = List.of(
                InternalRecord.put(
                        "alpha", 5, new byte[]{1, 2}),
                InternalRecord.delete("beta", 6),
                InternalRecord.put(
                        "gamma", 7, new byte[0])
        );

        SSTableMeta meta = writer(
                new FailpointRegistry(false)
        ).writeAndFinalize(
                p.temporary(),
                p.finished(),
                102,
                data.iterator(),
                data.size(),
                context()
        );

        var source = DiskSSTableTableSource.open(
                p.finished(), meta);

        assertTrue(
                source.get("beta")
                        .orElseThrow().tombstone()
        );

        assertArrayEquals(
                new byte[0],
                source.get("gamma")
                        .orElseThrow().value()
        );
    }

    @Test
    void writesManyBlocksWithoutInMemorySstable()
            throws Exception {

        Paths p = paths(103);
        List<InternalRecord> data = records(2000);

        SSTableMeta meta = writer(
                new FailpointRegistry(false)
        ).writeAndFinalize(
                p.temporary(),
                p.finished(),
                103,
                data.iterator(),
                data.size(),
                context()
        );

        assertEquals(2000, meta.entryCount());

        var source = DiskSSTableTableSource.open(
                p.finished(), meta);

        assertEquals(
                2000L,
                source.get("key01999")
                        .orElseThrow().sequence()
        );
    }

    @Test
    void rejectsUnsortedRecordsBeforeFinalization()
            throws Exception {

        Paths p = paths(104);

        List<InternalRecord> data = List.of(
                InternalRecord.put(
                        "zeta", 1, new byte[]{1}),
                InternalRecord.put(
                        "alpha", 2, new byte[]{2})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> writer(
                        new FailpointRegistry(false)
                ).writeAndFinalize(
                        p.temporary(),
                        p.finished(),
                        104,
                        data.iterator(),
                        data.size(),
                        context()
                )
        );

        assertFalse(Files.exists(p.finished()));
    }

    @Test
    void writeFailureLeavesNoFinalTable()
            throws Exception {

        Paths p = paths(105);

        assertThrows(
                IllegalArgumentException.class,
                () -> writer(
                        new FailpointRegistry(false)
                ).writeAndFinalize(
                        p.temporary(),
                        p.finished(),
                        105,
                        records(3).iterator(),
                        4,
                        context()
                )
        );

        assertFalse(Files.exists(p.finished()));
    }

    @Test
    void firstFailpointPreventsRename()
            throws Exception {

        Paths p = paths(106);

        FailpointRegistry registry =
                new FailpointRegistry(true);

        armIoFailure(
                registry,
                FailpointNames
                        .SSTABLE_AFTER_WRITE_BEFORE_SYNC
        );

        List<InternalRecord> data = records(100);

        assertThrows(
                IOException.class,
                () -> writer(registry).writeAndFinalize(
                        p.temporary(),
                        p.finished(),
                        106,
                        data.iterator(),
                        data.size(),
                        context()
                )
        );

        assertFalse(Files.exists(p.finished()));
        assertTrue(Files.exists(p.temporary()));
    }

    @Test
    void secondFailpointPreventsRename()
            throws Exception {

        Paths p = paths(107);

        FailpointRegistry registry =
                new FailpointRegistry(true);

        armIoFailure(
                registry,
                FailpointNames
                        .SSTABLE_AFTER_SYNC_BEFORE_RENAME
        );

        List<InternalRecord> data = records(100);

        assertThrows(
                IOException.class,
                () -> writer(registry).writeAndFinalize(
                        p.temporary(),
                        p.finished(),
                        107,
                        data.iterator(),
                        data.size(),
                        context()
                )
        );

        assertFalse(Files.exists(p.finished()));
        assertTrue(Files.exists(p.temporary()));
    }

    @Test
    void thirdFailpointOccursAfterRename()
            throws Exception {

        Paths p = paths(108);

        FailpointRegistry registry =
                new FailpointRegistry(true);

        armIoFailure(
                registry,
                FailpointNames
                        .SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC
        );

        List<InternalRecord> data = records(100);

        assertThrows(
                IOException.class,
                () -> writer(registry).writeAndFinalize(
                        p.temporary(),
                        p.finished(),
                        108,
                        data.iterator(),
                        data.size(),
                        context()
                )
        );

        // Renamed, but no successful directory fsync.
        // It is still not published in the manifest.
        assertTrue(Files.exists(p.finished()));
        assertFalse(Files.exists(p.temporary()));
    }

    @Test
    void cannotReuseExistingTemporaryPath()
            throws Exception {

        Paths p = paths(109);

        Files.createDirectories(
                p.temporary().getParent()
        );
        Files.write(
                p.temporary(),
                new byte[]{1, 2, 3}
        );

        byte[] before =
                Files.readAllBytes(p.temporary());

        List<InternalRecord> data = records(10);

        assertThrows(
                IOException.class,
                () -> writer(
                        new FailpointRegistry(false)
                ).writeAndFinalize(
                        p.temporary(),
                        p.finished(),
                        109,
                        data.iterator(),
                        data.size(),
                        context()
                )
        );

        assertArrayEquals(
                before,
                Files.readAllBytes(p.temporary())
        );
    }

    @Test
    void rejectsMismatchedFinalFilename()
            throws Exception {

        Paths p = paths(110);

        List<InternalRecord> data = records(10);

        assertThrows(
                IllegalArgumentException.class,
                () -> writer(
                        new FailpointRegistry(false)
                ).writeAndFinalize(
                        p.temporary(),
                        directory.resolve(
                                "lsm/sst/wrong.sst"),
                        110,
                        data.iterator(),
                        data.size(),
                        context()
                )
        );

        assertFalse(Files.exists(p.temporary()));
    }
}
