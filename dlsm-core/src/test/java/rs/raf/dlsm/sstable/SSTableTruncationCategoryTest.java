
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableTruncationCategoryTest {

    @TempDir
    Path directory;

    private SSTableWriter.Result createTable() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
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

    private Path write(byte[] bytes) throws IOException {
        Path file = directory.resolve(
                SSTableMeta.fileName(42));
        Files.write(file, bytes);
        return file;
    }

    private static void assertCategory(
            Path file,
            SSTableMeta meta,
            SSTableValidator.Category expectedCategory) {

        SSTableValidator.ValidationException error =
                assertThrows(
                        SSTableValidator.ValidationException.class,
                        () -> SSTableValidator.validateFully(
                                file, meta));

        assertEquals(expectedCategory, error.category());
        assertFalse(SSTableValidator.isValid(file, meta));
    }

    @Test
    void acceptsUnmodifiedSstable() throws Exception {
        var table = createTable();
        Path file = write(table.bytes());

        assertDoesNotThrow(
                () -> SSTableValidator.validateFully(
                        file, table.meta()));

        assertTrue(SSTableValidator.isValid(file, table.meta()));
    }

    @Test
    void rejectsEmptyFileAsTruncated() throws Exception {
        var table = createTable();
        Path file = write(new byte[0]);

        assertCategory(
                file,
                table.meta(),
                SSTableValidator.Category.TRUNCATED_REGION);
    }

    @Test
    void rejectsHeaderOnlyFileAsTruncated()
            throws Exception {

        var table = createTable();

        Path file = write(Arrays.copyOf(
                table.bytes(),
                SSTableWriter.HEADER_BYTES));

        assertCategory(
                file,
                table.meta(),
                SSTableValidator.Category.TRUNCATED_REGION);
    }

    @Test
    void rejectsFileWithOneByteMissing()
            throws Exception {

        var table = createTable();
        byte[] bytes = table.bytes();

        Path file = write(Arrays.copyOf(
                bytes, bytes.length - 1));

        assertCategory(
                file,
                table.meta(),
                SSTableValidator.Category.TRUNCATED_REGION);
    }

    @Test
    void rejectsLargeTruncationAsTruncated()
            throws Exception {

        var table = createTable();
        byte[] bytes = table.bytes();

        Path file = write(Arrays.copyOf(
                bytes, bytes.length / 2));

        assertCategory(
                file,
                table.meta(),
                SSTableValidator.Category.TRUNCATED_REGION);
    }

    @Test
    void rejectsTruncationThatStillExceedsMinimumSize()
            throws Exception {

        var table = createTable();
        byte[] bytes = table.bytes();

        int truncatedSize = bytes.length - 100;

        assertTrue(truncatedSize
                > SSTableWriter.HEADER_BYTES
                + SSTableWriter.FOOTER_BYTES);

        Path file = write(Arrays.copyOf(
                bytes, truncatedSize));

        assertCategory(
                file,
                table.meta(),
                SSTableValidator.Category.TRUNCATED_REGION);
    }

    @Test
    void rejectsAppendedBytesAsManifestSizeMismatch()
            throws Exception {

        var table = createTable();
        byte[] original = table.bytes();

        byte[] extended = Arrays.copyOf(
                original, original.length + 100);

        Path file = write(extended);

        assertCategory(
                file,
                table.meta(),
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH);
    }

    @Test
    void rejectsWrongManifestEntryCount()
            throws Exception {

        var table = createTable();
        SSTableMeta original = table.meta();

        SSTableMeta incorrect = new SSTableMeta(
                original.tableId(),
                original.file(),
                original.minKey(),
                original.maxKey(),
                original.minSequence(),
                original.maxSequence(),
                original.entryCount() + 1,
                original.sizeBytes()
        );

        Path file = write(table.bytes());

        assertCategory(
                file,
                incorrect,
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH);
    }

    @Test
    void rejectsWrongManifestSizeLargerThanFile()
            throws Exception {

        var table = createTable();
        SSTableMeta original = table.meta();

        SSTableMeta incorrect = new SSTableMeta(
                original.tableId(),
                original.file(),
                original.minKey(),
                original.maxKey(),
                original.minSequence(),
                original.maxSequence(),
                original.entryCount(),
                original.sizeBytes() + 100
        );

        Path file = write(table.bytes());

        assertCategory(
                file,
                incorrect,
                SSTableValidator.Category.TRUNCATED_REGION);
    }
}
