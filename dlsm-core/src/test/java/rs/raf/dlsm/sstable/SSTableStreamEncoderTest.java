
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableStreamEncoderTest {

    @TempDir
    Path directory;

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

    private static List<BlockCodec.Record> records(int count) {
        List<BlockCodec.Record> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            result.add(put(
                    String.format("key%05d", i),
                    i + 1L,
                    new byte[100]
            ));
        }

        return result;
    }

    private static SSTableMeta encode(
            OutputStream output,
            long tableId,
            List<BlockCodec.Record> records)
            throws IOException {

        return SSTableStreamEncoder.encode(
                output,
                tableId,
                records.iterator(),
                records.size()
        );
    }

    @Test
    void producesExactlyTheSameBytesAsExistingWriter()
            throws Exception {

        List<BlockCodec.Record> records = List.of(
                put("alpha", 1, new byte[]{1, 2}),
                delete("beta", 2),
                put("gamma", 3, new byte[0])
        );

        SSTableWriter.Result existing =
                SSTableWriter.build(
                        42, records.iterator(), records.size());

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        SSTableMeta streamed =
                encode(output, 42, records);

        assertEquals(existing.meta(), streamed);
        assertArrayEquals(
                existing.bytes(), output.toByteArray());
    }

    @Test
    void producesIdenticalMultiBlockTable()
            throws Exception {

        List<BlockCodec.Record> records = records(1000);

        SSTableWriter.Result existing =
                SSTableWriter.build(
                        43, records.iterator(), records.size());

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        SSTableMeta streamed =
                encode(output, 43, records);

        assertEquals(existing.meta(), streamed);
        assertArrayEquals(
                existing.bytes(), output.toByteArray());

        SSTableReader reader = SSTableReader.open(
                output.toByteArray(), streamed);

        assertTrue(reader.blockCount() > 1);

        assertEquals(1000, streamed.entryCount());
        assertTrue(reader.get("key00999").isPresent());
    }

    @Test
    void streamsDirectlyIntoOutputStream()
            throws Exception {

        List<BlockCodec.Record> records = records(300);

        class CountingOutputStream extends OutputStream {
            private long bytes;
            private int calls;

            @Override
            public void write(int value) {
                bytes++;
                calls++;
            }

            @Override
            public void write(byte[] values, int off, int len) {
                bytes += len;
                calls++;
            }
        }

        CountingOutputStream output =
                new CountingOutputStream();

        SSTableMeta meta = encode(
                output, 44, records);

        assertEquals(meta.sizeBytes(), output.bytes);
        assertTrue(output.calls > 4);
    }

    @Test
    void fileOutputMatchesMemoryWriter()
            throws Exception {

        List<BlockCodec.Record> records = records(150);
        Path file = directory.resolve("table.tmp");

        SSTableMeta meta;

        try (OutputStream output =
                     Files.newOutputStream(file)) {

            meta = encode(output, 45, records);
        }

        assertEquals(meta.sizeBytes(), Files.size(file));

        SSTableValidator.validateFully(file, meta);

        SSTableWriter.Result expected =
                SSTableWriter.build(
                        45, records.iterator(), records.size());

        assertArrayEquals(
                expected.bytes(), Files.readAllBytes(file));
    }

    @Test
    void preservesTombstoneAndEmptyValue()
            throws Exception {

        List<BlockCodec.Record> records = List.of(
                delete("alpha", 8),
                put("beta", 9, new byte[0])
        );

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        SSTableMeta meta = encode(
                output, 46, records);

        SSTableReader reader =
                SSTableReader.open(
                        output.toByteArray(), meta);

        assertTrue(reader.get("alpha")
                .orElseThrow().tombstone());

        BlockCodec.Record beta =
                reader.get("beta").orElseThrow();

        assertFalse(beta.tombstone());
        assertArrayEquals(new byte[0], beta.value());
    }

    @Test
    void supportsMaximumSizedValue()
            throws Exception {

        byte[] value = new byte[BlockCodec.MAX_VALUE_BYTES];

        List<BlockCodec.Record> records = List.of(
                put("large", 1, value)
        );

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        SSTableMeta meta = encode(
                output, 47, records);

        SSTableReader reader =
                SSTableReader.open(
                        output.toByteArray(), meta);

        assertArrayEquals(
                value,
                reader.get("large").orElseThrow().value());
    }

    @Test
    void rejectsDuplicateKeys() {
        List<BlockCodec.Record> records = List.of(
                put("alpha", 1, new byte[]{1}),
                put("alpha", 2, new byte[]{2})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> encode(
                        new ByteArrayOutputStream(),
                        48,
                        records)
        );
    }

    @Test
    void rejectsUnsortedKeys() {
        List<BlockCodec.Record> records = List.of(
                put("zeta", 1, new byte[]{1}),
                put("alpha", 2, new byte[]{2})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> encode(
                        new ByteArrayOutputStream(),
                        49,
                        records)
        );
    }

    @Test
    void rejectsIncorrectEntryCount() {
        List<BlockCodec.Record> records = List.of(
                put("alpha", 1, new byte[]{1})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableStreamEncoder.encode(
                        new ByteArrayOutputStream(),
                        50,
                        records.iterator(),
                        2)
        );
    }

    @Test
    void doesNotCloseCallerOwnedStream()
            throws Exception {

        class TrackedStream extends ByteArrayOutputStream {
            private boolean closed;

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }

        TrackedStream output = new TrackedStream();

        encode(output, 51, List.of(
                put("alpha", 1, new byte[]{1})
        ));

        assertFalse(output.closed);

        output.write(123);
        assertFalse(output.closed);
    }

    @Test
    void propagatesOutputFailure()
            throws Exception {

        OutputStream broken = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("Injected write failure");
            }

            @Override
            public void write(
                    byte[] bytes, int off, int len)
                    throws IOException {
                throw new IOException("Injected write failure");
            }
        };

        IOException error = assertThrows(
                IOException.class,
                () -> encode(
                        broken,
                        52,
                        List.of(put(
                                "alpha", 1, new byte[]{1}))
                )
        );

        assertEquals(
                "Injected write failure",
                error.getMessage());
    }
}
