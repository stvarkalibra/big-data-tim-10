
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

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

        BloomFilter restored =
                BloomFilter.fromByteArray(original.toByteArray());

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

        assertThrows(IllegalArgumentException.class,
                () -> filter.add("ključ"));

        assertThrows(IllegalArgumentException.class,
                () -> filter.add("a".repeat(129)));
    }

    @Test
    void serializationRoundTrip() {
        BloomFilter original = new BloomFilter(100);

        original.add("kimi");
        original.add("matej");
        original.add("mladen");

        byte[] bytes = original.serialize();
        BloomFilter restored = BloomFilter.deserialize(bytes);

        assertEquals(original.bitCount(), restored.bitCount());

        assertTrue(restored.mightContain("kimi"));
        assertTrue(restored.mightContain("matej"));
        assertTrue(restored.mightContain("mladen"));

        assertArrayEquals(bytes, restored.serialize());
    }

    @Test
    void corruptedBloomMustBeRejected() {
        BloomFilter filter = new BloomFilter(100);
        filter.add("kimi");

        byte[] bytes = filter.serialize();
        bytes[25] ^= 0x01;

        assertThrows(IllegalArgumentException.class,
                () -> BloomFilter.deserialize(bytes));
    }

    @Test
    void truncatedBloomMustBeRejected() {
        BloomFilter filter = new BloomFilter(100);

        byte[] bytes = filter.serialize();
        byte[] truncated = Arrays.copyOf(
                bytes, bytes.length - 1);

        assertThrows(IllegalArgumentException.class,
                () -> BloomFilter.deserialize(truncated));
    }

    @Test
    void wrongHashVersionMustBeRejected() {
        BloomFilter filter = new BloomFilter(100);
        byte[] bytes = filter.serialize();

        bytes[7] = 2;

        assertThrows(IllegalArgumentException.class,
                () -> BloomFilter.deserialize(bytes));
    }

    @Test
    void bloomMustBeDeterministic() {
        BloomFilter first = new BloomFilter(100);
        BloomFilter second = new BloomFilter(100);

        for (int i = 0; i < 100; i++) {
            String key = "key_" + i;
            first.add(key);
            second.add(key);
        }

        assertArrayEquals(first.serialize(), second.serialize());
    }

    @Test
    void falsePositiveRateMustBeAtMostFivePercent() {
        int n = 2000;
        int probes = 10000;

        BloomFilter filter = new BloomFilter(n);

        for (int i = 0; i < n; i++) {
            filter.add(String.format("k%06d", i * 20));
        }

        for (int i = 0; i < n; i++) {
            assertTrue(filter.mightContain(
                    String.format("k%06d", i * 20)));
        }

        int falsePositives = 0;

        for (int i = 0; i < probes; i++) {
            String absentKey =
                    String.format("k%06d", i * 2 + 1);

            if (filter.mightContain(absentKey)) {
                falsePositives++;
            }
        }

        double rate = (double) falsePositives / probes;

        assertTrue(rate <= 0.05,
                "False positive rate too high: " + rate);
    }
}
