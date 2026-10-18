
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.TableSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SSTableDiskIntegrationTest {

    @TempDir
    Path directory;

    private static BlockCodec.Record put(
            String key, long sequence, byte[] value) {

        return new BlockCodec.Record(
                key,
                sequence,
                BlockCodec.PUT,
                value
        );
    }

    private static BlockCodec.Record delete(
            String key, long sequence) {

        return new BlockCodec.Record(
                key,
                sequence,
                BlockCodec.DELETE,
                null
        );
    }

    private record Fixture(
            Path path,
            SSTableMeta meta,
            byte[] original
    ) {
    }

    private Fixture writeTable(
            long tableId,
            List<BlockCodec.Record> records) throws Exception {

        SSTableWriter.Result result = SSTableWriter.build(
                tableId,
                records.iterator(),
                records.size()
        );

        Path sstDirectory =
                directory.resolve("lsm").resolve("sst");

        Files.createDirectories(sstDirectory);

        Path path =
                sstDirectory.resolve(result.meta().file());

        byte[] bytes = result.bytes();

        Files.write(path, bytes);

        return new Fixture(
                path,
                result.meta(),
                bytes
        );
    }

    private static List<BlockCodec.Record> records(int count) {
        List<BlockCodec.Record> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            result.add(put(
                    String.format("key%05d", i),
                    i + 1L,
                    new byte[100]
            ));
        }

        return result;
    }

    private static TableSource open(Fixture fixture)
            throws StorageException {

        return DiskSSTableTableSource.open(
                fixture.path(),
                fixture.meta()
        );
    }

    @Test
    void writerValidatorAndDiskReaderAgree()
            throws Exception {

        Fixture fixture = writeTable(
                101,
                List.of(
                        put("alpha", 4, new byte[]{1, 2, 3}),
                        put("beta", 8, new byte[0]),
                        delete("gamma", 11)
                )
        );

        assertEquals(
                fixture.meta().sizeBytes(),
                Files.size(fixture.path())
        );

        assertDoesNotThrow(
                () -> SSTableValidator.validateFully(
                        fixture.path(),
                        fixture.meta()
                )
        );

        TableSource source = open(fixture);

        assertEquals(fixture.meta(), source.meta());

        InternalRecord alpha =
                source.get("alpha").orElseThrow();

        assertEquals("alpha", alpha.key());
        assertEquals(4L, alpha.sequence());
        assertEquals(Kind.PUT, alpha.kind());

        assertArrayEquals(
                new byte[]{1, 2, 3},
                alpha.value()
        );
    }

    @Test
    void emptyPutAndDeleteRemainDifferent()
            throws Exception {

        Fixture fixture = writeTable(
                102,
                List.of(
                        put("empty", 5, new byte[0]),
                        delete("removed", 9)
                )
        );

        TableSource source = open(fixture);

        InternalRecord empty =
                source.get("empty").orElseThrow();

        InternalRecord removed =
                source.get("removed").orElseThrow();

        assertEquals(Kind.PUT, empty.kind());
        assertFalse(empty.isTombstone());
        assertArrayEquals(new byte[0], empty.value());

        assertEquals(Kind.DELETE, removed.kind());
        assertTrue(removed.isTombstone());
    }

    @Test
    void everyRecordSurvivesMultiBlockRoundTrip()
            throws Exception {

        List<BlockCodec.Record> input = records(1000);

        Fixture fixture = writeTable(103, input);

        SSTableValidator.validateFully(
                fixture.path(),
                fixture.meta()
        );

        TableSource source = open(fixture);

        for (int i = 0; i < 1000; i++) {
            String key = String.format("key%05d", i);

            InternalRecord found =
                    source.get(key).orElseThrow();

            assertEquals(key, found.key());
            assertEquals(i + 1L, found.sequence());

            assertArrayEquals(
                    new byte[100],
                    found.value()
            );
        }

        assertEquals(1000, fixture.meta().entryCount());
    }

    @Test
    void iteratorStartsAtRequestedKeyAcrossBlocks()
            throws Exception {

        Fixture fixture = writeTable(
                104,
                records(1000)
        );

        TableSource source = open(fixture);

        Iterator<InternalRecord> iterator =
                source.iterator("key00750");

        int position = 750;
        int count = 0;

        while (iterator.hasNext()) {
            InternalRecord record = iterator.next();

            assertEquals(
                    String.format("key%05d", position),
                    record.key()
            );

            position++;
            count++;
        }

        assertEquals(250, count);
        assertEquals(1000, position);
    }

    @Test
    void pointLookupHandlesMissingKeys()
            throws Exception {

        Fixture fixture = writeTable(
                105,
                List.of(
                        put("alpha", 1, new byte[]{1}),
                        put("gamma", 2, new byte[]{2}),
                        put("omega", 3, new byte[]{3})
                )
        );

        TableSource source = open(fixture);

        assertTrue(source.get("aardvark").isEmpty());
        assertTrue(source.get("beta").isEmpty());
        assertTrue(source.get("delta").isEmpty());
        assertTrue(source.get("zebra").isEmpty());

        assertTrue(source.get("alpha").isPresent());
        assertTrue(source.get("gamma").isPresent());
        assertTrue(source.get("omega").isPresent());
    }

    @Test
    void reopeningUnmodifiedFilePreservesAllRecords()
            throws Exception {

        Fixture fixture = writeTable(
                106,
                records(100)
        );

        TableSource first = open(fixture);

        Optional<InternalRecord> before =
                first.get("key00042");

        assertTrue(before.isPresent());

        TableSource second = open(fixture);

        Optional<InternalRecord> after =
                second.get("key00042");

        assertTrue(after.isPresent());

        assertEquals(
                before.orElseThrow().key(),
                after.orElseThrow().key()
        );

        assertEquals(
                before.orElseThrow().sequence(),
                after.orElseThrow().sequence()
        );

        assertArrayEquals(
                before.orElseThrow().value(),
                after.orElseThrow().value()
        );

        assertArrayEquals(
                fixture.original(),
                Files.readAllBytes(fixture.path())
        );
    }

    @Test
    void corruptedCompleteDataBlockIsNotMissingKey()
            throws Exception {

        Fixture fixture = writeTable(
                107,
                records(100)
        );

        byte[] corrupted =
                Files.readAllBytes(fixture.path());

        corrupted[SSTableWriter.HEADER_BYTES + 25] ^= 1;

        Files.write(fixture.path(), corrupted);

        SSTableValidator.ValidationException validation =
                assertThrows(
                        SSTableValidator.ValidationException.class,
                        () -> SSTableValidator.validateFully(
                                fixture.path(),
                                fixture.meta()
                        )
                );

        assertEquals(
                SSTableValidator.Category.DATA_BLOCK,
                validation.category()
        );

        assertThrows(
                StorageException.class,
                () -> open(fixture)
        );
    }

    @Test
    void missingSstableDoesNotOpen()
            throws Exception {

        Fixture fixture = writeTable(
                108,
                records(10)
        );

        Files.delete(fixture.path());

        assertFalse(Files.exists(fixture.path()));

        assertThrows(
                Exception.class,
                () -> open(fixture)
        );
    }

    @Test
    void truncatedSstableIsRejected()
            throws Exception {

        Fixture fixture = writeTable(
                109,
                records(100)
        );

        Files.write(
                fixture.path(),
                Arrays.copyOf(
                        fixture.original(),
                        SSTableWriter.HEADER_BYTES + 3
                )
        );

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        fixture.path(),
                        fixture.meta()
                )
        );

        assertThrows(
                StorageException.class,
                () -> open(fixture)
        );
    }

    @Test
    void failedValidationDoesNotRewriteSstable()
            throws Exception {

        Fixture fixture = writeTable(
                110,
                records(100)
        );

        byte[] damaged =
                Files.readAllBytes(fixture.path());

        damaged[SSTableWriter.HEADER_BYTES + 30] ^= 0x40;

        Files.write(fixture.path(), damaged);

        byte[] before =
                Files.readAllBytes(fixture.path());

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        fixture.path(),
                        fixture.meta()
                )
        );

        byte[] after =
                Files.readAllBytes(fixture.path());

        assertArrayEquals(before, after);
    }
}
