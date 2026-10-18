
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.TableSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SSTableTableSourceTest {

    private static BlockCodec.Record put(
            String key, long sequence, String value) {

        return new BlockCodec.Record(
                key,
                sequence,
                BlockCodec.PUT,
                value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static SSTableWriter.Result build(
            List<BlockCodec.Record> records) {

        return SSTableWriter.build(
                42, records.iterator(), records.size()
        );
    }

    private static TableSource open(
            SSTableWriter.Result table) throws StorageException {

        return SSTableTableSource.open(
                table.bytes(), table.meta()
        );
    }

    @Test
    void exposesMetadata() throws Exception {
        var table = build(List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B")
        ));

        TableSource source = open(table);

        assertEquals(table.meta(), source.meta());
        assertEquals(42L, source.tableId());
    }

    @Test
    void returnsInternalRecord() throws Exception {
        var table = build(List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B")
        ));

        TableSource source = open(table);
        InternalRecord record =
                source.get("kimi").orElseThrow();

        assertEquals("kimi", record.key());
        assertEquals(2L, record.sequence());
        assertEquals(Kind.PUT, record.kind());

        assertArrayEquals(
                "B".getBytes(StandardCharsets.UTF_8),
                record.value()
        );
    }

    @Test
    void missingKeyReturnsEmpty() throws Exception {
        var table = build(List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B")
        ));

        TableSource source = open(table);

        assertTrue(source.get("marko").isEmpty());
        assertTrue(source.get("zzzz").isEmpty());
    }

    @Test
    void tombstoneIsReturnedNotHidden() throws Exception {
        var table = build(List.of(
                new BlockCodec.Record(
                        "deleted", 10,
                        BlockCodec.DELETE, null
                ),
                put("kimi", 11, "A")
        ));

        TableSource source = open(table);
        Optional<InternalRecord> result =
                source.get("deleted");

        assertTrue(result.isPresent());
        assertEquals(Kind.DELETE, result.orElseThrow().kind());
        assertTrue(result.orElseThrow().isTombstone());
        assertNull(result.orElseThrow().value());
    }

    @Test
    void emptyPutIsNotTombstone() throws Exception {
        var table = build(List.of(
                new BlockCodec.Record(
                        "empty", 1, BlockCodec.PUT, new byte[0]
                )
        ));

        InternalRecord record =
                open(table).get("empty").orElseThrow();

        assertEquals(Kind.PUT, record.kind());
        assertFalse(record.isTombstone());
        assertNotNull(record.value());
        assertEquals(0, record.valueLength());
    }

    @Test
    void iteratorStartsAtRequestedKey() throws Exception {
        var table = build(List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B"),
                put("matej", 3, "C"),
                put("zika", 4, "D")
        ));

        Iterator<InternalRecord> iterator =
                open(table).iterator("kimi");

        assertEquals("kimi", iterator.next().key());
        assertEquals("matej", iterator.next().key());
        assertEquals("zika", iterator.next().key());
        assertFalse(iterator.hasNext());
    }

    @Test
    void iteratorWorksAcrossMultipleBlocks() throws Exception {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 500; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%05d", i),
                    i + 1L,
                    BlockCodec.PUT,
                    new byte[100]
            ));
        }

        TableSource source = open(build(records));
        Iterator<InternalRecord> iterator =
                source.iterator("key00490");

        int count = 0;

        while (iterator.hasNext()) {
            InternalRecord record = iterator.next();

            assertEquals(
                    String.format("key%05d", 490 + count),
                    record.key()
            );
            count++;
        }

        assertEquals(10, count);
    }

    @Test
    void corruptFileProducesStorageException() {
        var table = build(List.of(
                put("kimi", 1, "A")
        ));

        byte[] corrupted = table.bytes();
        corrupted[25] ^= 1;

        StorageException exception = assertThrows(
                StorageException.class,
                () -> SSTableTableSource.open(
                        corrupted, table.meta()
                )
        );

        assertEquals(ErrorCode.STORAGE_IO, exception.code());
        assertEquals("DATA_BLOCK", exception.category());
    }

    @Test
    void modificationsToOriginalBytesDoNotAffectReader()
            throws Exception {

        var table = build(List.of(
                put("kimi", 1, "A")
        ));

        byte[] bytes = table.bytes();
        TableSource source =
                SSTableTableSource.open(bytes, table.meta());

        bytes[25] ^= 1;

        assertArrayEquals(
                new byte[]{'A'},
                source.get("kimi").orElseThrow().value()
        );
    }
}
