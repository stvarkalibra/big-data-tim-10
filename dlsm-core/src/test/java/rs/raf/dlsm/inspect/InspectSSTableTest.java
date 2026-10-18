package rs.raf.dlsm.inspect;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.SSTableWriter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InspectSSTableTest {

    @Test
    void inspectsValidTable() {
        var records = List.of(
                new BlockCodec.Record(
                        "anna", 1, BlockCodec.PUT,
                        "A".getBytes(StandardCharsets.UTF_8)),
                new BlockCodec.Record(
                        "kimi", 2, BlockCodec.PUT,
                        "B".getBytes(StandardCharsets.UTF_8))
        );

        var table = SSTableWriter.build(
                1, records.iterator(), records.size());

        Map<String, Object> result =
                InspectSSTable.inspect(table.bytes(), table.meta());

        assertEquals(true, result.get("valid"));
        assertEquals(1L, result.get("tableId"));
        assertEquals(2L, result.get("entryCount"));
        assertEquals("anna", result.get("minKey"));
        assertEquals("kimi", result.get("maxKey"));
    }

    @Test
    void inspectorPreservesTombstones() {
        var records = List.of(
                new BlockCodec.Record(
                        "deleted", 10, BlockCodec.DELETE, null),
                new BlockCodec.Record(
                        "kimi", 11, BlockCodec.PUT, new byte[0])
        );

        var table = SSTableWriter.build(
                2, records.iterator(), records.size());

        var result = InspectSSTable.inspect(
                table.bytes(), table.meta());

        @SuppressWarnings("unchecked")
        var inspectedRecords =
                (List<Map<String, Object>>) result.get("records");

        assertEquals("DELETE", inspectedRecords.get(0).get("kind"));
        assertNull(inspectedRecords.get(0).get("valueLength"));

        assertEquals("PUT", inspectedRecords.get(1).get("kind"));
        assertEquals(0, inspectedRecords.get(1).get("valueLength"));
    }

    @Test
    void rejectsCorruptedTable() {
        var records = List.of(
                new BlockCodec.Record(
                        "kimi", 1, BlockCodec.PUT, new byte[]{1, 2, 3})
        );

        var table = SSTableWriter.build(
                3, records.iterator(), records.size());

        byte[] corrupted = table.bytes();
        corrupted[25] ^= 1;

        assertThrows(
                SSTableValidatorExceptionPlaceholder.class,
                () -> {
                    try {
                        InspectSSTable.inspect(corrupted, table.meta());
                    } catch (IllegalArgumentException e) {
                        throw new SSTableValidatorExceptionPlaceholder(e);
                    }
                });
    }

    private static final class SSTableValidatorExceptionPlaceholder
            extends RuntimeException {

        private SSTableValidatorExceptionPlaceholder(Throwable cause) {
            super(cause);
        }
    }
}
