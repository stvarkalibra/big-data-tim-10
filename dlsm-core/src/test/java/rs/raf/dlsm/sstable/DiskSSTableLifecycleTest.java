
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiskSSTableLifecycleTest {

    @TempDir
    Path directory;

    private record Fixture(
            Path file,
            SSTableMeta meta,
            byte[] original,
            DiskSSTableTableSource reader,
            StorageStats stats
    ) {
    }

    private Fixture createTable() throws Exception {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%05d", i),
                    i + 1L,
                    BlockCodec.PUT,
                    new byte[100]
            ));
        }

        SSTableWriter.Result table = SSTableWriter.build(
                42,
                records.iterator(),
                records.size()
        );

        Path file = directory.resolve(table.meta().file());

        byte[] original = table.bytes();
        Files.write(file, original);

        StorageStats stats = new StorageStats();

        DiskSSTableTableSource reader =
                DiskSSTableTableSource.open(
                        file,
                        table.meta(),
                        stats
                );

        return new Fixture(
                file,
                table.meta(),
                original,
                reader,
                stats
        );
    }

    private static String key(int index) {
        return String.format("key%05d", index);
    }

    private static long footerLong(
            byte[] bytes,
            int positionWithinFooter) {

        int footerStart =
                bytes.length - SSTableWriter.FOOTER_BYTES;

        return ByteBuffer.wrap(
                        bytes,
                        footerStart + positionWithinFooter,
                        Long.BYTES
                )
                .order(ByteOrder.BIG_ENDIAN)
                .getLong();
    }

    private static int checkedOffset(
            byte[] bytes,
            long offset) {

        assertTrue(offset >= 0);
        assertTrue(offset < bytes.length);

        return Math.toIntExact(offset);
    }

    private static void damageByte(
            Path file,
            byte[] original,
            int offset) throws Exception {

        byte[] damaged = original.clone();

        assertTrue(offset >= 0);
        assertTrue(offset < damaged.length);

        damaged[offset] ^= 0x40;

        Files.write(file, damaged);

        assertFalse(
                Arrays.equals(
                        original,
                        Files.readAllBytes(file)
                )
        );
    }

    @Test
    void unchangedPublishedTableCanBeReadRepeatedly()
            throws Exception {

        Fixture fixture = createTable();

        for (int i = 0; i < 3; i++) {
            assertEquals(
                    1L,
                    fixture.reader()
                            .get(key(0))
                            .orElseThrow()
                            .sequence()
            );

            assertEquals(
                    101L,
                    fixture.reader()
                            .get(key(100))
                            .orElseThrow()
                            .sequence()
            );

            assertEquals(
                    200L,
                    fixture.reader()
                            .get(key(199))
                            .orElseThrow()
                            .sequence()
            );
        }

        assertArrayEquals(
                fixture.original(),
                Files.readAllBytes(fixture.file())
        );
    }

    @Test
    void corruptionOfDataBlockAfterOpenIsNotNotFound()
            throws Exception {

        Fixture fixture = createTable();

        assertTrue(
                fixture.reader().get(key(0)).isPresent()
        );

        // Modify the stored CRC of the first data block.
        // The SSTable was valid when the reader opened.
        damageByte(
                fixture.file(),
                fixture.original(),
                SSTableWriter.HEADER_BYTES + 4
        );

        assertThrows(
                StorageException.class,
                () -> fixture.reader().get(key(0))
        );

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        fixture.file(),
                        fixture.meta()
                )
        );
    }

    @Test
    void iteratorReportsCorruptedBlockAfterOpen()
            throws Exception {

        Fixture fixture = createTable();

        damageByte(
                fixture.file(),
                fixture.original(),
                SSTableWriter.HEADER_BYTES + 4
        );

        var iterator = fixture.reader().iterator(key(0));

        assertThrows(
                UncheckedStorageException.class,
                iterator::hasNext
        );
    }

    @Test
    void deletionAfterOpenDoesNotReturnNotFound()
            throws Exception {

        Fixture fixture = createTable();

        Files.delete(fixture.file());

        assertFalse(Files.exists(fixture.file()));

        assertThrows(
                StorageException.class,
                () -> fixture.reader().get(key(0))
        );
    }

    @Test
    void truncationAfterOpenDoesNotReturnNotFound()
            throws Exception {

        Fixture fixture = createTable();

        Files.write(
                fixture.file(),
                Arrays.copyOf(
                        fixture.original(),
                        SSTableWriter.HEADER_BYTES + 3
                )
        );

        assertThrows(
                StorageException.class,
                () -> fixture.reader().get(key(0))
        );
    }

    @Test
    void damagedIndexIsRejectedOnReopen()
            throws Exception {

        Fixture fixture = createTable();

        // Footer layout:
        // 0 magic, 4 version, 8 tableId,
        // 16 dataOffset, 24 dataLength,
        // 32 indexOffset.
        long indexOffset =
                footerLong(fixture.original(), 32);

        damageByte(
                fixture.file(),
                fixture.original(),
                checkedOffset(
                        fixture.original(),
                        indexOffset
                )
        );

        assertThrows(
                StorageException.class,
                () -> DiskSSTableTableSource.open(
                        fixture.file(),
                        fixture.meta()
                )
        );
    }

    @Test
    void damagedBloomIsRejectedOnReopen()
            throws Exception {

        Fixture fixture = createTable();

        // Bloom region begins at footer offset 48.
        long bloomOffset =
                footerLong(fixture.original(), 48);

        damageByte(
                fixture.file(),
                fixture.original(),
                checkedOffset(
                        fixture.original(),
                        bloomOffset
                )
        );

        assertThrows(
                StorageException.class,
                () -> DiskSSTableTableSource.open(
                        fixture.file(),
                        fixture.meta()
                )
        );
    }

    @Test
    void damagedFooterIsRejectedOnReopen()
            throws Exception {

        Fixture fixture = createTable();

        // Corrupt a byte belonging to the footer CRC.
        damageByte(
                fixture.file(),
                fixture.original(),
                fixture.original().length - 1
        );

        assertThrows(
                StorageException.class,
                () -> DiskSSTableTableSource.open(
                        fixture.file(),
                        fixture.meta()
                )
        );
    }

    @Test
    void failedReopenDoesNotRepairOrRewriteFile()
            throws Exception {

        Fixture fixture = createTable();

        int footerCrcOffset =
                fixture.original().length - Integer.BYTES;

        damageByte(
                fixture.file(),
                fixture.original(),
                footerCrcOffset
        );

        byte[] before =
                Files.readAllBytes(fixture.file());

        assertThrows(
                StorageException.class,
                () -> DiskSSTableTableSource.open(
                        fixture.file(),
                        fixture.meta()
                )
        );

        assertArrayEquals(
                before,
                Files.readAllBytes(fixture.file())
        );
    }

    @Test
    void readCountersDoNotTreatFailedReadAsBloomSkip()
            throws Exception {

        Fixture fixture = createTable();

        long bloomSkipsBefore =
                fixture.stats()
                        .snapshot()
                        .get("bloomNegativeSkips");

        damageByte(
                fixture.file(),
                fixture.original(),
                SSTableWriter.HEADER_BYTES + 4
        );

        assertThrows(
                StorageException.class,
                () -> fixture.reader().get(key(0))
        );

        assertEquals(
                bloomSkipsBefore,
                fixture.stats()
                        .snapshot()
                        .get("bloomNegativeSkips")
        );
    }
}
