
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableStreamingValidatorTest {

    @TempDir
    Path directory;

    private SSTableWriter.Result createTable(int count) {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%05d", i),
                    i + 1L,
                    BlockCodec.PUT,
                    new byte[100]
            ));
        }

        return SSTableWriter.build(
                42, records.iterator(), records.size());
    }

    private Path write(SSTableWriter.Result table)
            throws IOException {
        Path file = directory.resolve(table.meta().file());
        Files.write(file, table.bytes());
        return file;
    }

    @Test
    void validatesRealFile() throws IOException {
        var table = createTable(100);
        Path file = write(table);

        assertDoesNotThrow(() ->
                SSTableValidator.validateFully(file, table.meta()));

        assertTrue(SSTableValidator.isValid(file, table.meta()));
    }

    @Test
    void validatesManyDataBlocks() throws IOException {
        var table = createTable(10_000);
        Path file = write(table);

        assertDoesNotThrow(() ->
                SSTableValidator.validateFully(file, table.meta()));
    }

    @Test
    void rejectsCorruptedDataBlock() throws IOException {
        var table = createTable(100);
        Path file = write(table);

        byte[] bytes = Files.readAllBytes(file);
        bytes[SSTableWriter.HEADER_BYTES + 25] ^= 1;
        Files.write(file, bytes);

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        file, table.meta()));

        assertEquals(
                SSTableValidator.Category.DATA_BLOCK,
                exception.category());
    }

    @Test
    void rejectsCorruptedIndex() throws IOException {
        var table = createTable(100);
        Path file = write(table);

        byte[] bytes = Files.readAllBytes(file);
        int footer = bytes.length - SSTableWriter.FOOTER_BYTES;

        long indexOffset = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong(footer + 32);

        bytes[Math.toIntExact(indexOffset) + 5] ^= 1;
        Files.write(file, bytes);

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        file, table.meta()));

        assertEquals(
                SSTableValidator.Category.INDEX,
                exception.category());
    }

    @Test
    void rejectsCorruptedBloom() throws IOException {
        var table = createTable(100);
        Path file = write(table);

        byte[] bytes = Files.readAllBytes(file);
        int footer = bytes.length - SSTableWriter.FOOTER_BYTES;

        long bloomOffset = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong(footer + 48);

        bytes[Math.toIntExact(bloomOffset) + 5] ^= 1;
        Files.write(file, bytes);

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        file, table.meta()));

        assertEquals(
                SSTableValidator.Category.BLOOM,
                exception.category());
    }

    @Test
    void rejectsCorruptedFooter() throws IOException {
        var table = createTable(10);
        Path file = write(table);

        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 8] ^= 1;
        Files.write(file, bytes);

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        file, table.meta()));

        assertEquals(
                SSTableValidator.Category.FOOTER,
                exception.category());
    }

    @Test
    void rejectsTruncatedFile() throws IOException {
        var table = createTable(10);
        Path file = write(table);

        Files.write(file, new byte[]{1, 2, 3});

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        file, table.meta()));
    }

    @Test
    void rejectsManifestMetadataMismatch() throws IOException {
        var table = createTable(10);
        Path file = write(table);

        SSTableMeta original = table.meta();

        SSTableMeta wrong = new SSTableMeta(
                original.tableId(),
                original.file(),
                original.minKey(),
                original.maxKey(),
                original.minSequence(),
                original.maxSequence(),
                original.entryCount() + 1,
                original.sizeBytes()
        );

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        file, wrong));

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                exception.category());
    }

    @Test
    void validationDoesNotModifyFile() throws IOException {
        var table = createTable(100);
        Path file = write(table);

        byte[] before = Files.readAllBytes(file);

        SSTableValidator.validateFully(file, table.meta());

        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void missingFileIsRejected() {
        var table = createTable(10);
        Path missing = directory.resolve("missing.sst");

        assertFalse(SSTableValidator.isValid(
                missing, table.meta()));
    }
}
