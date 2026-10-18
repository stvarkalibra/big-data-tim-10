
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.engine.TableSource;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiskSSTableStatisticsTest {

    @TempDir
    Path directory;

    private record Fixture(
            TableSource source,
            StorageStats stats
    ) {
    }

    private Fixture create(int count) throws Exception {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%05d", i),
                    i + 1L,
                    BlockCodec.PUT,
                    new byte[100]
            ));
        }

        SSTableWriter.Result table = SSTableWriter.build(
                42, records.iterator(), records.size());

        Path path = directory.resolve(table.meta().file());
        Files.write(path, table.bytes());

        StorageStats stats = new StorageStats();

        TableSource source = DiskSSTableTableSource.open(
                path, table.meta(), stats);

        return new Fixture(source, stats);
    }

    private static long counter(
            StorageStats stats,
            String name) {

        return stats.snapshot().get(name);
    }

    @Test
    void existingKeyIncrementsBloomPositiveAndBlockRead()
            throws Exception {

        Fixture fixture = create(200);

        assertTrue(fixture.source().get("key00050").isPresent());

        assertEquals(1,
                counter(fixture.stats(), "bloomPositiveProbes"));

        assertEquals(0,
                counter(fixture.stats(), "bloomNegativeSkips"));

        assertEquals(1,
                counter(fixture.stats(), "sstableDataBlocksRead"));

        assertTrue(
                counter(fixture.stats(), "sstableDataBytesRead") > 0);

        assertEquals(0,
                counter(fixture.stats(), "rangeScanBlocksRead"));
    }

    @Test
    void missingKeyInsideRangeUsesBloomWithoutBlockRead()
            throws Exception {

        Fixture fixture = create(200);

        assertTrue(fixture.source()
                .get("key00050x").isEmpty());

        long negatives = counter(
                fixture.stats(), "bloomNegativeSkips");

        long positives = counter(
                fixture.stats(), "bloomPositiveProbes");

        assertEquals(1, negatives + positives);

        if (negatives == 1) {
            assertEquals(0,
                    counter(fixture.stats(),
                            "sstableDataBlocksRead"));
        }
    }

    @Test
    void outsideKeyRangeDoesNotConsultBloom()
            throws Exception {

        Fixture fixture = create(200);

        assertTrue(fixture.source().get("aaaa").isEmpty());
        assertTrue(fixture.source().get("zzzz").isEmpty());

        assertEquals(0,
                counter(fixture.stats(), "bloomNegativeSkips"));

        assertEquals(0,
                counter(fixture.stats(), "bloomPositiveProbes"));

        assertEquals(0,
                counter(fixture.stats(), "sstableDataBlocksRead"));
    }

    @Test
    void eachPointLookupCountsOneBloomConsultation()
            throws Exception {

        Fixture fixture = create(300);

        String[] keys = {
                "key00000",
                "key00050",
                "key00100",
                "key00150",
                "key00200",
                "key00250"
        };

        for (String key : keys) {
            assertTrue(fixture.source().get(key).isPresent());
        }

        assertEquals(keys.length,
                counter(fixture.stats(), "bloomPositiveProbes"));

        assertEquals(keys.length,
                counter(fixture.stats(), "sstableDataBlocksRead"));

        assertEquals(0,
                counter(fixture.stats(), "rangeScanBlocksRead"));
    }

    @Test
    void rangeScanCountsBlocksWithoutConsultingBloom()
            throws Exception {

        Fixture fixture = create(1000);

        var iterator =
                fixture.source().iterator("key00990");

        int count = 0;

        while (iterator.hasNext()) {
            assertEquals(
                    String.format("key%05d", 990 + count),
                    iterator.next().key()
            );
            count++;
        }

        assertEquals(10, count);

        long totalBlocks = counter(
                fixture.stats(), "sstableDataBlocksRead");

        long scanBlocks = counter(
                fixture.stats(), "rangeScanBlocksRead");

        assertTrue(scanBlocks >= 1);
        assertEquals(scanBlocks, totalBlocks);

        assertEquals(0,
                counter(fixture.stats(), "bloomNegativeSkips"));

        assertEquals(0,
                counter(fixture.stats(), "bloomPositiveProbes"));
    }

    @Test
    void scanStartingAfterLastKeyReadsNoBlocks()
            throws Exception {

        Fixture fixture = create(200);

        assertFalse(fixture.source()
                .iterator("zzzz").hasNext());

        assertEquals(0,
                counter(fixture.stats(), "sstableDataBlocksRead"));

        assertEquals(0,
                counter(fixture.stats(), "rangeScanBlocksRead"));
    }

    @Test
    void resetClearsCountersAndIncrementsEpoch()
            throws Exception {

        Fixture fixture = create(100);

        fixture.source().get("key00050");

        assertEquals(1,
                counter(fixture.stats(), "bloomPositiveProbes"));

        long epoch = fixture.stats().reset();

        assertEquals(1, epoch);
        assertEquals(1,
                counter(fixture.stats(), "resetEpoch"));

        assertEquals(0,
                counter(fixture.stats(), "bloomPositiveProbes"));

        assertEquals(0,
                counter(fixture.stats(), "sstableDataBlocksRead"));

        fixture.source().get("key00050");

        assertEquals(1,
                counter(fixture.stats(), "bloomPositiveProbes"));
    }

    @Test
    void statsAreSharedBetweenMultipleTables()
            throws Exception {

        Fixture first = create(100);
        StorageStats shared = first.stats();

        List<BlockCodec.Record> records = List.of(
                new BlockCodec.Record(
                        "zeta", 1000,
                        BlockCodec.PUT, new byte[]{1})
        );

        SSTableWriter.Result second = SSTableWriter.build(
                43, records.iterator(), 1);

        Path path = directory.resolve(second.meta().file());
        Files.write(path, second.bytes());

        TableSource secondSource =
                DiskSSTableTableSource.open(
                        path, second.meta(), shared);

        first.source().get("key00050");
        secondSource.get("zeta");

        assertEquals(2,
                counter(shared, "bloomPositiveProbes"));

        assertEquals(2,
                counter(shared, "sstableDataBlocksRead"));
    }

    @Test
    void readsAfterResetAreCountedInNewEpoch()
            throws Exception {

        Fixture fixture = create(100);

        fixture.source().get("key00001");
        fixture.stats().reset();

        fixture.source().get("key00002");
        fixture.source().get("key00003");

        Map<String, Long> values =
                fixture.stats().snapshot();

        assertEquals(1L, values.get("resetEpoch"));
        assertEquals(2L, values.get("bloomPositiveProbes"));
        assertEquals(2L, values.get("sstableDataBlocksRead"));
        assertEquals(0L, values.get("rangeScanBlocksRead"));
    }

    @Test
    void pointReadsDoNotCountAsRangeReads()
            throws Exception {

        Fixture fixture = create(500);

        for (int i = 0; i < 20; i++) {
            fixture.source().get(
                    String.format("key%05d", i));
        }

        assertEquals(20,
                counter(fixture.stats(), "sstableDataBlocksRead"));

        assertEquals(0,
                counter(fixture.stats(), "rangeScanBlocksRead"));
    }
}
