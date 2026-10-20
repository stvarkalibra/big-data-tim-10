
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SSTableLargeRangeScanTest {

    private static final int RECORD_COUNT = 20_000;
    private static final int VALUE_LENGTH = 256;

    @TempDir
    Path directory;

    private static String key(int number) {
        return String.format("key%05d", number);
    }

    private static byte[] value(int number) {
        byte[] result = new byte[VALUE_LENGTH];

        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (number + i * 17);
        }

        return result;
    }

    private record Fixture(
            DiskSSTableTableSource reader,
            StorageStats stats,
            SparseIndex index
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
                        702,
                        records.iterator(),
                        records.size()
                );

        byte[] bytes = result.bytes();

        Path file = directory.resolve(
                result.meta().file()
        );

        Files.write(file, bytes);

        // Decode the real sparse index from the
        // SSTable footer and index region.
        ByteBuffer footer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN);

        footer.position(
                bytes.length
                        - SSTableWriter.FOOTER_BYTES
                        + 32
        );

        long indexOffset = footer.getLong();
        long indexLength = footer.getLong();

        assertTrue(indexOffset >= 0);
        assertTrue(indexLength > 0);

        assertTrue(
                indexOffset + indexLength
                        <= bytes.length
                        - SSTableWriter.FOOTER_BYTES
        );

        byte[] indexBytes = new byte[
                Math.toIntExact(indexLength)
                ];

        System.arraycopy(
                bytes,
                Math.toIntExact(indexOffset),
                indexBytes,
                0,
                indexBytes.length
        );

        SparseIndex index =
                SparseIndex.deserialize(indexBytes);

        assertTrue(
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

        return new Fixture(reader, stats, index);
    }

    private static int blockPosition(
            SparseIndex index,
            String requestedKey) {

        List<SparseIndex.Entry> entries =
                index.entries();

        int left = 0;
        int right = entries.size() - 1;
        int result = 0;

        while (left <= right) {
            int middle = left + (right - left) / 2;

            if (entries.get(middle)
                    .firstKey()
                    .compareTo(requestedKey) <= 0) {

                result = middle;
                left = middle + 1;
            } else {
                right = middle - 1;
            }
        }

        return result;
    }

    private static void verifyRange(
            Fixture fixture,
            int firstNumber,
            int count) throws Exception {

        String start = key(firstNumber);
        String endExclusive = key(firstNumber + count);

        int startBlock = blockPosition(
                fixture.index(), start
        );

        int lastBlock = blockPosition(
                fixture.index(),
                key(firstNumber + count - 1)
        );

        // The real required range plus one
        // documented boundary block.
        long maxAllowedBlocks =
                lastBlock - startBlock + 2L;

        assertTrue(
                startBlock >= fixture.index().size() * 0.9,
                "Range must start in the final 10% of table"
        );

        fixture.stats().reset();

        Iterator<InternalRecord> iterator =
                fixture.reader().iterator(start);

        List<InternalRecord> received =
                new ArrayList<>();

        while (iterator.hasNext()
                && received.size() < count) {

            InternalRecord record = iterator.next();

            if (record.key().compareTo(endExclusive) >= 0) {
                break;
            }

            if (record.kind() == Kind.PUT) {
                received.add(record);
            }
        }

        assertEquals(
                count,
                received.size(),
                "SCAN returned incorrect number of records"
        );

        for (int i = 0; i < count; i++) {
            int expectedNumber = firstNumber + i;

            InternalRecord actual = received.get(i);

            assertEquals(
                    key(expectedNumber),
                    actual.key()
            );

            assertEquals(
                    expectedNumber + 1L,
                    actual.sequence()
            );

            assertEquals(
                    Kind.PUT,
                    actual.kind()
            );

            assertArrayEquals(
                    value(expectedNumber),
                    actual.value()
            );

            assertTrue(
                    actual.key().compareTo(endExclusive) < 0
            );

            if (i > 0) {
                assertTrue(
                        received.get(i - 1).key()
                                .compareTo(actual.key()) < 0
                );
            }
        }

        Map<String, Long> counters =
                fixture.stats().snapshot();

        long rangeBlocks =
                counters.get("rangeScanBlocksRead");

        long totalBlocks =
                counters.get("sstableDataBlocksRead");

        long dataBytes =
                counters.get("sstableDataBytesRead");

        assertTrue(
                rangeBlocks >= 1
                        && rangeBlocks <= maxAllowedBlocks,
                "Expected at most "
                        + maxAllowedBlocks
                        + " blocks, read "
                        + rangeBlocks
        );

        assertEquals(
                rangeBlocks,
                totalBlocks,
                "All reads in this test must belong to SCAN"
        );

        assertTrue(
                rangeBlocks < fixture.index().size() / 10,
                "SCAN is reading too much of the SSTable"
        );

        assertTrue(
                dataBytes > 0
                        && dataBytes < 50_000,
                "SCAN read too many data bytes: "
                        + dataBytes
        );

        assertEquals(
                0L,
                counters.get("bloomPositiveProbes")
        );

        assertEquals(
                0L,
                counters.get("bloomNegativeSkips")
        );
    }

    @Test
    void scanStartsNearEndWithoutReadingEarlierBlocks()
            throws Exception {

        Fixture fixture = createLargeTable();

        verifyRange(fixture, 19_003, 20);
    }

    @Test
    void scanNearPhysicalEndReturnsExactTwentyRecords()
            throws Exception {

        Fixture fixture = createLargeTable();

        verifyRange(fixture, 19_970, 20);
    }

    @Test
    void scanCanStartAtLastRecord()
            throws Exception {

        Fixture fixture = createLargeTable();

        fixture.stats().reset();

        Iterator<InternalRecord> iterator =
                fixture.reader().iterator(key(19_999));

        assertTrue(iterator.hasNext());

        InternalRecord finalRecord = iterator.next();

        assertEquals(
                key(19_999),
                finalRecord.key()
        );

        assertArrayEquals(
                value(19_999),
                finalRecord.value()
        );

        assertFalse(iterator.hasNext());

        Map<String, Long> counters =
                fixture.stats().snapshot();

        assertTrue(
                counters.get("rangeScanBlocksRead") <= 2
        );
    }
}
