
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

class SSTableFormatContractTest {

    private static final int SST_MAGIC = 0x53535431;
    private static final int BLOCK_MAGIC = 0x44424C31;
    private static final int INDEX_MAGIC = 0x49445831;
    private static final int BLOOM_MAGIC = 0x424C4D31;
    private static final int FOOTER_MAGIC = 0x46545231;

    private SSTableWriter.Result smallTable() {
        return SSTableWriter.build(
                7,
                List.of(new BlockCodec.Record(
                        "alpha",
                        1,
                        BlockCodec.PUT,
                        "A".getBytes(StandardCharsets.US_ASCII)
                )).iterator(),
                1
        );
    }

    private static ByteBuffer view(byte[] bytes) {
        return ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN);
    }

    private static void assertCrc(
            byte[] bytes, int offset, int length) {

        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length - 4);

        long stored = Integer.toUnsignedLong(
                view(bytes).getInt(offset + length - 4));

        assertEquals(crc.getValue(), stored);
    }

    @Test
    void headerHasExactLayout() {
        byte[] bytes = smallTable().bytes();
        ByteBuffer buffer = view(bytes);

        assertEquals(16, SSTableWriter.HEADER_BYTES);
        assertEquals(SST_MAGIC, buffer.getInt(0));
        assertEquals(1, buffer.getInt(4));
        assertEquals(7L, buffer.getLong(8));
    }

    @Test
    void dataBlockHasExactLayout() {
        byte[] bytes = smallTable().bytes();
        ByteBuffer buffer = view(bytes);

        int offset = SSTableWriter.HEADER_BYTES;

        assertEquals(BLOCK_MAGIC, buffer.getInt(offset));
        assertEquals(1, buffer.getInt(offset + 4));
        assertEquals(1, buffer.getInt(offset + 8));
        assertEquals(23, buffer.getInt(offset + 12));

        int record = offset + 16;

        assertEquals(5, buffer.getInt(record));
        assertEquals(1L, buffer.getLong(record + 4));
        assertEquals(BlockCodec.PUT, buffer.get(record + 12));
        assertEquals(1, buffer.getInt(record + 13));

        byte[] key = new byte[5];
        System.arraycopy(bytes, record + 17, key, 0, 5);

        assertEquals(
                "alpha",
                new String(key, StandardCharsets.US_ASCII));

        assertEquals('A', bytes[record + 22]);

        assertCrc(bytes, offset, 43);
    }

    @Test
    void sparseIndexPointsToDataBlock() {
        byte[] bytes = smallTable().bytes();
        ByteBuffer buffer = view(bytes);

        int offset = 59;

        assertEquals(INDEX_MAGIC, buffer.getInt(offset));
        assertEquals(1, buffer.getInt(offset + 4));
        assertEquals(1, buffer.getInt(offset + 8));
        assertEquals(5, buffer.getInt(offset + 12));

        byte[] key = new byte[5];
        System.arraycopy(bytes, offset + 16, key, 0, 5);

        assertEquals(
                "alpha",
                new String(key, StandardCharsets.US_ASCII));

        assertEquals(16L, buffer.getLong(offset + 21));
        assertEquals(43, buffer.getInt(offset + 29));

        assertCrc(bytes, offset, 37);
    }

    @Test
    void bloomHasExactLayout() {
        byte[] bytes = smallTable().bytes();
        ByteBuffer buffer = view(bytes);

        int offset = 96;

        assertEquals(BLOOM_MAGIC, buffer.getInt(offset));
        assertEquals(1, buffer.getInt(offset + 4));
        assertEquals(64, buffer.getInt(offset + 8));
        assertEquals(7, buffer.getInt(offset + 12));
        assertEquals(8, buffer.getInt(offset + 16));

        assertCrc(bytes, offset, 32);

        byte[] serialized = new byte[32];
        System.arraycopy(bytes, offset, serialized, 0, 32);

        assertTrue(
                BloomFilter.deserialize(serialized)
                        .mightContain("alpha"));
    }

    @Test
    void footerHasExactOffsetsAndLengths() {
        byte[] bytes = smallTable().bytes();
        ByteBuffer buffer = view(bytes);

        assertEquals(384, SSTableWriter.FOOTER_BYTES);
        assertEquals(512, bytes.length);

        int footer = bytes.length - SSTableWriter.FOOTER_BYTES;

        assertEquals(128, footer);
        assertEquals(FOOTER_MAGIC, buffer.getInt(footer));
        assertEquals(1, buffer.getInt(footer + 4));
        assertEquals(7L, buffer.getLong(footer + 8));

        assertEquals(16L, buffer.getLong(footer + 16));
        assertEquals(43L, buffer.getLong(footer + 24));

        assertEquals(59L, buffer.getLong(footer + 32));
        assertEquals(37L, buffer.getLong(footer + 40));

        assertEquals(96L, buffer.getLong(footer + 48));
        assertEquals(32L, buffer.getLong(footer + 56));

        assertEquals(1L, buffer.getLong(footer + 64));
        assertEquals(1L, buffer.getLong(footer + 72));
        assertEquals(1L, buffer.getLong(footer + 80));

        assertEquals(5, buffer.getInt(footer + 88));
        assertEquals(5, buffer.getInt(footer + 220));

        for (int i = footer + 352; i < footer + 380; i++) {
            assertEquals(0, bytes[i]);
        }

        assertCrc(bytes, footer, 384);
    }

    @Test
    void regionsAreContiguous() {
        byte[] bytes = smallTable().bytes();
        ByteBuffer buffer = view(bytes);
        int footer = bytes.length - 384;

        long dataOffset = buffer.getLong(footer + 16);
        long dataLength = buffer.getLong(footer + 24);

        long indexOffset = buffer.getLong(footer + 32);
        long indexLength = buffer.getLong(footer + 40);

        long bloomOffset = buffer.getLong(footer + 48);
        long bloomLength = buffer.getLong(footer + 56);

        assertEquals(
                SSTableWriter.HEADER_BYTES, dataOffset);

        assertEquals(
                indexOffset, dataOffset + dataLength);

        assertEquals(
                bloomOffset, indexOffset + indexLength);

        assertEquals(
                footer, bloomOffset + bloomLength);

        assertEquals(
                bytes.length, footer + SSTableWriter.FOOTER_BYTES);
    }

    @Test
    void sameInputProducesIdenticalBinaryFormat() {
        SSTableWriter.Result first = smallTable();
        SSTableWriter.Result second = smallTable();

        assertEquals(first.meta(), second.meta());
        assertArrayEquals(first.bytes(), second.bytes());
    }
}
