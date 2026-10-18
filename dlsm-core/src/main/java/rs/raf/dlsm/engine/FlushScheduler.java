package rs.raf.dlsm.engine;

/**
 * How the engine tells the maintenance worker that work is waiting
 * (plan §5.1). Implemented by the maintenance module.
 *
 * <p>The engine may call these methods while holding the engine write lock.
 * Implementations must therefore only enqueue work and return: they must
 * not block, must not wait for a flush and must never take the maintenance
 * lock, which would invert the lock order {@code maintenanceLock →
 * engineLock} (CP1 §13.1).
 */
public interface FlushScheduler {

    /** A frozen generation is waiting for flush (CP1 §10, rotation step 4). */
    void flushNeeded();

    /**
     * Published tables may have reached the automatic compaction threshold;
     * queue one compaction if needed (CP1 §20.1). Called after recovery.
     */
    void checkAutoCompaction();

    /** Stops the worker; called once by {@code Engine.close()}. Must not throw. */
    default void close() {
    }

    /** Does nothing: for an engine running without a maintenance worker. */
    FlushScheduler NONE = new FlushScheduler() {
        @Override
        public void flushNeeded() {
        }

        @Override
        public void checkAutoCompaction() {
        }
    };
}
