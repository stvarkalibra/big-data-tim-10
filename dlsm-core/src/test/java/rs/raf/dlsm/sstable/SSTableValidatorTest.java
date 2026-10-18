
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableValidatorTest {

    private BlockCodec.Record put(
            String key, long sequence, String value) {
        return new BlockCodec.Record(
                key,
                sequence,
                BlockCodec.PUT,
                value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private SSTableWriter.Result build() {
        return SSTableWriter.build(
                1,
                List.of(
                        put("anna", 1, "A"),
                        put("kimi", 2, "B"),
                        put("zika", 3, "C")
                ).iterator(),
                3
        );
    }

    private static long footerLong(byte[] data, int relativeOffset) {
        int footerStart =
                data.length - SSTableWriter.FOOTER_BYTES;

        return ByteBuffer.wrap(data)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong(footerStart + relativeOffset);
    }

    @Test
    void acceptsValidTable() {
        var result = build();

        assertDoesNotThrow(() ->
                SSTableValidator.validateFully(
                        result.bytes(), result.meta()));

        assertTrue(SSTableValidator.isValid(
                result.bytes(), result.meta()));
    }

    @Test
    void rejectsNullData() {
        var result = build();

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        null, result.meta()));

        assertEquals(
                SSTableValidator.Category.TRUNCATED_REGION,
                exception.category());
    }

    @Test
    void rejectsNullMetadata() {
        var result = build();

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        result.bytes(), null));

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                exception.category());
    }

    @Test
    void rejectsCorruptedHeader() {
        var result = build();
        byte[] data = result.bytes();

        data[0] ^= 0x01;

        assertFalse(SSTableValidator.isValid(
                data, result.meta()));

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        data, result.meta()));

        assertEquals(
                SSTableValidator.Category.HEADER,
                exception.category());
    }

    @Test
    void rejectsCorruptedDataBlock() {
        var result = build();
        byte[] data = result.bytes();

        data[SSTableWriter.HEADER_BYTES + 10] ^= 0x01;

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        data, result.meta()));
    }

    @Test
    void rejectsCorruptedIndex() {
        var result = build();
        byte[] data = result.bytes();

        int indexOffset =
                Math.toIntExact(footerLong(data, 32));

        data[indexOffset + 5] ^= 0x01;

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        data, result.meta()));
    }

    @Test
    void rejectsCorruptedBloom() {
        var result = build();
        byte[] data = result.bytes();

        int bloomOffset =
                Math.toIntExact(footerLong(data, 48));

        data[bloomOffset + 5] ^= 0x01;

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        data, result.meta()));
    }

    @Test
    void rejectsCorruptedFooter() {
        var result = build();
        byte[] data = result.bytes();

        data[data.length - 8] ^= 0x01;

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        data, result.meta()));
    }

    @Test
    void rejectsTruncatedTable() {
        var result = build();
        byte[] original = result.bytes();

        byte[] truncated = Arrays.copyOf(
                original, original.length - 100);

        assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        truncated, result.meta()));
    }

    @Test
    void rejectsDifferentManifestMetadata() {
        var result = build();
        SSTableMeta original = result.meta();

        SSTableMeta wrong = new SSTableMeta(
                original.tableId(),
                original.file(),
                "aaaa",
                original.maxKey(),
                original.minSequence(),
                original.maxSequence(),
                original.entryCount(),
                original.sizeBytes()
        );

        var exception = assertThrows(
                SSTableValidator.ValidationException.class,
                () -> SSTableValidator.validateFully(
                        result.bytes(), wrong));

        assertEquals(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                exception.category());
    }

    @Test
    void validatesManyBlocks() {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 500; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%05d", i),
                    i + 1,
                    BlockCodec.PUT,
                    new byte[100]
            ));
        }

        var result = SSTableWriter.build(
                5, records.iterator(), records.size());

        assertDoesNotThrow(() ->
                SSTableValidator.validateFully(
                        result.bytes(), result.meta()));

        SSTableReader reader =
                SSTableReader.open(result.bytes(), result.meta());

        assertTrue(reader.blockCount() > 1);
    }

    @Test
    void rejectsOversizedOrInvalidInput() {
        var result = build();

        byte[] empty = new byte[0];

        assertFalse(SSTableValidator.isValid(
                empty, result.meta()));
    }
}
