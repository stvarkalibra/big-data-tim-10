
package rs.raf.dlsm.sstable;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterStatisticalTest {

    private static final int KEY_COUNT = 10_000;
    private static final int ABSENT_KEY_COUNT = 10_000;
    private static final double MAX_FALSE_POSITIVE_RATE = 0.05;

    private static String insertedKey(int i) {
        return String.format(Locale.ROOT, "present-%08d", i);
    }

    private static String absentKey(int i) {
        return String.format(Locale.ROOT, "missing-%08d", i);
    }

    private static BloomFilter populatedFilter() {
        BloomFilter filter = new BloomFilter(KEY_COUNT);

        for (int i = 0; i < KEY_COUNT; i++) {
            filter.add(insertedKey(i));
        }

        return filter;
    }

    @Test
    void neverReturnsFalseNegativeForInsertedKeys() {
        BloomFilter filter = populatedFilter();

        for (int i = 0; i < KEY_COUNT; i++) {
            assertTrue(
                    filter.mightContain(insertedKey(i)),
                    "False negative for " + insertedKey(i)
            );
        }
    }

    @Test
    void falsePositiveRateDoesNotExceedFivePercent() {
        BloomFilter filter = populatedFilter();

        int falsePositives = 0;

        for (int i = 0; i < ABSENT_KEY_COUNT; i++) {
            if (filter.mightContain(absentKey(i))) {
                falsePositives++;
            }
        }

        double rate = (double) falsePositives / ABSENT_KEY_COUNT;

        assertTrue(
                rate <= MAX_FALSE_POSITIVE_RATE,
                String.format(
                        Locale.ROOT,
                        "False positive rate %.4f exceeds %.4f (%d/%d)",
                        rate,
                        MAX_FALSE_POSITIVE_RATE,
                        falsePositives,
                        ABSENT_KEY_COUNT
                )
        );
    }

    @Test
    void serializationPreservesAllInsertedKeys() {
        BloomFilter original = populatedFilter();

        byte[] serialized = original.serialize();
        BloomFilter restored = BloomFilter.deserialize(serialized);

        for (int i = 0; i < KEY_COUNT; i++) {
            assertTrue(
                    restored.mightContain(insertedKey(i)),
                    "Lost key after serialization: " + insertedKey(i)
            );
        }
    }

    @Test
    void serializedFilterHasSameAnswers() {
        BloomFilter original = populatedFilter();

        BloomFilter restored =
                BloomFilter.deserialize(original.serialize());

        for (int i = 0; i < ABSENT_KEY_COUNT; i++) {
            String key = absentKey(i);

            assertEquals(
                    original.mightContain(key),
                    restored.mightContain(key),
                    "Different result after serialization for " + key
            );
        }
    }

    @Test
    void serializationIsDeterministic() {
        BloomFilter first = populatedFilter();
        BloomFilter second = populatedFilter();

        assertArrayEquals(
                first.serialize(),
                second.serialize()
        );
    }
}
