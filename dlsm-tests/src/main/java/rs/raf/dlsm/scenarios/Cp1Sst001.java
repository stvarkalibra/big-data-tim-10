
package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Cp1Sst001 {

    public static final String ID = "CP1-SST-001";

    private static final int RECORD_COUNT = 300;

    private Cp1Sst001() {
    }

    public static void run(long seed, Report report)
            throws Exception {

        Path directory = Files.createTempDirectory(
                "cp1-sst-001-" + seed + "-"
        );

        try {
            List<BlockCodec.Record> records =
                    new ArrayList<>();

            for (int i = 0; i < RECORD_COUNT; i++) {
                records.add(
                        new BlockCodec.Record(
                                key(i),
                                i + 1L,
                                BlockCodec.PUT,
                                value(seed, i)
                        )
                );
            }

            SSTableWriter.Result written =
                    SSTableWriter.build(
                            101,
                            records.iterator(),
                            records.size()
                    );

            Path file = directory.resolve(
                    written.meta().file()
            );

            Files.write(file, written.bytes());

            StorageStats stats = new StorageStats();

            DiskSSTableTableSource table =
                    DiskSSTableTableSource.open(
                            file,
                            written.meta(),
                            stats
                    );

            // First record.
            verifyPresent(table, stats, seed, 0);

            // Middle record.
            verifyPresent(table, stats, seed, 150);

            // Last record.
            verifyPresent(table, stats, seed, 299);

            // Missing key inside the table's range.
            verifyAbsentInsideRange(
                    table,
                    stats,
                    "k0150x"
            );

            // Missing key outside the table's range.
            verifyAbsentOutsideRange(
                    table,
                    stats,
                    "z9999"
            );

            report.pass();

        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths
                        .sorted(Comparator.reverseOrder())
                        .toList()) {

                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String key(int number) {
        return String.format("k%04d", number);
    }

    private static byte[] value(
            long seed,
            int number) {

        byte[] result = new byte[64];

        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (
                    seed + number * 31L + i
            );
        }

        return result;
    }

    private static void verifyPresent(
            DiskSSTableTableSource table,
            StorageStats stats,
            long seed,
            int number) throws Exception {

        stats.reset();

        Optional<InternalRecord> found =
                table.get(key(number));

        if (found.isEmpty()) {
            throw new AssertionError(
                    "Missing existing key: " + key(number)
            );
        }

        InternalRecord actual = found.orElseThrow();

        if (!actual.key().equals(key(number))) {
            throw new AssertionError(
                    "Wrong key: " + actual.key()
            );
        }

        if (actual.sequence() != number + 1L) {
            throw new AssertionError(
                    "Wrong sequence for " + key(number)
            );
        }

        if (!java.util.Arrays.equals(
                value(seed, number),
                actual.value())) {

            throw new AssertionError(
                    "Wrong value for " + key(number)
            );
        }

        Map<String, Long> counters =
                stats.snapshot();

        long reads = counters.get(
                "sstableDataBlocksRead"
        );

        if (reads < 1 || reads > 2) {
            throw new AssertionError(
                    "GET " + key(number)
                            + ": expected 1..2 block reads"
                            + ", got " + reads
            );
        }

        if (counters.get("bloomPositiveProbes") != 1L) {
            throw new AssertionError(
                    "Present key did not pass Bloom"
            );
        }

        if (counters.get("bloomNegativeSkips") != 0L) {
            throw new AssertionError(
                    "Bloom rejected an existing key"
            );
        }
    }

    private static void verifyAbsentInsideRange(
            DiskSSTableTableSource table,
            StorageStats stats,
            String absentKey) throws Exception {

        stats.reset();

        if (table.get(absentKey).isPresent()) {
            throw new AssertionError(
                    "Absent key was found: " + absentKey
            );
        }

        Map<String, Long> counters =
                stats.snapshot();

        long negative = counters.get(
                "bloomNegativeSkips"
        );

        long positive = counters.get(
                "bloomPositiveProbes"
        );

        long reads = counters.get(
                "sstableDataBlocksRead"
        );

        if (negative + positive != 1L) {
            throw new AssertionError(
                    "Missing Bloom probe for in-range key"
            );
        }

        if (negative == 1L && reads != 0L) {
            throw new AssertionError(
                    "Negative Bloom probe read a data block"
            );
        }

        if (reads > 2L) {
            throw new AssertionError(
                    "Absent key required too many blocks: "
                            + reads
            );
        }
    }

    private static void verifyAbsentOutsideRange(
            DiskSSTableTableSource table,
            StorageStats stats,
            String absentKey) throws Exception {

        stats.reset();

        if (table.get(absentKey).isPresent()) {
            throw new AssertionError(
                    "Out-of-range key was found"
            );
        }

        Map<String, Long> counters =
                stats.snapshot();

        if (counters.get("sstableDataBlocksRead") != 0L) {
            throw new AssertionError(
                    "Out-of-range GET read data blocks"
            );
        }

        if (counters.get("bloomPositiveProbes") != 0L
                || counters.get("bloomNegativeSkips") != 0L) {

            throw new AssertionError(
                    "Out-of-range GET unnecessarily used Bloom"
            );
        }
    }
}
