package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.StorageException;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * What flush and compaction may ask of the engine (plan §5.1). The engine
 * implements it; the maintenance worker uses it.
 *
 * <h2>Locks</h2>
 * The worker holds its {@code maintenanceLock} for a whole job and takes
 * the engine lock only through these methods, so the order is always
 * {@code maintenanceLock → engineLock} (CP1 §13.1). The engine never takes
 * the maintenance lock. The expensive part of a job (writing the SSTable)
 * runs without the engine lock, so writes continue meanwhile.
 *
 * <h2>Flush (CP1 §19.1)</h2>
 * <ol>
 *   <li>Manual flush only: read {@link #maxSequenceInEngine} as
 *       {@code targetSequence}; flush the frozen generation first if one
 *       exists, then call {@link #rotateIfActiveContainsUpTo} (CP1 §23.1).</li>
 *   <li>Take {@link #oldestImmutable()}, write and finalize the SSTable
 *       outside the engine lock.</li>
 *   <li>In {@link #underEngineWriteLock}: re-check, durably publish the
 *       manifest, then {@link #installFlushResult}.</li>
 *   <li>After it returns: delete the covered WAL segments.</li>
 * </ol>
 *
 * <h2>Compaction (CP1 §20.3)</h2>
 * <ol>
 *   <li>Select inputs from {@link #currentView()}; merge them outside the
 *       engine lock (e.g. with {@link MergingIterator}).</li>
 *   <li>In {@link #underEngineWriteLock}: check that every input is still
 *       published, durably publish the manifest, then
 *       {@link #installCompactionResult}.</li>
 *   <li>After it returns: no reader uses the inputs any more, so their
 *       files may be deleted.</li>
 * </ol>
 */
public interface MaintenanceHost {

    /**
     * The greatest sequence in any source of the engine (active and frozen
     * generations, published tables), tombstones included; 0 when there is
     * none. This is the manual flush {@code targetSequence} (CP1 §23.1).
     */
    long maxSequenceInEngine(OpContext ctx) throws StorageException;

    /**
     * If the active generation still holds mutations with sequence
     * {@code <= targetSequence}, rotates it (CP1 §10) so it becomes the
     * frozen generation waiting for flush.
     *
     * @return true if a rotation happened, false if nothing was needed
     * @throws IllegalStateException if a frozen generation already exists;
     *                               flush it first (FIFO, at most one)
     * @throws StorageException      if the new WAL segment cannot be created;
     *                               the current pair then stays in use
     */
    boolean rotateIfActiveContainsUpTo(long targetSequence, OpContext ctx)
            throws StorageException;

    /** The frozen generation to flush next (FIFO; at most one in CP1). */
    Optional<FlushInput> oldestImmutable();

    /** The current published tables and frozen generation; safe without a lock. */
    EngineView currentView();

    /**
     * Runs {@code step} holding the engine write lock: no read or mutation
     * runs meanwhile, so the step can re-validate its assumptions, publish
     * the manifest and swap the in-memory view as one atomic change. The
     * lock wait is bounded by the deadline of {@code ctx}.
     *
     * @throws StorageException {@code DEADLINE_EXCEEDED} if the lock is not
     *                          acquired in time; anything {@code step} throws
     *                          is passed on unchanged
     */
    <T> T underEngineWriteLock(OpContext ctx, Callable<T> step) throws Exception;

    /**
     * Flush step 11: removes frozen generation {@code memtableGeneration}
     * from the view and adds {@code table}, which holds exactly its records,
     * then wakes writers waiting on backpressure. Call it inside
     * {@link #underEngineWriteLock}, after the manifest naming {@code table}
     * is durable.
     *
     * @throws IllegalStateException if not under the write lock, if that
     *                               generation is not the frozen one, or if
     *                               {@code table} does not match it
     */
    void installFlushResult(long memtableGeneration, TableSource table);

    /**
     * Compaction step 8: replaces exactly {@code inputTableIds} by
     * {@code output} in the view; every other table stays. Call it inside
     * {@link #underEngineWriteLock}, after the manifest is durable.
     *
     * @throws IllegalStateException if not under the write lock or if an
     *                               input is no longer published
     */
    void installCompactionResult(Set<Long> inputTableIds, TableSource output);

    /**
     * Reports a fatal maintenance error: durable state became uncertain,
     * e.g. an I/O error after the manifest rename (CP1 §26.1). The engine
     * enters {@code FAILED} (the first error is kept as
     * {@code lastFatalError}), wakes writers waiting on backpressure with an
     * error and refuses every later operation until restart (CP1 §22).
     * Safe to call with or without the engine write lock.
     */
    void reportFatalError(StorageException cause);
}
