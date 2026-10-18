
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class SSTableIteratorTest {

    private SSTableWriter.Result createTable(int count) {
        List<BlockCodec.Record> records = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%05d", i),
                    i + 1,
                    BlockCodec.PUT,
                    new byte[100]));
        }

        return SSTableWriter.build(
                1, records.iterator(), records.size());
    }

    @Test
    void iteratesOverAllRecords() {
        var table = createTable(100);

        try (var iterator = new SSTableIterator(
                table.bytes(), table.meta(), "key00000")) {

            int count = 0;

            while (iterator.hasNext()) {
                assertEquals(
                        String.format("key%05d", count),
                        iterator.next().key());
                count++;
            }

            assertEquals(100, count);
        }
    }

    @Test
    void startsFromRequestedKey() {
        var table = createTable(100);

        try (var iterator = new SSTableIterator(
                table.bytes(), table.meta(), "key00050")) {

            assertEquals("key00050", iterator.next().key());
        }
    }

    @Test
    void startsFromNextAvailableKey() {
        var table = createTable(100);

        try (var iterator = new SSTableIterator(
                table.bytes(), table.meta(), "key00050a")) {

            assertEquals("key00051", iterator.next().key());
        }
    }

    @Test
    void returnsNothingAfterLastKey() {
        var table = createTable(100);

        try (var iterator = new SSTableIterator(
                table.bytes(), table.meta(), "zzzz")) {

            assertFalse(iterator.hasNext());
        }
    }

    @Test
    void readsAcrossMultipleBlocks() {
        var table = createTable(500);

        int count = 0;

        try (var iterator = new SSTableIterator(
                table.bytes(), table.meta(), "key00250")) {

            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
        }

        assertEquals(250, count);
    }

    @Test
    void closeStopsIteration() {
        var table = createTable(10);

        var iterator = new SSTableIterator(
                table.bytes(), table.meta(), "key00000");

        iterator.close();

        assertFalse(iterator.hasNext());

        assertThrows(NoSuchElementException.class,
                iterator::next);
    }

    @Test
    void nextAfterEndThrowsException() {
        var table = createTable(1);

        try (var iterator = new SSTableIterator(
                table.bytes(), table.meta(), "key00000")) {

            iterator.next();

            assertThrows(NoSuchElementException.class,
                    iterator::next);
        }
    }

    @Test
    void rejectsInvalidStartKey() {
        var table = createTable(10);

        assertThrows(IllegalArgumentException.class,
                () -> new SSTableIterator(
                        table.bytes(), table.meta(), ""));
    }
}
