
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SSTableRecordAdapterTest {

    @TempDir
    Path directory;

    @Test
    void convertsPutRecord() {
        InternalRecord input = InternalRecord.put(
                "alpha",
                15,
                new byte[]{1, 2, 3}
        );

        BlockCodec.Record output =
                SSTableRecordAdapter
                        .adapt(List.of(input).iterator())
                        .next();

        assertEquals("alpha", output.key());
        assertEquals(15L, output.sequence());
        assertEquals(BlockCodec.PUT, output.kind());
        assertFalse(output.tombstone());

        assertArrayEquals(
                new byte[]{1, 2, 3},
                output.value()
        );
    }

    @Test
    void convertsTombstone() {
        InternalRecord input =
                InternalRecord.delete("beta", 20);

        BlockCodec.Record output =
                SSTableRecordAdapter
                        .adapt(List.of(input).iterator())
                        .next();

        assertEquals("beta", output.key());
        assertEquals(20L, output.sequence());
        assertEquals(BlockCodec.DELETE, output.kind());
        assertTrue(output.tombstone());
        assertNull(output.value());
    }

    @Test
    void preservesEmptyPut() {
        InternalRecord input =
                InternalRecord.put("empty", 7, new byte[0]);

        BlockCodec.Record output =
                SSTableRecordAdapter
                        .adapt(List.of(input).iterator())
                        .next();

        assertEquals(BlockCodec.PUT, output.kind());
        assertFalse(output.tombstone());

        assertNotNull(output.value());
        assertEquals(0, output.value().length);
    }

    @Test
    void preservesInputOrderAndSequences() {
        List<InternalRecord> input = List.of(
                InternalRecord.put(
                        "alpha", 100, new byte[]{1}),
                InternalRecord.delete("beta", 3),
                InternalRecord.put(
                        "gamma", 55, new byte[]{2})
        );

        Iterator<BlockCodec.Record> adapter =
                SSTableRecordAdapter.adapt(input.iterator());

        List<String> keys = new ArrayList<>();
        List<Long> sequences = new ArrayList<>();

        while (adapter.hasNext()) {
            BlockCodec.Record record = adapter.next();

            keys.add(record.key());
            sequences.add(record.sequence());
        }

        assertEquals(
                List.of("alpha", "beta", "gamma"),
                keys
        );

        assertEquals(
                List.of(100L, 3L, 55L),
                sequences
        );
    }

    @Test
    void doesNotConsumeRecordsEagerly() {
        AtomicInteger consumed = new AtomicInteger();

        Iterator<InternalRecord> source = new Iterator<>() {
            private int position;

            @Override
            public boolean hasNext() {
                return position < 3;
            }

            @Override
            public InternalRecord next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                int index = position++;
                consumed.incrementAndGet();

                return InternalRecord.put(
                        "key" + index,
                        index + 1L,
                        new byte[]{(byte) index}
                );
            }
        };

        Iterator<BlockCodec.Record> adapter =
                SSTableRecordAdapter.adapt(source);

        assertEquals(0, consumed.get());

        assertTrue(adapter.hasNext());
        assertEquals(0, consumed.get());

        assertEquals("key0", adapter.next().key());
        assertEquals(1, consumed.get());

        assertEquals("key1", adapter.next().key());
        assertEquals(2, consumed.get());

        assertEquals("key2", adapter.next().key());
        assertEquals(3, consumed.get());

        assertFalse(adapter.hasNext());
    }

    @Test
    void rejectsNullIterator() {
        assertThrows(
                NullPointerException.class,
                () -> SSTableRecordAdapter.adapt(null)
        );
    }

    @Test
    void refusesRemoval() {
        Iterator<BlockCodec.Record> adapter =
                SSTableRecordAdapter.adapt(
                        List.of(
                                InternalRecord.put(
                                        "alpha",
                                        1,
                                        new byte[]{1}
                                )
                        ).iterator()
                );

        assertThrows(
                UnsupportedOperationException.class,
                adapter::remove
        );
    }

    @Test
    void throwsWhenIteratorIsExhausted() {
        Iterator<BlockCodec.Record> adapter =
                SSTableRecordAdapter.adapt(
                        List.<InternalRecord>of().iterator()
                );

        assertFalse(adapter.hasNext());

        assertThrows(
                NoSuchElementException.class,
                adapter::next
        );
    }

    @Test
    void worksWithExistingMemoryWriter() {
        List<InternalRecord> input = List.of(
                InternalRecord.put(
                        "alpha", 5, new byte[]{1, 2}),
                InternalRecord.delete("beta", 10),
                InternalRecord.put(
                        "gamma", 8, new byte[0])
        );

        SSTableWriter.Result table =
                SSTableWriter.build(
                        77,
                        SSTableRecordAdapter.adapt(
                                input.iterator()
                        ),
                        input.size()
                );

        SSTableReader reader = SSTableReader.open(
                table.bytes(),
                table.meta()
        );

        assertEquals(3, table.meta().entryCount());
        assertEquals(5, table.meta().minSequence());
        assertEquals(10, table.meta().maxSequence());

        assertArrayEquals(
                new byte[]{1, 2},
                reader.get("alpha").orElseThrow().value()
        );

        assertTrue(
                reader.get("beta").orElseThrow().tombstone()
        );

        assertFalse(
                reader.get("gamma").orElseThrow().tombstone()
        );

        assertEquals(
                0,
                reader.get("gamma")
                        .orElseThrow().value().length
        );
    }

    @Test
    void producesIdenticalBytesThroughStreamEncoder()
            throws Exception {

        List<InternalRecord> input = List.of(
                InternalRecord.put(
                        "alpha", 101, new byte[]{1}),
                InternalRecord.delete("beta", 102),
                InternalRecord.put(
                        "gamma", 103, new byte[0])
        );

        SSTableWriter.Result expected =
                SSTableWriter.build(
                        78,
                        SSTableRecordAdapter.adapt(
                                input.iterator()
                        ),
                        input.size()
                );

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        SSTableMeta actual = SSTableStreamEncoder.encode(
                output,
                78,
                SSTableRecordAdapter.adapt(
                        input.iterator()
                ),
                input.size()
        );

        assertEquals(expected.meta(), actual);
        assertArrayEquals(
                expected.bytes(),
                output.toByteArray()
        );
    }

    @Test
    void streamsInternalRecordsIntoValidSstableFile()
            throws Exception {

        List<InternalRecord> input = new ArrayList<>();

        for (int i = 0; i < 500; i++) {
            input.add(
                    InternalRecord.put(
                            String.format("key%05d", i),
                            i + 1L,
                            new byte[100]
                    )
            );
        }

        Path file = directory.resolve(
                SSTableMeta.fileName(79)
        );

        SSTableMeta meta;

        try (var output = Files.newOutputStream(file)) {
            meta = SSTableStreamEncoder.encode(
                    output,
                    79,
                    SSTableRecordAdapter.adapt(
                            input.iterator()
                    ),
                    input.size()
            );
        }

        assertEquals(500, meta.entryCount());
        assertEquals(Files.size(file), meta.sizeBytes());

        assertDoesNotThrow(
                () -> SSTableValidator.validateFully(file, meta)
        );

        var reader = DiskSSTableTableSource.open(file, meta);

        assertEquals(
                Kind.PUT,
                reader.get("key00499")
                        .orElseThrow().kind()
        );

        assertEquals(
                500L,
                reader.get("key00499")
                        .orElseThrow().sequence()
        );
    }

    @Test
    void rejectsDuplicateKeysThroughWriter() {
        List<InternalRecord> input = List.of(
                InternalRecord.put(
                        "alpha", 1, new byte[]{1}),
                InternalRecord.put(
                        "alpha", 2, new byte[]{2})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableWriter.build(
                        80,
                        SSTableRecordAdapter.adapt(
                                input.iterator()
                        ),
                        input.size()
                )
        );
    }

    @Test
    void rejectsUnsortedKeysThroughWriter() {
        List<InternalRecord> input = List.of(
                InternalRecord.put(
                        "zeta", 1, new byte[]{1}),
                InternalRecord.put(
                        "alpha", 2, new byte[]{2})
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SSTableWriter.build(
                        81,
                        SSTableRecordAdapter.adapt(
                                input.iterator()
                        ),
                        input.size()
                )
        );
    }
}
