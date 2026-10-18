package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;

/**
 * The local storage engine (plan §5.1). It knows nothing about HTTP,
 * partitions or Docker (CP1 §2.1): the server validates requests first,
 * but the engine still enforces every storage rule itself, so tests may
 * call it directly (CP1 §8).
 *
 * <p>Every operation takes an {@link OpContext}: its request id flows to
 * failpoint filters and logs, and its deadline bounds every wait. Failures
 * are {@link StorageException}s carrying a shared {@code ErrorCode}.
 *
 * <p>Flush, compaction, status and statistics join this interface together
 * with the maintenance and status work (plan §6.3, §6.4).
 */
public interface StorageEngine extends AutoCloseable {

    /** Durable, visible PUT; returns only after the WAL force (CP1 §9). */
    MutationResult put(String key, byte[] value, OpContext ctx)
            throws StorageException;

    /** Durable tombstone; succeeds also for a key that never existed. */
    MutationResult delete(String key, OpContext ctx) throws StorageException;

    GetResult get(String key, OpContext ctx) throws StorageException;

    /** Live keys in {@code [startInclusive, endExclusive)}, at most {@code limit}. */
    ScanResult scan(String startInclusive, String endExclusive, int limit,
                    OpContext ctx) throws StorageException;

    /** Shared lifecycle for health: RECOVERING, READY or FAILED. */
    Lifecycle lifecycle();

    EngineMode mode();

    @Override
    void close();
}
