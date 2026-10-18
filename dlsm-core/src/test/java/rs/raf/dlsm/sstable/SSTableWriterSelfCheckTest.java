
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableWriterSelfCheckTest {

    private static BlockCodec.Record put(
            String key,
            long sequence,
            byte[] value) {

        return new BlockCodec.Record(
                key,
                sequence,
                BlockCodec.PUT,
                value
        );
    }

    private static BlockCodec.Record delete(
            String key,
            long sequence) {

        return new BlockCodec.Record(
                key,
                sequence,
                BlockCodec.DELETE,
                null
        );
    }

    @Test
    void generatedTablePassesProductionReaderValidation() {
        List<BlockCodec.Record> records = List.of(
                put("alpha", 3,
                        "A".getBytes(StandardCharsets.UTF_8)),
                delete("beta", 8),
                put("gamma", 5, new byte[0])
        );

        SSTableWriter.Result result = SSTableWriter.build(
                101, records.iterator(), records.size());

        SSTableReader reader = assertDoesNotThrow(
                () -> SSTableReader.open(
                        result.bytes(),
                        result.meta()
                )
        );

        assertEquals(3, result.meta().entryCount());
        assertEquals("alpha", result.meta().minKey());
        assertEquals("gamma", result.meta().maxKey());
        assertEquals(3, result.meta().minSequence());
        assertEquals(8, result.meta().maxSequence());

        assertArrayEquals(
                "A".getBytes(StandardCharsets.UTF_8),
                reader.get("alpha").orElseThrow().value()
        );

        assertTrue(
                reader.get("beta").orElseThrow().tombstone()
        );

        BlockCodec.Record empty =
                reader.get("gamma").orElseThrow();

        assertFalse(empty.tombstone());
        assertArrayEquals(new byte[0], empty.value());
    }

    @Test
    void selfCheckAcceptsMultiBlockTable() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 500; i++) {
            records.add(put(
                    String.format("key%05d", i),
                    i + 1L,
                    new byte[100]
            ));
        }

        SSTableWriter.Result result = SSTableWriter.build(
                102,
                records.iterator(),
                records.size()
        );

        SSTableReader reader = SSTableReader.open(
                result.bytes(), result.meta());

        assertTrue(reader.blockCount() > 1);
        assertEquals(500, result.meta().entryCount());

        for (int i : new int[]{0, 1, 99, 250, 499}) {
            String key = String.format("key%05d", i);

            assertEquals(
                    i + 1L,
                    reader.get(key).orElseThrow().sequence()
            );
        }
    }

    @Test
    void selfCheckAcceptsMaximumSizedValue() {
        byte[] value = new byte[BlockCodec.MAX_VALUE_BYTES];

        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) i;
        }

        SSTableWriter.Result result = SSTableWriter.build(
                103,
                List.of(put("large", 9, value)).iterator(),
                1
        );

        SSTableReader reader = SSTableReader.open(
                result.bytes(), result.meta());

        assertArrayEquals(
                value,
                reader.get("large").orElseThrow().value()
        );
    }

    @Test
    void rejectsUnsortedRecordsBeforeReturningResult() {
        List<BlockCodec.Record> records = List.of(
                put("zeta", 1, new byte[]{1}),
                put("alpha", 2, new byte[]{2})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableWriter.build(
                        104, records.iterator(), 2)
        );
    }

    @Test
    void rejectsDuplicateKeysBeforeReturningResult() {
        List<BlockCodec.Record> records = List.of(
                put("alpha", 1, new byte[]{1}),
                put("alpha", 2, new byte[]{2})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableWriter.build(
                        105, records.iterator(), 2)
        );
    }

    @Test
    void rejectsIncorrectExpectedRecordCount() {
        List<BlockCodec.Record> records = List.of(
                put("alpha", 1, new byte[]{1}),
                put("beta", 2, new byte[]{2})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableWriter.build(
                        106, records.iterator(), 3)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableWriter.build(
                        106, records.iterator(), 1)
        );
    }

    @Test
    void returnedBytesAreDefensivelyCopied() {
        SSTableWriter.Result result = SSTableWriter.build(
                107,
                List.of(put("alpha", 1, new byte[]{42}))
                        .iterator(),
                1
        );

        byte[] changed = result.bytes();
        changed[0] ^= 0x7F;

        assertDoesNotThrow(() -> SSTableReader.open(
                result.bytes(),
                result.meta()
        ));
    }

    @Test
    void generatedTableIsDeterministic() {
        List<BlockCodec.Record> records = List.of(
                put("alpha", 1, new byte[]{1}),
                delete("beta", 2),
                put("gamma", 3, new byte[0])
        );

        SSTableWriter.Result first = SSTableWriter.build(
                108, records.iterator(), records.size());

        SSTableWriter.Result second = SSTableWriter.build(
                108, records.iterator(), records.size());

        assertEquals(first.meta(), second.meta());
        assertArrayEquals(first.bytes(), second.bytes());
    }
}
