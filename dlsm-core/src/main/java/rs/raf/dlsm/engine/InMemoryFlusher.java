package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Flush worker for the in-memory M1 node, until the real flush (SSTable
 * files and manifest) replaces it. It drives the same {@link MaintenanceHost}
 * steps as the real worker: take the frozen generation, build a table
 * outside the engine lock, publish it under the engine write lock. That
 * frees the frozen slot, so backpressured writers continue.
 *
 * <p>One worker thread serializes all flushes. {@link #flushNeeded} only
 * enqueues, as {@link FlushScheduler} requires. No compaction: tables
 * accumulate, which is acceptable for an in-memory milestone node.
 */
public final class InMemoryFlusher implements FlushScheduler, AutoCloseable {

    private final MaintenanceHost host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "in-memory-flush");
        thread.setDaemon(true);
        return thread;
    });

    /** Touched only by the worker thread. */
    private long nextTableId = 1;

    public InMemoryFlusher(MaintenanceHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public void flushNeeded() {
        try {
            worker.execute(this::flushOldest);
        } catch (RejectedExecutionException e) {
            // Closed together with the engine: there is nothing left to serve.
        }
    }

    @Override
    public void checkAutoCompaction() {
        // No compaction for in-memory tables (see class comment).
    }

    private void flushOldest() {
        Optional<FlushInput> next = host.oldestImmutable();
        if (next.isEmpty()) {
            return;
        }
        FlushInput input = next.get();
        MemoryTable table = MemoryTable.of(nextTableId++, input);

        try {
            host.underEngineWriteLock(OpContext.background(), () -> {
                host.installFlushResult(input.memtableGeneration(), table);
                return null;
            });
        } catch (StorageException e) {
            if (e.code() != ErrorCode.NODE_NOT_READY) {
                host.reportFatalError(e);
            }
            // NODE_NOT_READY: the engine is already FAILED; nothing to publish into.
        } catch (Exception e) {
            // Writers would wait for this flush forever: fail loudly instead.
            host.reportFatalError(new StorageException(ErrorCode.INTERNAL_ERROR, null,
                    "In-memory flush failed", e));
        }
    }

    /** Waits until every flush queued so far has finished (for tests). */
    void awaitIdle(long timeout, java.util.concurrent.TimeUnit unit) throws Exception {
        worker.submit(() -> { }).get(timeout, unit);
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
