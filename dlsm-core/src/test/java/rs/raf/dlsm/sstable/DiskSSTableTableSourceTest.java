
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.engine.TableSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiskSSTableTableSourceTest {

    @TempDir
    Path directory;

    private static BlockCodec.Record put(
            String key, long sequence, byte[] value) {

        return new BlockCodec.Record(
                key, sequence, BlockCodec.PUT, value);
    }

    private SSTableWriter.Result build(int count) {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            records.add(put(
                    String.format("key%05d", i),
                    i + 1L,
                    new byte[]{(byte) i}
            ));
        }

        return SSTableWriter.build(
                42, records.iterator(), records.size());
    }

    private Path write(SSTableWriter.Result result)
            throws IOException {

        Path file = directory.resolve(result.meta().file());
        Files.write(file, result.bytes());
        return file;
    }

    @Test
    void opensRealFileAndExposesMetadata() throws Exception {
        var table = build(100);

        TableSource source = DiskSSTableTableSource.open(
                write(table), table.meta());

        assertEquals(table.meta(), source.meta());
        assertEquals(42L, source.tableId());
    }

    @Test
    void readsExistingKeys() throws Exception {
        var table = build(100);
        TableSource source = DiskSSTableTableSource.open(
                write(table), table.meta());

        for (int i : new int[]{0, 50, 99}) {
            InternalRecord record = source.get(
                    String.format("key%05d", i)).orElseThrow();

            assertEquals(i + 1L, record.sequence());
            assertEquals(Kind.PUT, record.kind());
            assertArrayEquals(
                    new byte[]{(byte) i}, record.value());
        }
    }

    @Test
    void missingKeysReturnEmpty() throws Exception {
        var table = build(100);
        TableSource source = DiskSSTableTableSource.open(
                write(table), table.meta());

        assertTrue(source.get("aaaa").isEmpty());
        assertTrue(source.get("zzzz").isEmpty());
        assertTrue(source.get("key00050x").isEmpty());
    }

    @Test
    void preservesTombstones() throws Exception {
        var records = List.of(
                new BlockCodec.Record(
                        "alpha", 1,
                        BlockCodec.DELETE, null),
                put("beta", 2, new byte[0])
        );

        var table = SSTableWriter.build(
                42, records.iterator(), records.size());

        TableSource source = DiskSSTableTableSource.open(
                write(table), table.meta());

        assertTrue(source.get("alpha").orElseThrow()
                .isTombstone());

        InternalRecord beta =
                source.get("beta").orElseThrow();

        assertEquals(Kind.PUT, beta.kind());
        assertFalse(beta.isTombstone());
        assertEquals(0, beta.valueLength());
    }

    @Test
    void iteratorStartsNearEnd() throws Exception {
        var table = build(1000);
        TableSource source = DiskSSTableTableSource.open(
                write(table), table.meta());

        Iterator<InternalRecord> iterator =
                source.iterator("key00990");

        int count = 0;

        while (iterator.hasNext()) {
            assertEquals(
                    String.format("key%05d", 990 + count),
                    iterator.next().key());
            count++;
        }

        assertEquals(10, count);
    }

    @Test
    void iteratorReturnsAllRecordsAcrossBlocks() throws Exception {
        var table = build(500);
        TableSource source = DiskSSTableTableSource.open(
                write(table), table.meta());

        Iterator<InternalRecord> iterator =
                source.iterator("key00000");

        int count = 0;

        while (iterator.hasNext()) {
            assertEquals(
                    String.format("key%05d", count),
                    iterator.next().key());
            count++;
        }

        assertEquals(500, count);
    }

    @Test
    void iteratorBeyondLastKeyIsEmpty() throws Exception {
        var table = build(100);
        TableSource source = DiskSSTableTableSource.open(
                write(table), table.meta());

        assertFalse(source.iterator("zzzz").hasNext());
    }

    @Test
    void corruptedFileFailsAtOpen() throws Exception {
        var table = build(100);
        Path path = write(table);

        byte[] bytes = Files.readAllBytes(path);
        bytes[SSTableWriter.HEADER_BYTES + 25] ^= 1;
        Files.write(path, bytes);

        StorageException error = assertThrows(
                StorageException.class,
                () -> DiskSSTableTableSource.open(
                        path, table.meta()));

        assertEquals(ErrorCode.STORAGE_IO, error.code());
        assertEquals("DATA_BLOCK", error.category());
    }

    @Test
    void corruptionAfterOpenFailsDuringGet() throws Exception {
        var table = build(100);
        Path path = write(table);

        TableSource source = DiskSSTableTableSource.open(
                path, table.meta());

        byte[] bytes = Files.readAllBytes(path);
        bytes[SSTableWriter.HEADER_BYTES + 25] ^= 1;
        Files.write(path, bytes);

        StorageException error = assertThrows(
                StorageException.class,
                () -> source.get("key00000"));

        assertEquals(ErrorCode.STORAGE_IO, error.code());
        assertEquals("DATA_BLOCK", error.category());
    }

    @Test
    void corruptionAfterOpenFailsDuringIteration()
            throws Exception {

        var table = build(100);
        Path path = write(table);

        TableSource source = DiskSSTableTableSource.open(
                path, table.meta());

        byte[] bytes = Files.readAllBytes(path);
        bytes[SSTableWriter.HEADER_BYTES + 25] ^= 1;
        Files.write(path, bytes);

        Iterator<InternalRecord> iterator =
                source.iterator("key00000");

        UncheckedStorageException error = assertThrows(
                UncheckedStorageException.class,
                iterator::hasNext);

        assertEquals(ErrorCode.STORAGE_IO,
                error.getCause().code());
    }

    @Test
    void missingFileFailsWithStorageException() {
        var table = build(10);

        Path missing = directory.resolve("missing.sst");

        StorageException error = assertThrows(
                StorageException.class,
                () -> DiskSSTableTableSource.open(
                        missing, table.meta()));

        assertEquals(ErrorCode.STORAGE_IO, error.code());
    }
}
