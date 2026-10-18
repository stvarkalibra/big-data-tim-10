
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

class SSTableWriterTest {

    private BlockCodec.Record put(
            String key, long seq, String value) {
        return new BlockCodec.Record(
                key, seq, BlockCodec.PUT,
                value.getBytes(StandardCharsets.UTF_8));
    }

    private SSTableWriter.Result build(
            long id, List<BlockCodec.Record> records) {
        return SSTableWriter.build(
                id, records.iterator(), records.size());
    }

    @Test
    void createsValidTableMetadata() {
        var result = build(1, List.of(
                put("anna", 5, "A"),
                put("kimi", 7, "B"),
                put("zika", 9, "C")
        ));

        assertEquals(1, result.meta().tableId());
        assertEquals("anna", result.meta().minKey());
        assertEquals("zika", result.meta().maxKey());
        assertEquals(5, result.meta().minSequence());
        assertEquals(9, result.meta().maxSequence());
        assertEquals(3, result.meta().entryCount());
        assertEquals(result.bytes().length,
                result.meta().sizeBytes());
    }

    @Test
    void tableContainsCorrectHeader() {
        var result = build(42, List.of(
                put("kimi", 1, "test")
        ));

        ByteBuffer buffer = ByteBuffer.wrap(result.bytes())
                .order(ByteOrder.BIG_ENDIAN);

        assertEquals(0x53535431, buffer.getInt());
        assertEquals(1, buffer.getInt());
        assertEquals(42, buffer.getLong());
    }

    @Test
    void footerHasValidChecksum() {
        var result = build(1, List.of(
                put("kimi", 1, "test")
        ));

        byte[] bytes = result.bytes();
        int start = bytes.length - SSTableWriter.FOOTER_BYTES;

        CRC32C crc = new CRC32C();
        crc.update(bytes, start,
                SSTableWriter.FOOTER_BYTES - 4);

        long stored = Integer.toUnsignedLong(
                ByteBuffer.wrap(bytes, bytes.length - 4, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt());

        assertEquals(stored, crc.getValue());
    }

    @Test
    void indexAndBloomAreStored() {
        var result = build(1, List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B"),
                put("zika", 3, "C")
        ));

        byte[] bytes = result.bytes();

        ByteBuffer footer = ByteBuffer.wrap(
                bytes,
                bytes.length - SSTableWriter.FOOTER_BYTES,
                SSTableWriter.FOOTER_BYTES
        ).order(ByteOrder.BIG_ENDIAN);

        footer.getInt();
        footer.getInt();
        footer.getLong();

        long dataOffset = footer.getLong();
        long dataLength = footer.getLong();
        long indexOffset = footer.getLong();
        int indexLength = Math.toIntExact(footer.getLong());
        long bloomOffset = footer.getLong();
        int bloomLength = Math.toIntExact(footer.getLong());

        assertEquals(SSTableWriter.HEADER_BYTES, dataOffset);
        assertEquals(dataOffset + dataLength, indexOffset);
        assertEquals(indexOffset + indexLength, bloomOffset);

        byte[] indexBytes = Arrays.copyOfRange(
                bytes,
                Math.toIntExact(indexOffset),
                Math.toIntExact(indexOffset) + indexLength
        );

        byte[] bloomBytes = Arrays.copyOfRange(
                bytes,
                Math.toIntExact(bloomOffset),
                Math.toIntExact(bloomOffset) + bloomLength
        );

        SparseIndex index = SparseIndex.deserialize(indexBytes);
        BloomFilter bloom = BloomFilter.deserialize(bloomBytes);

        assertFalse(index.isEmpty());
        assertTrue(bloom.mightContain("anna"));
        assertTrue(bloom.mightContain("kimi"));
        assertTrue(bloom.mightContain("zika"));
    }

    @Test
    void rejectsUnsortedKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> build(1, List.of(
                        put("zika", 1, "A"),
                        put("anna", 2, "B")
                )));
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> build(1, List.of(
                        put("kimi", 1, "A"),
                        put("kimi", 2, "B")
                )));
    }

    @Test
    void rejectsWrongExpectedCount() {
        assertThrows(IllegalArgumentException.class,
                () -> SSTableWriter.build(
                        1,
                        List.of(put("kimi", 1, "A")).iterator(),
                        2
                ));
    }

    @Test
    void supportsTombstones() {
        var result = build(1, List.of(
                new BlockCodec.Record(
                        "deleted", 10, BlockCodec.DELETE, null),
                put("kimi", 11, "A")
        ));

        assertEquals(2, result.meta().entryCount());
        assertEquals("deleted", result.meta().minKey());
    }


    @Test
    void createsMultipleBlocks() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%04d", i),
                    i + 1,
                    BlockCodec.PUT,
                    new byte[100]
            ));
        }

        var result = build(1, records);

        byte[] bytes = result.bytes();
        int footerStart = bytes.length - SSTableWriter.FOOTER_BYTES;

        ByteBuffer footer = ByteBuffer.wrap(
                bytes, footerStart, SSTableWriter.FOOTER_BYTES
        ).order(ByteOrder.BIG_ENDIAN);

        footer.getInt();  // Magic
        footer.getInt();  // Version
        footer.getLong(); // Table ID

        footer.getLong(); // Data offset
        footer.getLong(); // Data length

        long indexOffset = footer.getLong();
        int indexLength = Math.toIntExact(footer.getLong());

        byte[] indexBytes = Arrays.copyOfRange(
                bytes,
                Math.toIntExact(indexOffset),
                Math.toIntExact(indexOffset) + indexLength
        );

        SparseIndex index = SparseIndex.deserialize(indexBytes);

        assertTrue(index.size() > 1);
        assertEquals(100, result.meta().entryCount());
    }


    @Test
    void createsDeterministicOutput() {
        List<BlockCodec.Record> records = List.of(
                put("anna", 1, "A"),
                put("kimi", 2, "B")
        );

        assertArrayEquals(
                build(1, records).bytes(),
                build(1, records).bytes()
        );
    }

    @Test
    void returnedBytesAreDefensivelyCopied() {
        var result = build(1, List.of(
                put("kimi", 1, "A")
        ));

        byte[] first = result.bytes();
        first[0] = 0;

        assertEquals(0x53, result.bytes()[0] & 0xFF);
    }
}
