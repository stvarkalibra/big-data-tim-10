
package rs.raf.dlsm.inspect;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.sstable.SSTableWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InspectSSTableDetailsTest {

    private static BlockCodec.Record put(
            String key, long seq, byte[] value) {
        return new BlockCodec.Record(
                key, seq, BlockCodec.PUT, value);
    }

    private static SSTableWriter.Result table(int count) {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            records.add(put(
                    String.format("key%05d", i),
                    i + 1L,
                    new byte[100]
            ));
        }

        return SSTableWriter.build(
                7, records.iterator(), records.size());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(
            Map<String, Object> result, String name) {
        return (List<Map<String, Object>>) result.get(name);
    }

    @Test
    void reportsFormatAndChecksumStatuses() {
        var table = table(10);
        var result = InspectSSTable.inspect(
                table.bytes(), table.meta());

        assertEquals("SST1", result.get("format"));
        assertEquals(1, result.get("version"));
        assertEquals(7L, result.get("tableId"));
        assertEquals(true, result.get("valid"));

        assertEquals("NOT_PRESENT",
                result.get("headerCrcStatus"));
        assertEquals("VALID",
                result.get("footerCrcStatus"));
        assertEquals("VALID",
                result.get("indexCrcStatus"));
        assertEquals("VALID",
                result.get("bloomCrcStatus"));
    }

    @Test
    void reportsBlocksAndSparseIndex() {
        var table = table(300);

        var result = InspectSSTable.inspect(
                table.bytes(), table.meta());

        var blocks = items(result, "blocks");
        var index = items(result, "sparseIndex");

        assertTrue(blocks.size() > 1);
        assertEquals(blocks.size(), index.size());
        assertEquals(blocks.size(), result.get("blockCount"));

        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            var entry = index.get(i);

            assertEquals(entry.get("firstKey"),
                    block.get("firstKey"));
            assertEquals(entry.get("offset"),
                    block.get("offset"));
            assertEquals(entry.get("length"),
                    block.get("length"));

            assertEquals("VALID", block.get("crcStatus"));
            assertTrue((int) block.get("recordCount") > 0);
        }
    }

    @Test
    void blockOffsetsAreContiguous() {
        var table = table(300);

        var result = InspectSSTable.inspect(
                table.bytes(), table.meta());

        var blocks = items(result, "blocks");

        for (int i = 1; i < blocks.size(); i++) {
            var previous = blocks.get(i - 1);
            var current = blocks.get(i);

            long previousEnd =
                    ((Number) previous.get("offset")).longValue()
                            + ((Number) previous.get("length")).longValue();

            assertEquals(
                    previousEnd,
                    ((Number) current.get("offset")).longValue());
        }
    }

    @Test
    void reportsAllRecordsInKeyOrder() {
        var table = table(300);

        var result = InspectSSTable.inspect(
                table.bytes(), table.meta());

        var records = items(result, "records");

        assertEquals(300, records.size());

        for (int i = 0; i < 300; i++) {
            assertEquals(
                    String.format("key%05d", i),
                    records.get(i).get("key"));

            assertEquals((long) i + 1,
                    records.get(i).get("sequence"));

            assertEquals("PUT", records.get(i).get("kind"));
        }
    }

    @Test
    void distinguishesTombstoneAndEmptyPut() {
        var records = List.of(
                new BlockCodec.Record(
                        "empty", 1,
                        BlockCodec.PUT, new byte[0]),
                new BlockCodec.Record(
                        "removed", 2,
                        BlockCodec.DELETE, null)
        );

        var table = SSTableWriter.build(
                9, records.iterator(), records.size());

        var result = InspectSSTable.inspect(
                table.bytes(), table.meta());

        var inspected = items(result, "records");

        assertEquals("PUT", inspected.get(0).get("kind"));
        assertEquals(0, inspected.get(0).get("valueLength"));

        assertEquals("DELETE", inspected.get(1).get("kind"));
        assertNull(inspected.get(1).get("valueLength"));
    }

    @Test
    void rejectsCorruptionInsteadOfHidingIt() {
        var table = table(10);
        byte[] corrupted = table.bytes();

        corrupted[25] ^= 1;

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> InspectSSTable.inspect(
                        corrupted, table.meta()));
    }

    @Test
    void doesNotModifyInput() {
        var table = table(10);
        byte[] original = table.bytes();
        byte[] before = original.clone();

        InspectSSTable.inspect(original, table.meta());

        assertArrayEquals(before, original);
    }
}
