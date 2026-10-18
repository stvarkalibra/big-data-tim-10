
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableRecoveryVerifierTest {

    @TempDir
    Path directory;

    private record Fixture(
            Path path,
            SSTableMeta meta
    ) {
    }

    private Fixture createTable(long id, String prefix)
            throws Exception {

        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            records.add(new BlockCodec.Record(
                    prefix + String.format("%04d", i),
                    i + 1L,
                    BlockCodec.PUT,
                    new byte[]{(byte) i}
            ));
        }

        SSTableWriter.Result result = SSTableWriter.build(
                id,
                records.iterator(),
                records.size()
        );

        Files.createDirectories(directory);

        Path path = directory.resolve(result.meta().file());
        Files.write(path, result.bytes());

        return new Fixture(path, result.meta());
    }

    private static SSTableValidator.ValidationException expectFailure(
            Path directory,
            List<SSTableMeta> tables) {

        return assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableRecoveryVerifier.verifyAll(
                        directory, tables)
        );
    }

    @Test
    void emptyManifestIsValid() {
        assertDoesNotThrow(
                () -> SSTableRecoveryVerifier.verifyAll(
                        directory, List.of())
        );
    }

    @Test
    void validatesOnePublishedTable() throws Exception {
        Fixture table = createTable(1, "alpha");

        assertDoesNotThrow(
                () -> SSTableRecoveryVerifier.verifyAll(
                        directory,
                        List.of(table.meta())
                )
        );
    }

    @Test
    void validatesMultiplePublishedTables() throws Exception {
        Fixture first = createTable(1, "alpha");
        Fixture second = createTable(2, "beta");
        Fixture third = createTable(3, "gamma");

        assertDoesNotThrow(
                () -> SSTableRecoveryVerifier.verifyAll(
                        directory,
                        List.of(
                                first.meta(),
                                second.meta(),
                                third.meta()
                        )
                )
        );
    }

    @Test
    void ignoresUnreferencedFiles() throws Exception {
        Fixture published = createTable(1, "alpha");

        // An orphan or temporary file must not be interpreted
        // as a published table.
        Path unreferenced =
                directory.resolve("sst-00000000000000000099.sst");

        Files.write(unreferenced, new byte[]{1, 2, 3});

        assertDoesNotThrow(
                () -> SSTableRecoveryVerifier.verifyAll(
                        directory,
                        List.of(published.meta())
                )
        );

        assertTrue(Files.exists(unreferenced));
    }

    @Test
    void missingPublishedTableFailsRecovery() throws Exception {
        Fixture fixture = createTable(1, "alpha");

        Files.delete(fixture.path());

        SSTableValidator.ValidationException error =
                expectFailure(
                        directory,
                        List.of(fixture.meta())
                );

        assertEquals(
                SSTableValidator.Category.TRUNCATED_REGION,
                error.category()
        );
    }

    @Test
    void corruptionInSecondTableFailsEntireVerification()
            throws Exception {

        Fixture first = createTable(1, "alpha");
        Fixture second = createTable(2, "beta");

        byte[] corrupted = Files.readAllBytes(second.path());
        corrupted[SSTableWriter.HEADER_BYTES + 25] ^= 1;
        Files.write(second.path(), corrupted);

        SSTableValidator.ValidationException error =
                expectFailure(
                        directory,
                        List.of(first.meta(), second.meta())
                );

        assertEquals(
                SSTableValidator.Category.DATA_BLOCK,
                error.category()
        );
    }

    @Test
    void rejectsDuplicateTableIds() throws Exception {
        Fixture fixture = createTable(1, "alpha");

        SSTableValidator.ValidationException error =
                expectFailure(
                        directory,
                        List.of(fixture.meta(), fixture.meta())
                );

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                error.category()
        );
    }

    @Test
    void rejectsNonCanonicalFilename() throws Exception {
        Fixture fixture = createTable(1, "alpha");
        SSTableMeta original = fixture.meta();

        SSTableMeta invalid = new SSTableMeta(
                original.tableId(),
                "other-file.sst",
                original.minKey(),
                original.maxKey(),
                original.minSequence(),
                original.maxSequence(),
                original.entryCount(),
                original.sizeBytes()
        );

        SSTableValidator.ValidationException error =
                expectFailure(
                        directory,
                        List.of(invalid)
                );

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                error.category()
        );
    }

    @Test
    void rejectsPathTraversalInManifest() throws Exception {
        Fixture fixture = createTable(1, "alpha");
        SSTableMeta original = fixture.meta();

        SSTableMeta invalid = new SSTableMeta(
                original.tableId(),
                "../outside.sst",
                original.minKey(),
                original.maxKey(),
                original.minSequence(),
                original.maxSequence(),
                original.entryCount(),
                original.sizeBytes()
        );

        SSTableValidator.ValidationException error =
                expectFailure(
                        directory,
                        List.of(invalid)
                );

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                error.category()
        );
    }

    @Test
    void rejectsManifestMetadataMismatch() throws Exception {
        Fixture fixture = createTable(1, "alpha");
        SSTableMeta original = fixture.meta();

        SSTableMeta invalid = new SSTableMeta(
                original.tableId(),
                original.file(),
                original.minKey(),
                original.maxKey(),
                original.minSequence(),
                original.maxSequence(),
                original.entryCount() + 1,
                original.sizeBytes()
        );

        SSTableValidator.ValidationException error =
                expectFailure(
                        directory,
                        List.of(invalid)
                );

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                error.category()
        );
    }

    @Test
    void validationNeverModifiesFiles() throws Exception {
        Fixture fixture = createTable(1, "alpha");

        byte[] before = Files.readAllBytes(fixture.path());

        SSTableRecoveryVerifier.verifyAll(
                directory,
                List.of(fixture.meta())
        );

        assertArrayEquals(
                before,
                Files.readAllBytes(fixture.path())
        );
    }

    @Test
    void rejectsNullManifestEntry() throws Exception {
        List<SSTableMeta> tables = new ArrayList<>();
        tables.add(null);

        SSTableValidator.ValidationException error =
                expectFailure(directory, tables);

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                error.category()
        );
    }
}
