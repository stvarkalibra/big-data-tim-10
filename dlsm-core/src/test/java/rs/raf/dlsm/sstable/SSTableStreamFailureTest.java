
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class SSTableStreamFailureTest {

    private static BlockCodec.Record put(
            String key,
            long sequence) {

        return new BlockCodec.Record(
                key,
                sequence,
                BlockCodec.PUT,
                new byte[100]
        );
    }

    private static List<BlockCodec.Record> records(int count) {
        List<BlockCodec.Record> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            result.add(put(
                    String.format("key%05d", i),
                    i + 1L
            ));
        }

        return result;
    }

    @Test
    void duplicateKeyCanLeavePartialOutput() {
        List<BlockCodec.Record> input = records(100);

        // A duplicate encountered after earlier blocks
        // have already been emitted.
        input.add(put("key00099", 101));

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> SSTableStreamEncoder.encode(
                        output,
                        201,
                        input.iterator(),
                        input.size()
                )
        );

        assertTrue(
                error.getMessage().contains(
                        "strictly increasing")
        );

        assertTrue(
                output.size() > SSTableWriter.HEADER_BYTES,
                "The output should contain previously emitted data"
        );

        // No SSTableMeta was returned. Partial bytes are
        // not a publishable table.
    }

    @Test
    void unsortedKeyCanLeavePartialOutput() {
        List<BlockCodec.Record> input = records(100);

        input.add(put("aaa", 101));

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableStreamEncoder.encode(
                        output,
                        202,
                        input.iterator(),
                        input.size()
                )
        );

        assertTrue(
                output.size() > SSTableWriter.HEADER_BYTES
        );
    }

    @Test
    void tooFewRecordsCanLeavePartialOutput() {
        List<BlockCodec.Record> input = records(100);

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> SSTableStreamEncoder.encode(
                        output,
                        203,
                        input.iterator(),
                        101
                )
        );

        assertTrue(
                error.getMessage().contains("Expected 101")
        );

        assertTrue(
                output.size() > SSTableWriter.HEADER_BYTES
        );
    }

    @Test
    void tooManyRecordsCanLeavePartialOutput() {
        List<BlockCodec.Record> input = records(101);

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableStreamEncoder.encode(
                        output,
                        204,
                        input.iterator(),
                        100
                )
        );

        assertTrue(
                output.size() > SSTableWriter.HEADER_BYTES
        );
    }

    @Test
    void iteratorFailureLeavesOutputUnpublishable() {
        List<BlockCodec.Record> input = records(100);

        Iterator<BlockCodec.Record> source =
                new Iterator<>() {

                    private int position;

                    @Override
                    public boolean hasNext() {
                        return position < input.size();
                    }

                    @Override
                    public BlockCodec.Record next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }

                        if (position == 90) {
                            throw new IllegalStateException(
                                    "Injected iterator failure"
                            );
                        }

                        return input.get(position++);
                    }
                };

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> SSTableStreamEncoder.encode(
                        output,
                        205,
                        source,
                        100
                )
        );

        assertEquals(
                "Injected iterator failure",
                error.getMessage()
        );

        assertTrue(
                output.size() > SSTableWriter.HEADER_BYTES
        );
    }

    @Test
    void outputFailurePropagatesWithoutClosingStream() {
        class FailingStream extends OutputStream {

            private long written;
            private boolean closed;

            @Override
            public void write(int value) throws IOException {
                if (written >= 1000) {
                    throw new IOException(
                            "Injected output failure"
                    );
                }

                written++;
            }

            @Override
            public void write(
                    byte[] data,
                    int offset,
                    int length) throws IOException {

                for (int i = 0; i < length; i++) {
                    write(data[offset + i]);
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        }

        FailingStream output = new FailingStream();

        IOException error = assertThrows(
                IOException.class,
                () -> SSTableStreamEncoder.encode(
                        output,
                        206,
                        records(100).iterator(),
                        100
                )
        );

        assertEquals(
                "Injected output failure",
                error.getMessage()
        );

        assertTrue(output.written > 0);
        assertFalse(output.closed);
    }

    @Test
    void invalidArgumentsDoNotWriteHeader() {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableStreamEncoder.encode(
                        output,
                        0,
                        records(1).iterator(),
                        1
                )
        );

        assertEquals(0, output.size());

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableStreamEncoder.encode(
                        output,
                        207,
                        records(1).iterator(),
                        0
                )
        );

        assertEquals(0, output.size());
    }

    @Test
    void successfulEncodingStillMatchesExistingWriter()
            throws Exception {

        List<BlockCodec.Record> input = records(100);

        SSTableWriter.Result expected =
                SSTableWriter.build(
                        208,
                        input.iterator(),
                        input.size()
                );

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        SSTableMeta actual = SSTableStreamEncoder.encode(
                output,
                208,
                input.iterator(),
                input.size()
        );

        assertEquals(expected.meta(), actual);

        assertArrayEquals(
                expected.bytes(),
                output.toByteArray()
        );

        assertDoesNotThrow(
                () -> SSTableReader.open(
                        output.toByteArray(),
                        actual
                )
        );
    }
}
