
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SSTableLargePointReadTest {

    private static final int RECORD_COUNT = 10_000;
    private static final int VALUE_LENGTH = 256;

    @TempDir
    Path directory;

    private static String key(int number) {
        return String.format("key%05d", number);
    }

    private static byte[] value(int number) {
        byte[] result = new byte[VALUE_LENGTH];

        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (number * 31 + i);
        }

        return result;
    }

    private static long counter(
            Map<String, Long> counters,
            String name) {

        return counters.get(name);
    }

    private record Fixture(
            DiskSSTableTableSource reader,
            StorageStats stats,
            SSTableMeta meta,
            Path file
    ) {
    }

    private Fixture createLargeTable() throws Exception {
        List<BlockCodec.Record> records =
                new ArrayList<>(RECORD_COUNT);

        for (int i = 0; i < RECORD_COUNT; i++) {
            records.add(
                    new BlockCodec.Record(
                            key(i),
                            i + 1L,
                            BlockCodec.PUT,
                            value(i)
                    )
            );
        }

        SSTableWriter.Result result =
                SSTableWriter.build(
                        701,
                        records.iterator(),
                        records.size()
                );

        Path file = directory.resolve(
                result.meta().file()
        );

        Files.write(file, result.bytes());

        StorageStats stats = new StorageStats();

        DiskSSTableTableSource reader =
                DiskSSTableTableSource.open(
                        file,
                        result.meta(),
                        stats
                );

        return new Fixture(
                reader,
                stats,
                result.meta(),
                file
        );
    }

    @Test
    void firstMiddleAndLateKeysUseAtMostTwoBlocks()
            throws Exception {

        Fixture fixture = createLargeTable();

        assertEquals(
                RECORD_COUNT,
                fixture.meta().entryCount()
        );

        // Test beginning, middle, late and final records.
        for (int number : new int[]{
                0, 1, 5000, 9500, 9998, 9999
        }) {
            fixture.stats().reset();

            InternalRecord actual =
                    fixture.reader()
                            .get(key(number))
                            .orElseThrow();

            assertEquals(
                    key(number),
                    actual.key()
            );

            assertEquals(
                    number + 1L,
                    actual.sequence()
            );

            assertArrayEquals(
                    value(number),
                    actual.value()
            );

            Map<String, Long> counters =
                    fixture.stats().snapshot();

            long blockReads = counter(
                    counters,
                    "sstableDataBlocksRead"
            );

            assertTrue(
                    blockReads >= 1 && blockReads <= 2,
                    "Key " + key(number)
                            + " used " + blockReads
                            + " candidate blocks"
            );

            assertEquals(
                    1L,
                    counter(
                            counters,
                            "bloomPositiveProbes"
                    )
            );

            assertEquals(
                    0L,
                    counter(
                            counters,
                            "rangeScanBlocksRead"
                    )
            );

            long bytesRead = counter(
                    counters,
                    "sstableDataBytesRead"
            );

            assertTrue(
                    bytesRead > 0
                            && bytesRead < 12_000,
                    "Point lookup read too many bytes: "
                            + bytesRead
            );
        }
    }

    @Test
    void lateLookupDoesNotReadEarlyDataBlocks()
            throws Exception {

        Fixture fixture = createLargeTable();

        fixture.stats().reset();

        assertTrue(
                fixture.reader()
                        .get(key(9997))
                        .isPresent()
        );

        Map<String, Long> counters =
                fixture.stats().snapshot();

        assertTrue(
                counter(
                        counters,
                        "sstableDataBlocksRead"
                ) <= 2
        );

        assertTrue(
                counter(
                        counters,
                        "sstableDataBytesRead"
                ) < 12_000
        );

        assertTrue(
                fixture.meta().sizeBytes() > 1_000_000
        );
    }

    @Test
    void missingKeysDoNotTriggerLinearDiskScan()
            throws Exception {

        Fixture fixture = createLargeTable();

        // In-range, absent keys. Bloom may be negative
        // or may produce a permitted false positive.
        for (String absent : List.of(
                "key05000x",
                "key09000x",
                "key09998x"
        )) {
            fixture.stats().reset();

            assertTrue(
                    fixture.reader()
                            .get(absent)
                            .isEmpty()
            );

            Map<String, Long> counters =
                    fixture.stats().snapshot();

            assertEquals(
                    1L,
                    counter(
                            counters,
                            "bloomNegativeSkips"
                    ) + counter(
                            counters,
                            "bloomPositiveProbes"
                    )
            );

            assertTrue(
                    counter(
                            counters,
                            "sstableDataBlocksRead"
                    ) <= 2
            );
        }
    }

    @Test
    void reopeningLargeTablePreservesBoundedLookup()
            throws Exception {

        Fixture fixture = createLargeTable();

        StorageStats newStats = new StorageStats();

        DiskSSTableTableSource reopened =
                DiskSSTableTableSource.open(
                        fixture.file(),
                        fixture.meta(),
                        newStats
                );

        newStats.reset();

        InternalRecord actual =
                reopened.get(key(9876))
                        .orElseThrow();

        assertArrayEquals(
                value(9876),
                actual.value()
        );

        assertTrue(
                newStats.snapshot().get(
                        "sstableDataBlocksRead"
                ) <= 2
        );
    }
}
