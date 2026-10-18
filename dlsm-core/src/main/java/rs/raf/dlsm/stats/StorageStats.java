
package rs.raf.dlsm.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class StorageStats {

    // Matej: WAL
    public final AtomicLong walAppendAttempts = new AtomicLong();
    public final AtomicLong walAppendsCompleted = new AtomicLong();
    public final AtomicLong walForceAttempts = new AtomicLong();
    public final AtomicLong walForcesSucceeded = new AtomicLong();
    public final AtomicLong walBytesWritten = new AtomicLong();
    public final AtomicLong walRecordsReplayed = new AtomicLong();
    public final AtomicLong walTornTailsTruncated = new AtomicLong();

    // Matej / Ilija: cleanup
    public final AtomicLong orphanFilesRemoved = new AtomicLong();
    public final AtomicLong temporaryFilesRemoved = new AtomicLong();

    // Ilija: maintenance
    public final AtomicLong flushAttempts = new AtomicLong();
    public final AtomicLong flushPublications = new AtomicLong();
    public final AtomicLong compactionAttempts = new AtomicLong();
    public final AtomicLong compactionPublications = new AtomicLong();

    // Kimi: SSTable
    public final AtomicLong sstableDataBlocksRead = new AtomicLong();
    public final AtomicLong sstableDataBytesRead = new AtomicLong();
    public final AtomicLong bloomNegativeSkips = new AtomicLong();
    public final AtomicLong bloomPositiveProbes = new AtomicLong();
    public final AtomicLong rangeScanBlocksRead = new AtomicLong();

    public final AtomicLong resetEpoch = new AtomicLong();

    private final Object lock = new Object();

    /**
     * Preferred API for recording counter changes.
     * Operations through add/increment/reset/snapshot share one lock.
     */
    public void increment(String counterName) {
        add(counterName, 1);
    }

    public void add(String counterName, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException(
                    "Counter increment cannot be negative");
        }

        synchronized (lock) {
            AtomicLong counter = counter(counterName);
            long current = counter.get();

            if (current > Long.MAX_VALUE - amount) {
                throw new ArithmeticException("Counter overflow");
            }

            counter.set(current + amount);
        }
    }

    public long reset() {
        synchronized (lock) {
            long epoch = resetEpoch.get();

            if (epoch == Long.MAX_VALUE) {
                throw new ArithmeticException("Reset epoch overflow");
            }

            for (AtomicLong counter : counters().values()) {
                counter.set(0);
            }

            return resetEpoch.incrementAndGet();
        }
    }

    public Map<String, Long> snapshot() {
        synchronized (lock) {
            Map<String, Long> result = new LinkedHashMap<>();

            for (var entry : counters().entrySet()) {
                result.put(entry.getKey(), entry.getValue().get());
            }

            result.put("resetEpoch", resetEpoch.get());
            return result;
        }
    }

    private AtomicLong counter(String name) {
        Objects.requireNonNull(name, "counterName");

        AtomicLong result = counters().get(name);

        if (result == null) {
            throw new IllegalArgumentException(
                    "Unknown counter: " + name);
        }

        return result;
    }

    private Map<String, AtomicLong> counters() {
        Map<String, AtomicLong> result = new LinkedHashMap<>();

        result.put("walAppendAttempts", walAppendAttempts);
        result.put("walAppendsCompleted", walAppendsCompleted);
        result.put("walForceAttempts", walForceAttempts);
        result.put("walForcesSucceeded", walForcesSucceeded);
        result.put("walBytesWritten", walBytesWritten);
        result.put("walRecordsReplayed", walRecordsReplayed);
        result.put("walTornTailsTruncated", walTornTailsTruncated);

        result.put("orphanFilesRemoved", orphanFilesRemoved);
        result.put("temporaryFilesRemoved", temporaryFilesRemoved);

        result.put("flushAttempts", flushAttempts);
        result.put("flushPublications", flushPublications);
        result.put("compactionAttempts", compactionAttempts);
        result.put("compactionPublications", compactionPublications);

        result.put("sstableDataBlocksRead", sstableDataBlocksRead);
        result.put("sstableDataBytesRead", sstableDataBytesRead);
        result.put("bloomNegativeSkips", bloomNegativeSkips);
        result.put("bloomPositiveProbes", bloomPositiveProbes);
        result.put("rangeScanBlocksRead", rangeScanBlocksRead);

        return result;
    }
}
