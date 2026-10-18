
package rs.raf.dlsm.stats;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class StorageStatsConcurrencyTest {

    @Test
    void incrementAndAddWork() {
        StorageStats stats = new StorageStats();

        stats.increment("walAppendAttempts");
        stats.add("walBytesWritten", 1024);

        Map<String, Long> snapshot = stats.snapshot();

        assertEquals(1L, snapshot.get("walAppendAttempts"));
        assertEquals(1024L, snapshot.get("walBytesWritten"));
    }

    @Test
    void resetClearsCountersAndIncrementsEpoch() {
        StorageStats stats = new StorageStats();

        stats.add("walBytesWritten", 500);
        stats.increment("flushAttempts");

        assertEquals(1L, stats.reset());

        Map<String, Long> snapshot = stats.snapshot();

        assertEquals(0L, snapshot.get("walBytesWritten"));
        assertEquals(0L, snapshot.get("flushAttempts"));
        assertEquals(1L, snapshot.get("resetEpoch"));

        assertEquals(2L, stats.reset());
    }

    @Test
    void concurrentIncrementsAreNotLost() throws Exception {
        StorageStats stats = new StorageStats();

        int threads = 8;
        int incrementsPerThread = 1000;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();

                    for (int j = 0; j < incrementsPerThread; j++) {
                        stats.increment("sstableDataBlocksRead");
                    }

                    return null;
                }));
            }

            start.countDown();

            for (Future<?> future : futures) {
                future.get();
            }

            assertEquals(
                    8000L,
                    stats.snapshot().get("sstableDataBlocksRead")
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentResetAndIncrementRemainUsable() throws Exception {
        StorageStats stats = new StorageStats();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < 3; i++) {
                futures.add(executor.submit(() -> {
                    start.await();

                    for (int j = 0; j < 1000; j++) {
                        stats.increment("bloomPositiveProbes");
                    }

                    return null;
                }));
            }

            futures.add(executor.submit(() -> {
                start.await();

                for (int j = 0; j < 100; j++) {
                    stats.reset();
                    stats.snapshot();
                }

                return null;
            }));

            start.countDown();

            for (Future<?> future : futures) {
                future.get();
            }

            Map<String, Long> snapshot = stats.snapshot();

            assertEquals(100L, snapshot.get("resetEpoch"));
            assertTrue(snapshot.get("bloomPositiveProbes") >= 0);
            assertTrue(snapshot.get("bloomPositiveProbes") <= 3000);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidCounterOperations() {
        StorageStats stats = new StorageStats();

        assertThrows(IllegalArgumentException.class,
                () -> stats.increment("nonexistent"));

        assertThrows(IllegalArgumentException.class,
                () -> stats.add("walBytesWritten", -1));

        assertThrows(NullPointerException.class,
                () -> stats.increment(null));
    }

    @Test
    void detectsCounterOverflow() {
        StorageStats stats = new StorageStats();

        stats.add("walBytesWritten", Long.MAX_VALUE);

        assertThrows(ArithmeticException.class,
                () -> stats.increment("walBytesWritten"));
    }
}
