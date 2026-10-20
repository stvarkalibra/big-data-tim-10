
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableBloomOracleTest {

    private static final int ABSENT_COUNT = 10_000;

    @TempDir
    Path directory;

    @Test
    void bloomHasNoFalseNegativesAndAtMostFivePercentPositives()
            throws Exception {

        List<BlockCodec.Record> records =
                new ArrayList<>();

        records.add(new BlockCodec.Record(
                "k00000", 1, BlockCodec.PUT,
                new byte[]{1}
        ));

        // 2000 stored even keys. Odd keys are absent
        // and fall strictly inside the physical range.
        for (int i = 1; i <= 2000; i++) {
            String key = String.format(
                    "k%05d", i * 2
            );

            byte kind = (i % 11 == 0)
                    ? BlockCodec.DELETE
                    : BlockCodec.PUT;

            byte[] value = kind == BlockCodec.DELETE
                    ? null
                    : (i % 7 == 0)
                    ? new byte[0]
                    : new byte[]{(byte) i};

            records.add(new BlockCodec.Record(
                    key,
                    i + 1L,
                    kind,
                    value
            ));
        }

        records.add(new BlockCodec.Record(
                "k99999", 2002, BlockCodec.PUT,
                new byte[]{9}
        ));

        SSTableWriter.Result table =
                SSTableWriter.build(
                        601,
                        records.iterator(),
                        records.size()
                );

        Path file = directory.resolve(
                table.meta().file()
        );

        Files.write(file, table.bytes());

        // Independently check that Bloom cannot exclude
        // any physically stored record, including DELETE.
        BloomFilter bloom =
                new BloomFilter(records.size());

        for (BlockCodec.Record record : records) {
            bloom.add(record.key());
        }

        for (BlockCodec.Record record : records) {
            assertTrue(
                    bloom.mightContain(record.key()),
                    "False negative: " + record.key()
            );
        }

        StorageStats stats = new StorageStats();

        DiskSSTableTableSource reader =
                DiskSSTableTableSource.open(
                        file,
                        table.meta(),
                        stats
                );

        // Also verify every stored key through the real reader.
        for (BlockCodec.Record record : records) {
            var actual = reader.get(record.key());

            assertTrue(
                    actual.isPresent(),
                    "Missing stored key: " + record.key()
            );

            assertEquals(
                    record.kind() == BlockCodec.DELETE
                            ? rs.raf.dlsm.common.Kind.DELETE
                            : rs.raf.dlsm.common.Kind.PUT,
                    actual.orElseThrow().kind()
            );
        }

        stats.reset();

        /*
         * Ten thousand distinct absent keys:
         * k40001, k40003, ..., k59999.
         *
         * They lie strictly inside [k00000, k99999],
         * and none is physically stored.
         */
        for (int i = 0; i < ABSENT_COUNT; i++) {
            String absentKey = String.format(
                    "k%05d", 40001 + 2 * i
            );

            assertTrue(
                    reader.get(absentKey).isEmpty(),
                    "Unexpected record: " + absentKey
            );
        }

        var counters = stats.snapshot();

        long negative =
                counters.get("bloomNegativeSkips");

        long positive =
                counters.get("bloomPositiveProbes");

        long blockReads =
                counters.get("sstableDataBlocksRead");

        assertEquals(
                ABSENT_COUNT,
                negative + positive
        );

        assertTrue(
                positive <= ABSENT_COUNT / 20,
                "Bloom false-positive rate exceeds 5%: "
                        + positive + "/" + ABSENT_COUNT
        );

        assertTrue(
                blockReads <= 2L * positive,
                "Negative Bloom probes must not read blocks"
        );
    }
}
