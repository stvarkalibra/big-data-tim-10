
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockCodecTest {

    private static BlockCodec.Record put(
            String key, long seq, String value) {
        return new BlockCodec.Record(
                key, seq, BlockCodec.PUT,
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void putRoundTrip() {
        List<BlockCodec.Record> input = List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B"),
                put("zika", 3, "C")
        );

        List<BlockCodec.Record> restored =
                BlockCodec.decode(BlockCodec.encode(input));

        assertEquals(3, restored.size());
        assertEquals("kimi", restored.get(1).key());
        assertEquals(2, restored.get(1).sequence());
        assertArrayEquals("B".getBytes(), restored.get(1).value());
    }

    @Test
    void deleteRoundTrip() {
        BlockCodec.Record tombstone = new BlockCodec.Record(
                "kimi", 10, BlockCodec.DELETE, null);

        List<BlockCodec.Record> restored =
                BlockCodec.decode(BlockCodec.encode(List.of(tombstone)));

        assertTrue(restored.get(0).tombstone());
        assertNull(restored.get(0).value());
        assertEquals(10, restored.get(0).sequence());
    }

    @Test
    void emptyPutIsNotDelete() {
        BlockCodec.Record record = new BlockCodec.Record(
                "kimi", 1, BlockCodec.PUT, new byte[0]);

        BlockCodec.Record restored = BlockCodec.decode(
                BlockCodec.encode(List.of(record))).get(0);

        assertFalse(restored.tombstone());
        assertNotNull(restored.value());
        assertEquals(0, restored.value().length);
    }

    @Test
    void binaryValuesRoundTrip() {
        byte[] value = {0, -1, 50, 0, 127};

        BlockCodec.Record record = new BlockCodec.Record(
                "binary", 5, BlockCodec.PUT, value);

        byte[] encoded = BlockCodec.encode(List.of(record));
        BlockCodec.Record restored =
                BlockCodec.decode(encoded).get(0);

        assertArrayEquals(value, restored.value());
    }

    @Test
    void recordValueMustBeDefensivelyCopied() {
        byte[] value = {1, 2, 3};

        BlockCodec.Record record = new BlockCodec.Record(
                "kimi", 1, BlockCodec.PUT, value);

        value[0] = 99;

        assertEquals(1, record.value()[0]);

        byte[] returned = record.value();
        returned[0] = 55;

        assertEquals(1, record.value()[0]);
    }

    @Test
    void rejectsUnsortedKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockCodec.encode(List.of(
                        put("zika", 1, "A"),
                        put("anna", 2, "B")
                )));
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockCodec.encode(List.of(
                        put("kimi", 1, "A"),
                        put("kimi", 2, "B")
                )));
    }

    @Test
    void rejectsInvalidRecords() {
        assertThrows(IllegalArgumentException.class,
                () -> new BlockCodec.Record(
                        "", 1, BlockCodec.PUT, new byte[0]));

        assertThrows(IllegalArgumentException.class,
                () -> new BlockCodec.Record(
                        "kimi", 0, BlockCodec.PUT, new byte[0]));

        assertThrows(IllegalArgumentException.class,
                () -> new BlockCodec.Record(
                        "kimi", 1, BlockCodec.PUT, null));

        assertThrows(IllegalArgumentException.class,
                () -> new BlockCodec.Record(
                        "kimi", 1, BlockCodec.DELETE, new byte[0]));
    }

    @Test
    void corruptedBlockMustBeRejected() {
        byte[] encoded = BlockCodec.encode(
                List.of(put("kimi", 1, "test")));

        encoded[20] ^= 0x01;

        assertThrows(IllegalArgumentException.class,
                () -> BlockCodec.decode(encoded));
    }

    @Test
    void truncatedBlockMustBeRejected() {
        byte[] encoded = BlockCodec.encode(
                List.of(put("kimi", 1, "test")));

        byte[] truncated = Arrays.copyOf(
                encoded, encoded.length - 1);

        assertThrows(IllegalArgumentException.class,
                () -> BlockCodec.decode(truncated));
    }

    @Test
    void deterministicEncoding() {
        List<BlockCodec.Record> input = List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B")
        );

        assertArrayEquals(
                BlockCodec.encode(input),
                BlockCodec.encode(input));
    }

    @Test
    void acceptsMaximumValue() {
        byte[] value = new byte[BlockCodec.MAX_VALUE_BYTES];
        Arrays.fill(value, (byte) 42);

        BlockCodec.Record record = new BlockCodec.Record(
                "large", 15, BlockCodec.PUT, value);

        byte[] encoded = BlockCodec.encode(List.of(record));

        assertArrayEquals(
                value,
                BlockCodec.decode(encoded).get(0).value());
    }

    @Test
    void rejectsOversizedValue() {
        byte[] value =
                new byte[BlockCodec.MAX_VALUE_BYTES + 1];

        assertThrows(IllegalArgumentException.class,
                () -> new BlockCodec.Record(
                        "large", 1, BlockCodec.PUT, value));
    }

    @Test
    void oversizedRecordGetsOwnBlock() {
        byte[] value = new byte[5000];

        BlockCodec.Record record = new BlockCodec.Record(
                "large", 1, BlockCodec.PUT, value);

        assertTrue(BlockCodec.fitsTarget(0, record));
        assertFalse(BlockCodec.fitsTarget(100, record));
    }

    @Test
    void returnsImmutableDecodedList() {
        byte[] encoded = BlockCodec.encode(
                List.of(put("kimi", 1, "test")));

        List<BlockCodec.Record> restored =
                BlockCodec.decode(encoded);

        assertThrows(UnsupportedOperationException.class,
                () -> restored.clear());
    }
}
