
package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.BloomFilter;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class Cp1Sst002 {

    public static final String ID = "CP1-SST-002";

    private static final int STORED_COUNT = 2000;
    private static final int ABSENT_COUNT = 10_000;

    private Cp1Sst002() {
    }

    public static void run(long seed, Report report)
            throws Exception {

        Path directory = Files.createTempDirectory(
                "cp1-sst-002-" + seed + "-"
        );

        try {
            List<BlockCodec.Record> records =
                    prepareRecords(seed);

            SSTableWriter.Result written =
                    SSTableWriter.build(
                            602,
                            records.iterator(),
                            records.size()
                    );

            Path file = directory.resolve(
                    written.meta().file()
            );

            Files.write(file, written.bytes());

            StorageStats stats = new StorageStats();

            DiskSSTableTableSource reader =
                    DiskSSTableTableSource.open(
                            file,
                            written.meta(),
                            stats
                    );

            verifyStoredRecords(records, reader);
            verifyBloomDirectly(records);
            verifyAbsentKeys(reader, stats);

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

    private static List<BlockCodec.Record> prepareRecords(
            long seed) {

        List<BlockCodec.Record> records =
                new ArrayList<>();

        // First physical key.
        records.add(
                new BlockCodec.Record(
                        "k00000",
                        1L,
                        BlockCodec.PUT,
                        new byte[]{1}
                )
        );

        // 2000 even-numbered keys.
        for (int i = 1; i <= STORED_COUNT; i++) {

            String key = String.format(
                    "k%05d",
                    i * 2
            );

            byte kind = i % 11 == 0
                    ? BlockCodec.DELETE
                    : BlockCodec.PUT;

            byte[] value;

            if (kind == BlockCodec.DELETE) {
                value = null;
            } else if (i % 7 == 0) {
                value = new byte[0];
            } else {
                value = new byte[]{
                        (byte) (seed + i),
                        (byte) (seed ^ i)
                };
            }

            records.add(
                    new BlockCodec.Record(
                            key,
                            i + 1L,
                            kind,
                            value
                    )
            );
        }

        // Last physical key ensures that all absent
        // keys are strictly inside the table range.
        records.add(
                new BlockCodec.Record(
                        "k99999",
                        STORED_COUNT + 2L,
                        BlockCodec.PUT,
                        new byte[]{9}
                )
        );

        return records;
    }

    private static void verifyStoredRecords(
            List<BlockCodec.Record> records,
            DiskSSTableTableSource reader)
            throws Exception {

        for (BlockCodec.Record expected : records) {

            InternalRecord actual =
                    reader.get(expected.key())
                            .orElseThrow(
                                    () -> new AssertionError(
                                            "Bloom false negative or "
                                                    + "missing physical key: "
                                                    + expected.key()
                                    )
                            );

            if (!expected.key().equals(actual.key())) {
                throw new AssertionError(
                        "Wrong key: " + actual.key()
                );
            }

            if (expected.sequence()
                    != actual.sequence()) {

                throw new AssertionError(
                        "Wrong sequence for "
                                + expected.key()
                );
            }

            Kind expectedKind =
                    expected.kind() == BlockCodec.DELETE
                            ? Kind.DELETE
                            : Kind.PUT;

            if (actual.kind() != expectedKind) {
                throw new AssertionError(
                        "Wrong record kind for "
                                + expected.key()
                );
            }

            if (expectedKind == Kind.PUT
                    && !Arrays.equals(
                    expected.value(),
                    actual.value())) {

                throw new AssertionError(
                        "Wrong value for "
                                + expected.key()
                );
            }
        }
    }

    private static void verifyBloomDirectly(
            List<BlockCodec.Record> records) {

        BloomFilter bloom =
                new BloomFilter(records.size());

        // Independent expected set comes from the
        // fixture, not from the production Bloom code.
        for (BlockCodec.Record record : records) {
            bloom.add(record.key());
        }

        for (BlockCodec.Record record : records) {

            if (!bloom.mightContain(record.key())) {
                throw new AssertionError(
                        "Direct Bloom false negative: "
                                + record.key()
                );
            }
        }
    }

    private static void verifyAbsentKeys(
            DiskSSTableTableSource reader,
            StorageStats stats) throws Exception {

        // Reset AFTER checking the stored keys.
        stats.reset();

        /*
         * Absent keys:
         *
         * k40001, k40003, ... k59999
         *
         * All are:
         * - unique
         * - absent from the fixture
         * - strictly inside [k00000, k99999]
         */

        for (int i = 0; i < ABSENT_COUNT; i++) {

            String absent = String.format(
                    "k%05d",
                    40001 + 2 * i
            );

            if (reader.get(absent).isPresent()) {
                throw new AssertionError(
                        "Absent key unexpectedly found: "
                                + absent
                );
            }
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

        if (negative + positive != ABSENT_COUNT) {
            throw new AssertionError(
                    "Expected " + ABSENT_COUNT
                            + " Bloom probes, got "
                            + (negative + positive)
            );
        }

        if (positive > ABSENT_COUNT / 20) {
            throw new AssertionError(
                    "False-positive rate above 5%: "
                            + positive + "/"
                            + ABSENT_COUNT
            );
        }

        if (reads > 2L * positive) {
            throw new AssertionError(
                    "Bloom-negative queries read blocks. "
                            + "positive=" + positive
                            + ", reads=" + reads
            );
        }

        // Require that the filter actually rejects
        // absent keys instead of always saying positive.
        if (negative == 0L) {
            throw new AssertionError(
                    "Bloom filter rejected no absent keys"
            );
        }
    }
}
