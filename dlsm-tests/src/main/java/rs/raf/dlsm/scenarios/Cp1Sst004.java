
package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.sstable.SparseIndex;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

public final class Cp1Sst004 {

    public static final String ID = "CP1-SST-004";

    private static final int COUNT = 20_000;
    private static final int VALUE_SIZE = 256;

    private Cp1Sst004() {
    }

    public static void run(long seed, Report report)
            throws Exception {

        Path dir = Files.createTempDirectory(
                "cp1-sst-004-" + seed + "-"
        );

        try {
            List<BlockCodec.Record> records =
                    new ArrayList<>(COUNT);

            for (int i = 0; i < COUNT; i++) {
                records.add(new BlockCodec.Record(
                        key(i),
                        i + 1L,
                        BlockCodec.PUT,
                        value(seed, i)
                ));
            }

            SSTableWriter.Result result =
                    SSTableWriter.build(
                            704,
                            records.iterator(),
                            records.size()
                    );

            byte[] bytes = result.bytes();

            Path file = dir.resolve(
                    result.meta().file()
            );

            Files.write(file, bytes);

            SparseIndex index = readIndex(bytes);

            check(
                    index.size() >= 200,
                    "Expected at least 200 data blocks"
            );

            StorageStats stats = new StorageStats();

            DiskSSTableTableSource reader =
                    DiskSSTableTableSource.open(
                            file,
                            result.meta(),
                            stats
                    );

            verifyRange(
                    reader, stats, index,
                    seed, 19_003, 20
            );

            verifyRange(
                    reader, stats, index,
                    seed, 19_970, 20
            );

            verifyLastRecord(
                    reader, stats, seed
            );

            report.pass();

        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths
                        .sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static SparseIndex readIndex(
            byte[] bytes) {

        int footerStart =
                bytes.length - SSTableWriter.FOOTER_BYTES;

        ByteBuffer footer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN);

        long offset = footer.getLong(
                footerStart + 32
        );

        long length = footer.getLong(
                footerStart + 40
        );

        check(
                offset >= 0 && length > 0
                        && offset + length <= footerStart,
                "Invalid index region"
        );

        byte[] indexBytes = Arrays.copyOfRange(
                bytes,
                Math.toIntExact(offset),
                Math.toIntExact(offset + length)
        );

        return SparseIndex.deserialize(indexBytes);
    }

    private static int blockPosition(
            SparseIndex index,
            String key) {

        List<SparseIndex.Entry> entries =
                index.entries();

        int low = 0;
        int high = entries.size() - 1;
        int result = 0;

        while (low <= high) {
            int middle = low + (high - low) / 2;

            if (entries.get(middle)
                    .firstKey().compareTo(key) <= 0) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return result;
    }

    private static void verifyRange(
            DiskSSTableTableSource reader,
            StorageStats stats,
            SparseIndex index,
            long seed,
            int first,
            int count) throws Exception {

        String start = key(first);
        String endExclusive = key(first + count);

        int firstBlock = blockPosition(index, start);
        int lastBlock = blockPosition(
                index, key(first + count - 1)
        );

        check(
                firstBlock >= index.size() * 0.9,
                "SCAN does not start in final 10%"
        );

        long maxBlocks =
                lastBlock - firstBlock + 2L;

        stats.reset();

        Iterator<InternalRecord> iterator =
                reader.iterator(start);

        List<InternalRecord> results =
                new ArrayList<>();

        while (iterator.hasNext()
                && results.size() < count) {

            InternalRecord item = iterator.next();

            if (item.key()
                    .compareTo(endExclusive) >= 0) {
                break;
            }

            if (item.kind() == Kind.PUT) {
                results.add(item);
            }
        }

        check(
                results.size() == count,
                "Expected 20 records, got "
                        + results.size()
        );

        for (int i = 0; i < count; i++) {

            InternalRecord actual = results.get(i);
            int expected = first + i;

            check(
                    actual.key().equals(key(expected)),
                    "Incorrect key at position " + i
            );

            check(
                    actual.sequence() == expected + 1L,
                    "Incorrect sequence"
            );

            check(
                    Arrays.equals(
                            actual.value(),
                            value(seed, expected)
                    ),
                    "Incorrect value"
            );

            check(
                    actual.key().compareTo(endExclusive) < 0,
                    "End boundary violated"
            );

            if (i > 0) {
                check(
                        results.get(i - 1).key()
                                .compareTo(actual.key()) < 0,
                        "Unsorted or duplicate result"
                );
            }
        }

        Map<String, Long> counters =
                stats.snapshot();

        long rangeBlocks =
                counters.get("rangeScanBlocksRead");

        long allBlocks =
                counters.get("sstableDataBlocksRead");

        check(
                rangeBlocks >= 1
                        && rangeBlocks <= maxBlocks,
                "Exceeded expected block range: "
                        + rangeBlocks + "/" + maxBlocks
        );

        check(
                rangeBlocks == allBlocks,
                "Unexpected non-SCAN block reads"
        );

        check(
                rangeBlocks < index.size() / 10,
                "SCAN read too much of SSTable"
        );

        check(
                counters.get("bloomPositiveProbes") == 0
                        && counters.get("bloomNegativeSkips") == 0,
                "SCAN unexpectedly used Bloom filter"
        );
    }

    private static void verifyLastRecord(
            DiskSSTableTableSource reader,
            StorageStats stats,
            long seed) throws Exception {

        stats.reset();

        Iterator<InternalRecord> iterator =
                reader.iterator(key(19_999));

        check(
                iterator.hasNext(),
                "Last record missing"
        );

        InternalRecord record = iterator.next();

        check(
                record.key().equals(key(19_999)),
                "Wrong final key"
        );

        check(
                Arrays.equals(
                        record.value(),
                        value(seed, 19_999)
                ),
                "Wrong final value"
        );

        check(
                !iterator.hasNext(),
                "Unexpected record after EOF"
        );

        check(
                stats.snapshot().get(
                        "rangeScanBlocksRead") <= 2L,
                "Last record read too many blocks"
        );
    }

    private static String key(int number) {
        return String.format("key%05d", number);
    }

    private static byte[] value(
            long seed,
            int number) {

        byte[] bytes = new byte[VALUE_SIZE];

        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (
                    seed + number + i * 17L
            );
        }

        return bytes;
    }

    private static void check(
            boolean condition,
            String message) {

        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
