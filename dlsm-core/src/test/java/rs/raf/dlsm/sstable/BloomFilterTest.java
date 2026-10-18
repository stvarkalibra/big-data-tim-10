
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    @Test
    void newFilterShouldHaveMinimum64Bits() {
        BloomFilter filter = new BloomFilter(0);

        assertEquals(64, filter.bitCount());
        assertEquals(7, filter.hashFunctionCount());
    }

    @Test
    void filterShouldCalculateCorrectSize() {
        BloomFilter filter = new BloomFilter(100);

        assertEquals(1000, filter.bitCount());
    }

    @Test
    void insertedKeysMustAlwaysBeFound() {
        BloomFilter filter = new BloomFilter(100);

        filter.add("kimi");
        filter.add("matej");
        filter.add("mladen");

        assertTrue(filter.mightContain("kimi"));
        assertTrue(filter.mightContain("matej"));
        assertTrue(filter.mightContain("mladen"));
    }

    @Test
    void emptyFilterShouldRejectKeys() {
        BloomFilter filter = new BloomFilter(100);

        assertFalse(filter.mightContain("nepostojeci"));
    }

    @Test
    void serializationShouldPreserveData() {
        BloomFilter original = new BloomFilter(100);
        original.add("kimi");
        original.add("test");

        byte[] serialized = original.toByteArray();

        BloomFilter restored =
                BloomFilter.fromByteArray(serialized);

        assertTrue(restored.mightContain("kimi"));
        assertTrue(restored.mightContain("test"));
        assertEquals(original.bitCount(), restored.bitCount());
    }

    @Test
    void shouldHandleManyKeysWithoutFalseNegatives() {
        BloomFilter filter = new BloomFilter(1000);

        for (int i = 0; i < 1000; i++) {
            filter.add("key_" + i);
        }

        for (int i = 0; i < 1000; i++) {
            assertTrue(filter.mightContain("key_" + i));
        }
    }

    @Test
    void shouldRejectInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new BloomFilter(-1));

        BloomFilter filter = new BloomFilter(10);

        assertThrows(IllegalArgumentException.class,
                () -> filter.add(null));

        assertThrows(IllegalArgumentException.class,
                () -> filter.add(""));
    }
}
