
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTablePublishedCorruptionScenarioTest {

    @TempDir
    Path directory;

    private record Fixture(
            Path path,
            SSTableMeta meta
    ) {
    }

    private Fixture createTable(long id)
            throws Exception {

        List<BlockCodec.Record> records =
                new ArrayList<>();

        for (int i = 0; i < 150; i++) {
            records.add(
                    new BlockCodec.Record(
                            String.format("key%05d", i),
                            i + 1L,
                            BlockCodec.PUT,
                            new byte[100]
                    )
            );
        }

        SSTableWriter.Result result =
                SSTableWriter.build(
                        id,
                        records.iterator(),
                        records.size()
                );

        Path path = directory.resolve(
                result.meta().file()
        );

        Files.write(path, result.bytes());

        return new Fixture(
                path,
                result.meta()
        );
    }

    private static SSTableValidator.ValidationException
    expectRejected(
            Path directory,
            List<SSTableMeta> published) {

        return assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableRecoveryVerifier.verifyAll(
                        directory, published
                )
        );
    }

    private static void changeByte(
            Path file,
            int offset) throws Exception {

        byte[] bytes = Files.readAllBytes(file);

        assertTrue(offset >= 0);
        assertTrue(offset < bytes.length);

        bytes[offset] ^= 0x01;

        Files.write(file, bytes);
    }

    private static int regionOffset(
            Path file,
            int footerFieldOffset) throws Exception {

        byte[] bytes = Files.readAllBytes(file);

        int footerStart =
                bytes.length - SSTableWriter.FOOTER_BYTES;

        long value = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong(footerStart + footerFieldOffset);

        return Math.toIntExact(value);
    }

    @Test
    void allReferencedHealthyTablesAreAccepted()
            throws Exception {

        Fixture first = createTable(1);
        Fixture second = createTable(2);

        assertDoesNotThrow(
                () -> SSTableRecoveryVerifier.verifyAll(
                        directory,
                        List.of(first.meta(), second.meta())
                )
        );
    }

    @Test
    void corruptionInDataBlockRejectsEntireRecovery()
            throws Exception {

        Fixture first = createTable(3);
        Fixture second = createTable(4);

        changeByte(
                second.path(),
                SSTableWriter.HEADER_BYTES + 25
        );

        var error = expectRejected(
                directory,
                List.of(first.meta(), second.meta())
        );

        assertEquals(
                SSTableValidator.Category.DATA_BLOCK,
                error.category()
        );

        assertTrue(Files.exists(first.path()));
        assertTrue(Files.exists(second.path()));
    }

    @Test
    void corruptSparseIndexRejectsRecovery()
            throws Exception {

        Fixture fixture = createTable(5);

        // Footer offset 32 contains indexOffset.
        int indexOffset = regionOffset(
                fixture.path(), 32
        );

        changeByte(fixture.path(), indexOffset);

        var error = expectRejected(
                directory,
                List.of(fixture.meta())
        );

        assertEquals(
                SSTableValidator.Category.INDEX,
                error.category()
        );
    }

    @Test
    void corruptBloomRegionRejectsRecovery()
            throws Exception {

        Fixture fixture = createTable(6);

        // Footer offset 48 contains bloomOffset.
        int bloomOffset = regionOffset(
                fixture.path(), 48
        );

        changeByte(fixture.path(), bloomOffset);

        var error = expectRejected(
                directory,
                List.of(fixture.meta())
        );

        assertEquals(
                SSTableValidator.Category.BLOOM,
                error.category()
        );
    }

    @Test
    void corruptFooterRejectsRecovery()
            throws Exception {

        Fixture fixture = createTable(7);

        byte[] bytes =
                Files.readAllBytes(fixture.path());

        changeByte(
                fixture.path(),
                bytes.length - 1
        );

        var error = expectRejected(
                directory,
                List.of(fixture.meta())
        );

        assertEquals(
                SSTableValidator.Category.FOOTER,
                error.category()
        );
    }

    @Test
    void truncatedPublishedTableRejectsRecovery()
            throws Exception {

        Fixture fixture = createTable(8);

        byte[] bytes =
                Files.readAllBytes(fixture.path());

        Files.write(
                fixture.path(),
                java.util.Arrays.copyOf(
                        bytes,
                        bytes.length - 40
                )
        );

        var error = expectRejected(
                directory,
                List.of(fixture.meta())
        );

        assertEquals(
                SSTableValidator.Category.TRUNCATED_REGION,
                error.category()
        );
    }

    @Test
    void missingPublishedFileRejectsRecovery()
            throws Exception {

        Fixture fixture = createTable(9);

        Files.delete(fixture.path());

        var error = expectRejected(
                directory,
                List.of(fixture.meta())
        );

        assertEquals(
                SSTableValidator.Category.TRUNCATED_REGION,
                error.category()
        );
    }

    @Test
    void manifestMetadataMismatchRejectsRecovery()
            throws Exception {

        Fixture fixture = createTable(10);

        SSTableMeta original = fixture.meta();

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

        var error = expectRejected(
                directory,
                List.of(incorrect)
        );

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                error.category()
        );
    }

    @Test
    void orphanDoesNotBecomePublishedAutomatically()
            throws Exception {

        Fixture published = createTable(11);
        Fixture orphan = createTable(12);

        // Even if an orphan is corrupted, the verifier
        // must only trust the supplied published table list.
        changeByte(
                orphan.path(),
                SSTableWriter.HEADER_BYTES + 25
        );

        assertDoesNotThrow(
                () -> SSTableRecoveryVerifier.verifyAll(
                        directory,
                        List.of(published.meta())
                )
        );

        assertTrue(Files.exists(orphan.path()));
    }

    @Test
    void duplicateManifestReferenceIsRejected()
            throws Exception {

        Fixture fixture = createTable(13);

        var error = expectRejected(
                directory,
                List.of(
                        fixture.meta(),
                        fixture.meta()
                )
        );

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                error.category()
        );
    }
}
