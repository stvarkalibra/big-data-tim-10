
package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class Cp1Sst003 {

    public static final String ID = "CP1-SST-003";

    private static final int RECORD_COUNT = 10_000;
    private static final int VALUE_LENGTH = 256;

    private Cp1Sst003() {
    }

    public static void run(long seed, Report report)
            throws Exception {

        Path directory = Files.createTempDirectory(
                "cp1-sst-003-" + seed + "-"
        );

        try {
            List<BlockCodec.Record> records =
                    new ArrayList<>(RECORD_COUNT);

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
                            703,
                            records.iterator(),
                            records.size()
                    );

            SSTableMeta meta = written.meta();

            if (meta.entryCount() != RECORD_COUNT) {
                throw new AssertionError(
                        "Incorrect SSTable entry count"
                );
            }

            if (meta.sizeBytes() <= 1_000_000) {
                throw new AssertionError(
                        "SSTable is not sufficiently large"
                );
            }

            Path file = directory.resolve(
                    meta.file()
            );

            Files.write(file, written.bytes());

            StorageStats stats = new StorageStats();

            DiskSSTableTableSource table =
                    DiskSSTableTableSource.open(
                            file,
                            meta,
                            stats
                    );

            for (int number : new int[]{
                    0, 1, 100,
                    5000, 5001,
                    9500, 9998, 9999
            }) {
                verifyGet(table, stats, seed, number);
            }

            verifyMissing(table, stats, "key05000x");
            verifyMissing(table, stats, "key09998x");

            // Reopen the file with fresh counters.
            StorageStats reopenedStats =
                    new StorageStats();

            DiskSSTableTableSource reopened =
                    DiskSSTableTableSource.open(
                            file,
                            meta,
                            reopenedStats
                    );

            verifyGet(
                    reopened,
                    reopenedStats,
                    seed,
                    9876
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
        return String.format("key%05d", number);
    }

    private static byte[] value(
            long seed,
            int number) {

        byte[] result = new byte[VALUE_LENGTH];

        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (
                    seed + number * 31L + i
            );
        }

        return result;
    }

    private static long counter(
            Map<String, Long> counters,
            String name) {

        Long value = counters.get(name);

        if (value == null) {
            throw new AssertionError(
                    "Missing statistics counter: " + name
            );
        }

        return value;
    }

    private static void verifyGet(
            DiskSSTableTableSource table,
            StorageStats stats,
            long seed,
            int number) throws Exception {

        stats.reset();

        InternalRecord actual =
                table.get(key(number))
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Missing key " + key(number)
                                )
                        );

        if (!actual.key().equals(key(number))) {
            throw new AssertionError(
                    "Wrong key returned"
            );
        }

        if (actual.sequence() != number + 1L) {
            throw new AssertionError(
                    "Wrong sequence for " + key(number)
            );
        }

        if (!Arrays.equals(
                actual.value(),
                value(seed, number))) {

            throw new AssertionError(
                    "Wrong value for " + key(number)
            );
        }

        Map<String, Long> counters =
                stats.snapshot();

        long blocks = counter(
                counters,
                "sstableDataBlocksRead"
        );

        long bytes = counter(
                counters,
                "sstableDataBytesRead"
        );

        if (blocks < 1 || blocks > 2) {
            throw new AssertionError(
                    "Indexed GET read " + blocks
                            + " blocks for " + key(number)
            );
        }

        if (bytes <= 0 || bytes >= 12_000) {
            throw new AssertionError(
                    "Indexed GET read too many bytes: "
                            + bytes
            );
        }

        if (counter(
                counters,
                "bloomPositiveProbes") != 1L) {

            throw new AssertionError(
                    "Expected one positive Bloom probe"
            );
        }

        if (counter(
                counters,
                "rangeScanBlocksRead") != 0L) {

            throw new AssertionError(
                    "Point GET triggered range scanning"
            );
        }
    }

    private static void verifyMissing(
            DiskSSTableTableSource table,
            StorageStats stats,
            String missingKey) throws Exception {

        stats.reset();

        if (table.get(missingKey).isPresent()) {
            throw new AssertionError(
                    "Missing key was found: " + missingKey
            );
        }

        Map<String, Long> counters =
                stats.snapshot();

        long negative = counter(
                counters,
                "bloomNegativeSkips"
        );

        long positive = counter(
                counters,
                "bloomPositiveProbes"
        );

        long blocks = counter(
                counters,
                "sstableDataBlocksRead"
        );

        if (negative + positive != 1L) {
            throw new AssertionError(
                    "Expected one Bloom probe"
            );
        }

        if (blocks > 2) {
            throw new AssertionError(
                    "Missing key caused excessive block reads: "
                            + blocks
            );
        }

        if (negative == 1L && blocks != 0L) {
            throw new AssertionError(
                    "Bloom-negative GET read data blocks"
            );
        }
    }
}
