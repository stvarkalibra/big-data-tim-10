
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SSTableHardeningTest {

    @TempDir
    Path directory;

    private void validate(byte[] bytes, SSTableMeta expected) {
        try {
            Path file = Files.createTempFile(
                    directory, "sst-", ".sst");
            Files.write(file, bytes);
            SSTableValidator.validateFully(file, expected);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BlockCodec.Record put(
            String key, long sequence, byte[] value) {

        return new BlockCodec.Record(
                key, sequence, BlockCodec.PUT, value);
    }

    private static BlockCodec.Record delete(
            String key, long sequence) {

        return new BlockCodec.Record(
                key, sequence, BlockCodec.DELETE, null);
    }

    private static SSTableWriter.Result build(
            List<BlockCodec.Record> records) {

        return SSTableWriter.build(
                42, records.iterator(), records.size());
    }

    private static SSTableReader reader(
            SSTableWriter.Result result) {

        return SSTableReader.open(
                result.bytes(), result.meta());
    }

    @Test
    void readsFirstMiddleLastAndMissingKeys() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 300; i++) {
            records.add(put(
                    String.format("k%04d", i),
                    i + 1,
                    new byte[]{(byte) i, (byte) (i >>> 8)}
            ));
        }

        SSTableReader reader = reader(build(records));

        for (int i : new int[]{0, 150, 299}) {
            String key = String.format("k%04d", i);

            assertArrayEquals(
                    new byte[]{(byte) i, (byte) (i >>> 8)},
                    reader.get(key).orElseThrow().value());
        }

        assertTrue(reader.get("k0150x").isEmpty());
        assertTrue(reader.get("a0000").isEmpty());
        assertTrue(reader.get("z9999").isEmpty());
    }

    @Test
    void readsLargeBinaryValuesWithoutModification() {
        byte[] value = new byte[256 * 1024];
        new Random(12345).nextBytes(value);

        List<BlockCodec.Record> records = List.of(
                put("alpha", 1, new byte[]{1}),
                put("large", 2, value),
                put("omega", 3, new byte[]{2})
        );

        SSTableReader reader = reader(build(records));

        assertArrayEquals(
                value,
                reader.get("large").orElseThrow().value());

        assertArrayEquals(
                new byte[]{2},
                reader.get("omega").orElseThrow().value());
    }

    @Test
    void keepsEmptyPutDifferentFromTombstone() {
        SSTableReader reader = reader(build(List.of(
                put("empty", 1, new byte[0]),
                delete("removed", 2)
        )));

        var empty = reader.get("empty").orElseThrow();
        var removed = reader.get("removed").orElseThrow();

        assertFalse(empty.tombstone());
        assertNotNull(empty.value());
        assertEquals(0, empty.value().length);

        assertTrue(removed.tombstone());
        assertNull(removed.value());
    }

    @Test
    void rejectsDuplicateAndDescendingKeys() {
        assertThrows(IllegalArgumentException.class, () ->
                build(List.of(
                        put("alpha", 1, new byte[]{1}),
                        put("alpha", 2, new byte[]{2})
                )));

        assertThrows(IllegalArgumentException.class, () ->
                build(List.of(
                        put("zeta", 1, new byte[]{1}),
                        put("alpha", 2, new byte[]{2})
                )));
    }

    @Test
    void readsLargeTableAcrossManyBlocks() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 10_000; i++) {
            records.add(put(
                    String.format("k%05d", i),
                    i + 1L,
                    new byte[100]
            ));
        }

        SSTableWriter.Result table = build(records);
        SSTableReader reader = reader(table);

        assertTrue(reader.blockCount() >= 100);

        for (int i : new int[]{
                0, 1, 2500, 5000, 7500, 9999}) {

            assertTrue(reader.get(
                    String.format("k%05d", i)).isPresent());
        }

        assertEquals(10_000, table.meta().entryCount());
    }

    @Test
    void iteratorStartsNearEndAndReturnsCorrectOrder() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 1_000; i++) {
            records.add(put(
                    String.format("k%04d", i),
                    i + 1L,
                    new byte[100]
            ));
        }

        SSTableWriter.Result table = build(records);

        try (SSTableIterator iterator =
                     new SSTableIterator(
                             table.bytes(),
                             table.meta(),
                             "k0990")) {

            int expected = 990;

            while (iterator.hasNext()) {
                assertEquals(
                        String.format("k%04d", expected),
                        iterator.next().key());

                expected++;
            }

            assertEquals(1000, expected);
        }
    }

    @Test
    void detectsCorruptionInDifferentDataBlocks() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 300; i++) {
            records.add(put(
                    String.format("k%04d", i),
                    i + 1L,
                    new byte[100]
            ));
        }

        SSTableWriter.Result table = build(records);
        byte[] original = table.bytes();

        int[] positions = {
                SSTableWriter.HEADER_BYTES + 30,
                SSTableWriter.HEADER_BYTES + 500,
                SSTableWriter.HEADER_BYTES + 1000
        };

        for (int position : positions) {
            byte[] corrupted = original.clone();
            corrupted[position] ^= 0x01;

            assertThrows(IllegalArgumentException.class,
                    () -> validate(corrupted, table.meta()));
        }
    }

    @Test
    void rejectsTruncationAtMultiplePositions() {
        SSTableWriter.Result table = build(List.of(
                put("alpha", 1, new byte[100]),
                put("omega", 2, new byte[100])
        ));

        byte[] original = table.bytes();

        int[] lengths = {
                0,
                1,
                SSTableWriter.HEADER_BYTES,
                original.length / 2,
                original.length - 1
        };

        for (int length : lengths) {
            byte[] truncated = Arrays.copyOf(original, length);

            assertThrows(IllegalArgumentException.class,
                    () -> validate(truncated, table.meta()));
        }
    }

    @Test
    void validatesSequenceRangeWithNonMonotonicSequences() {
        SSTableWriter.Result table = build(List.of(
                put("alpha", 500, new byte[]{1}),
                put("beta", 10, new byte[]{2}),
                put("gamma", 250, new byte[]{3})
        ));

        assertEquals(10, table.meta().minSequence());
        assertEquals(500, table.meta().maxSequence());

        assertDoesNotThrow(() ->
                validate(table.bytes(), table.meta()));
    }

    @Test
    void producesIdenticalBytesForIdenticalInput() {
        List<BlockCodec.Record> records = List.of(
                put("alpha", 1, new byte[]{0, 1, 2}),
                delete("beta", 2),
                put("gamma", 3, new byte[]{3, 4, 5})
        );

        SSTableWriter.Result first = build(records);
        SSTableWriter.Result second = build(records);

        assertArrayEquals(first.bytes(), second.bytes());
        assertEquals(first.meta(), second.meta());
    }
}
