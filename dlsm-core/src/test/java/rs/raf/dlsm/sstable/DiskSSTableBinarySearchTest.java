
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiskSSTableBinarySearchTest {

    @TempDir
    Path directory;

    private record Fixture(
            DiskSSTableTableSource source,
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
                42,
                records.iterator(),
                records.size()
        );

        Path path = directory.resolve(table.meta().file());
        Files.write(path, table.bytes());

        StorageStats stats = new StorageStats();

        DiskSSTableTableSource source =
                DiskSSTableTableSource.open(
                        path,
                        table.meta(),
                        stats
                );

        return new Fixture(source, stats);
    }

    private static String key(int number) {
        return String.format("key%05d", number);
    }

    @Test
    void startsAtFirstKey() throws Exception {
        Fixture fixture = create(3000);

        Iterator<InternalRecord> iterator =
                fixture.source().iterator(key(0));

        assertTrue(iterator.hasNext());
        assertEquals(key(0), iterator.next().key());

        assertEquals(
                1L,
                fixture.stats().snapshot()
                        .get("rangeScanBlocksRead")
        );
    }

    @Test
    void startsAtExactKeyNearMiddle() throws Exception {
        Fixture fixture = create(3000);

        Iterator<InternalRecord> iterator =
                fixture.source().iterator(key(1500));

        assertTrue(iterator.hasNext());
        assertEquals(key(1500), iterator.next().key());

        assertEquals(
                1501L,
                fixture.source().get(key(1500))
                        .orElseThrow().sequence()
        );
    }

    @Test
    void startsNearEndWithoutReadingPreviousBlocks()
            throws Exception {

        Fixture fixture = create(3000);

        Iterator<InternalRecord> iterator =
                fixture.source().iterator(key(2990));

        List<String> found = new ArrayList<>();

        while (iterator.hasNext()) {
            found.add(iterator.next().key());
        }

        assertEquals(10, found.size());
        assertEquals(key(2990), found.get(0));
        assertEquals(key(2999), found.get(9));

        // The iterator begins in the correct sparse-index
        // block rather than scanning all preceding blocks.
        assertEquals(
                1L,
                fixture.stats().snapshot()
                        .get("rangeScanBlocksRead")
        );
    }

    @Test
    void startsBeforeFirstKey() throws Exception {
        Fixture fixture = create(100);

        Iterator<InternalRecord> iterator =
                fixture.source().iterator("aaa");

        assertTrue(iterator.hasNext());
        assertEquals(key(0), iterator.next().key());
    }

    @Test
    void returnsEmptyIteratorAfterLastKey()
            throws Exception {

        Fixture fixture = create(100);

        Iterator<InternalRecord> iterator =
                fixture.source().iterator("zzz");

        assertFalse(iterator.hasNext());

        assertEquals(
                0L,
                fixture.stats().snapshot()
                        .get("rangeScanBlocksRead")
        );
    }

    @Test
    void scansAcrossBlockBoundariesInOrder()
            throws Exception {

        Fixture fixture = create(3000);

        Iterator<InternalRecord> iterator =
                fixture.source().iterator(key(1000));

        for (int i = 1000; i < 1100; i++) {
            assertTrue(iterator.hasNext());

            InternalRecord record = iterator.next();

            assertEquals(key(i), record.key());
            assertEquals(i + 1L, record.sequence());
        }
    }

    @Test
    void nonExistingStartKeyUsesCorrectNextRecord()
            throws Exception {

        Fixture fixture = create(3000);

        Iterator<InternalRecord> iterator =
                fixture.source().iterator("key01500a");

        assertTrue(iterator.hasNext());
        assertEquals(key(1501), iterator.next().key());
    }
}
