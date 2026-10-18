
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableReaderTest {

    private BlockCodec.Record put(String key, long seq, String value) {
        return new BlockCodec.Record(
                key, seq, BlockCodec.PUT,
                value.getBytes(StandardCharsets.UTF_8));
    }

    private SSTableWriter.Result build(List<BlockCodec.Record> records) {
        return SSTableWriter.build(1, records.iterator(), records.size());
    }

    private SSTableReader open(List<BlockCodec.Record> records) {
        var result = build(records);
        return SSTableReader.open(result.bytes(), result.meta());
    }

    @Test
    void readsExistingKey() {
        var reader = open(List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B"),
                put("zika", 3, "C")
        ));

        assertArrayEquals(
                "B".getBytes(StandardCharsets.UTF_8),
                reader.get("kimi").orElseThrow().value());
    }

    @Test
    void returnsEmptyForMissingKeys() {
        var reader = open(List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B")
        ));

        assertTrue(reader.get("marko").isEmpty());
        assertTrue(reader.get("aaa").isEmpty());
        assertTrue(reader.get("zzzz").isEmpty());
    }

    @Test
    void preservesTombstones() {
        var reader = open(List.of(
                new BlockCodec.Record(
                        "deleted", 5, BlockCodec.DELETE, null),
                put("kimi", 6, "A")
        ));

        var record = reader.get("deleted").orElseThrow();

        assertTrue(record.tombstone());
        assertNull(record.value());
    }

    @Test
    void distinguishesEmptyValueFromDelete() {
        var reader = open(List.of(
                new BlockCodec.Record(
                        "empty", 1, BlockCodec.PUT, new byte[0]),
                new BlockCodec.Record(
                        "removed", 2, BlockCodec.DELETE, null)
        ));

        assertFalse(reader.get("empty").orElseThrow().tombstone());
        assertEquals(0, reader.get("empty").orElseThrow().value().length);
        assertTrue(reader.get("removed").orElseThrow().tombstone());
    }

    @Test
    void scansFromRequestedKey() {
        var reader = open(List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B"),
                put("matej", 3, "C"),
                put("zika", 4, "D")
        ));

        var result = reader.scan("kimi");

        assertEquals(3, result.size());
        assertEquals("kimi", result.get(0).key());
        assertEquals("zika", result.get(2).key());
    }

    @Test
    void readsAcrossMultipleBlocks() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%05d", i),
                    i + 1,
                    BlockCodec.PUT,
                    new byte[100]
            ));
        }

        var reader = open(records);

        assertTrue(reader.blockCount() > 1);
        assertEquals(200, reader.scan("key00000").size());

        assertTrue(reader.get("key00000").isPresent());
        assertTrue(reader.get("key00100").isPresent());
        assertTrue(reader.get("key00199").isPresent());
    }

    @Test
    void rejectsCorruptedHeader() {
        var result = build(List.of(put("kimi", 1, "A")));

        byte[] bytes = result.bytes();
        bytes[0] ^= 1;

        assertThrows(IllegalArgumentException.class,
                () -> SSTableReader.open(bytes, result.meta()));
    }

    @Test
    void rejectsCorruptedFooter() {
        var result = build(List.of(put("kimi", 1, "A")));

        byte[] bytes = result.bytes();
        bytes[bytes.length - 10] ^= 1;

        assertThrows(IllegalArgumentException.class,
                () -> SSTableReader.open(bytes, result.meta()));
    }

    @Test
    void rejectsCorruptedDataBlock() {
        var result = build(List.of(put("kimi", 1, "A")));

        byte[] bytes = result.bytes();
        bytes[25] ^= 1;

        assertThrows(IllegalArgumentException.class,
                () -> SSTableReader.open(bytes, result.meta()));
    }

    @Test
    void rejectsWrongManifestMetadata() {
        var result = build(List.of(put("kimi", 1, "A")));

        SSTableMeta wrong = new SSTableMeta(
                1,
                result.meta().file(),
                "anna",
                result.meta().maxKey(),
                result.meta().minSequence(),
                result.meta().maxSequence(),
                result.meta().entryCount(),
                result.meta().sizeBytes()
        );

        assertThrows(IllegalArgumentException.class,
                () -> SSTableReader.open(result.bytes(), wrong));
    }
}
