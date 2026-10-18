
package rs.raf.dlsm.stats;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StorageStatsTest {

    @Test
    void countersShouldStartAtZero() {
        StorageStats stats = new StorageStats();

        Map<String, Long> snapshot = stats.snapshot();

        assertEquals(0L, snapshot.get("walAppendAttempts"));
        assertEquals(0L, snapshot.get("sstableDataBlocksRead"));
        assertEquals(0L, snapshot.get("resetEpoch"));
    }

    @Test
    void countersShouldIncrease() {
        StorageStats stats = new StorageStats();

        stats.sstableDataBlocksRead.incrementAndGet();
        stats.sstableDataBlocksRead.incrementAndGet();
        stats.sstableDataBytesRead.addAndGet(4096);

        assertEquals(2L, stats.sstableDataBlocksRead.get());
        assertEquals(4096L, stats.sstableDataBytesRead.get());
    }

    @Test
    void resetShouldClearCountersAndIncreaseEpoch() {
        StorageStats stats = new StorageStats();

        stats.walAppendAttempts.incrementAndGet();
        stats.bloomNegativeSkips.incrementAndGet();

        long epoch = stats.reset();

        assertEquals(1L, epoch);
        assertEquals(0L, stats.walAppendAttempts.get());
        assertEquals(0L, stats.bloomNegativeSkips.get());
        assertEquals(1L, stats.resetEpoch.get());
    }

    @Test
    void snapshotShouldContainExpectedCounters() {
        StorageStats stats = new StorageStats();

        Map<String, Long> snapshot = stats.snapshot();

        assertTrue(snapshot.containsKey("walForceAttempts"));
        assertTrue(snapshot.containsKey("flushPublications"));
        assertTrue(snapshot.containsKey("compactionPublications"));
        assertTrue(snapshot.containsKey("rangeScanBlocksRead"));
    }
}
