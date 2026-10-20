
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTablePointReadOracleTest {

    @TempDir
    Path directory;

    private static String key(int number) {
        return String.format("k%04d", number);
    }

    private static byte[] value(int number) {
        return new byte[]{
                (byte) number,
                (byte) (number >>> 8),
                (byte) (number ^ 0x5A)
        };
    }

    @Test
    void firstMiddleLastAndAbsentKeysFollowBoundedReadPath()
            throws Exception {

        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < 300; i++) {
            records.add(new BlockCodec.Record(
                    key(i),
                    i + 1L,
                    BlockCodec.PUT,
                    value(i)
            ));
        }

        SSTableWriter.Result table =
                SSTableWriter.build(
                        501,
                        records.iterator(),
                        records.size()
                );

        Path file = directory.resolve(
                table.meta().file()
        );

        Files.write(file, table.bytes());

        StorageStats stats = new StorageStats();

        DiskSSTableTableSource reader =
                DiskSSTableTableSource.open(
                        file,
                        table.meta(),
                        stats
                );

        for (int number : new int[]{0, 150, 299}) {
            stats.reset();

            var result = reader.get(key(number));

            assertTrue(result.isPresent());
            assertArrayEquals(
                    value(number),
                    result.orElseThrow().value()
            );

            assertEquals(
                    number + 1L,
                    result.orElseThrow().sequence()
            );

            var counters = stats.snapshot();

            assertEquals(
                    1L,
                    counters.get("bloomPositiveProbes")
            );

            assertTrue(
                    counters.get("sstableDataBlocksRead") <= 2
            );
        }

        stats.reset();

        assertTrue(
                reader.get("k0150x").isEmpty()
        );

        var absent = stats.snapshot();

        assertEquals(
                1L,
                absent.get("bloomPositiveProbes")
                        + absent.get("bloomNegativeSkips")
        );

        assertTrue(
                absent.get("sstableDataBlocksRead") <= 2
        );

        stats.reset();

        assertTrue(reader.get("a0000").isEmpty());
        assertTrue(reader.get("z9999").isEmpty());

        var outside = stats.snapshot();

        assertEquals(
                0L,
                outside.get("sstableDataBlocksRead")
        );

        assertEquals(
                0L,
                outside.get("bloomPositiveProbes")
        );

        assertEquals(
                0L,
                outside.get("bloomNegativeSkips")
        );
    }

    @Test
    void physicallyPresentTombstoneIsReturnedAsDelete()
            throws Exception {

        List<BlockCodec.Record> records = List.of(
                new BlockCodec.Record(
                        "alpha", 10, BlockCodec.PUT,
                        new byte[]{1}
                ),
                new BlockCodec.Record(
                        "beta", 11, BlockCodec.DELETE,
                        null
                ),
                new BlockCodec.Record(
                        "gamma", 12, BlockCodec.PUT,
                        new byte[0]
                )
        );

        SSTableWriter.Result table =
                SSTableWriter.build(
                        502,
                        records.iterator(),
                        records.size()
                );

        Path file = directory.resolve(
                table.meta().file()
        );

        Files.write(file, table.bytes());

        DiskSSTableTableSource reader =
                DiskSSTableTableSource.open(
                        file,
                        table.meta()
                );

        assertEquals(
                rs.raf.dlsm.common.Kind.DELETE,
                reader.get("beta")
                        .orElseThrow().kind()
        );

        assertArrayEquals(
                new byte[0],
                reader.get("gamma")
                        .orElseThrow().value()
        );
    }
}
