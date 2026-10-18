
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SparseIndexTest {

    @Test
    void emptyIndexReturnsNull() {
        SparseIndex index = new SparseIndex();
        assertNull(index.findBlock("kimi"));
    }

    @Test
    void findsCorrectBlock() {
        SparseIndex index = new SparseIndex();

        index.add("anna", 100, 50);
        index.add("marko", 150, 60);
        index.add("zika", 210, 70);

        assertEquals("anna", index.findBlock("kimi").firstKey());
        assertEquals("marko", index.findBlock("matej").firstKey());
        assertEquals("zika", index.findBlock("zika").firstKey());
    }

    @Test
    void returnsNullBeforeFirstKey() {
        SparseIndex index = new SparseIndex();
        index.add("marko", 100, 50);

        assertNull(index.findBlock("ana"));
    }

    @Test
    void rejectsDuplicateKeys() {
        SparseIndex index = new SparseIndex();
        index.add("kimi", 100, 50);

        assertThrows(IllegalArgumentException.class,
                () -> index.add("kimi", 150, 50));
    }

    @Test
    void rejectsUnsortedKeys() {
        SparseIndex index = new SparseIndex();
        index.add("zika", 100, 50);

        assertThrows(IllegalArgumentException.class,
                () -> index.add("ana", 150, 50));
    }

    @Test
    void rejectsOverlappingBlocks() {
        SparseIndex index = new SparseIndex();
        index.add("anna", 100, 100);

        assertThrows(IllegalArgumentException.class,
                () -> index.add("marko", 150, 50));
    }

    @Test
    void entriesCannotBeModifiedExternally() {
        SparseIndex index = new SparseIndex();
        index.add("kimi", 100, 50);

        assertThrows(UnsupportedOperationException.class,
                () -> index.entries().clear());
    }

    @Test
    void serializationRoundTrip() {
        SparseIndex original = new SparseIndex();

        original.add("anna", 100, 50);
        original.add("kimi", 150, 100);
        original.add("zika", 250, 80);

        byte[] encoded = original.serialize();
        SparseIndex restored = SparseIndex.deserialize(encoded);

        assertEquals(3, restored.size());
        assertEquals(original.entries(), restored.entries());
        assertArrayEquals(encoded, restored.serialize());
    }

    @Test
    void corruptedIndexMustBeRejected() {
        SparseIndex index = new SparseIndex();
        index.add("kimi", 100, 50);

        byte[] data = index.serialize();
        data[16] ^= 0x01;

        assertThrows(IllegalArgumentException.class,
                () -> SparseIndex.deserialize(data));
    }

    @Test
    void truncatedIndexMustBeRejected() {
        SparseIndex index = new SparseIndex();
        index.add("kimi", 100, 50);

        byte[] data = index.serialize();
        byte[] truncated = Arrays.copyOf(
                data, data.length - 1);

        assertThrows(IllegalArgumentException.class,
                () -> SparseIndex.deserialize(truncated));
    }

    @Test
    void indexMustBeDeterministic() {
        SparseIndex first = new SparseIndex();
        SparseIndex second = new SparseIndex();

        for (int i = 0; i < 100; i++) {
            String key = String.format("key%04d", i);
            first.add(key, i * 100L, 100);
            second.add(key, i * 100L, 100);
        }

        assertArrayEquals(first.serialize(), second.serialize());
    }

    @Test
    void rejectsInvalidOffsetAndLength() {
        SparseIndex index = new SparseIndex();

        assertThrows(IllegalArgumentException.class,
                () -> index.add("kimi", -1, 100));

        assertThrows(IllegalArgumentException.class,
                () -> index.add("kimi", 100, 0));

        assertThrows(IllegalArgumentException.class,
                () -> index.add("kimi", Long.MAX_VALUE, 100));
    }

    @Test
    void findsBlockAmongThousands() {
        SparseIndex index = new SparseIndex();

        for (int i = 0; i < 5000; i++) {
            index.add(String.format("key%05d", i),
                    i * 100L, 100);
        }

        assertEquals("key04999",
                index.findBlock("key04999").firstKey());

        assertEquals("key02500",
                index.findBlock("key02500").firstKey());
    }
}
