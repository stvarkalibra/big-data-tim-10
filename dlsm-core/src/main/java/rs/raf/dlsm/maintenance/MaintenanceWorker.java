package rs.raf.dlsm.maintenance;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.FlushScheduler;
import rs.raf.dlsm.engine.MaintenanceHost;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MaintenanceWorker implements FlushScheduler {

    private final MaintenanceHost host;
    private final ManifestManager manifests;
    private final FlushJob flushJob;
    private final JsonLog log;
    private final AtomicBoolean autoFlushQueued = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "maintenance");
        thread.setDaemon(true);
        return thread;
    });

    private volatile StorageException lastError;

    public MaintenanceWorker(MaintenanceHost host, ManifestManager manifests, FlushJob flushJob, JsonLog log) {
        this.host = Objects.requireNonNull(host, "host");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.flushJob = Objects.requireNonNull(flushJob, "flushJob");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public void flushNeeded() {
        if (!autoFlushQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::autoFlush);
        } catch (RejectedExecutionException e) {
            autoFlushQueued.set(false);
        }
    }

    @Override
    public void checkAutoCompaction() {
    }

    public ManualFlushResult flush(OpContext ctx) throws StorageException {
        Objects.requireNonNull(ctx, "ctx");
        OpContext jobCtx = new OpContext(ctx.requestId(), OpContext.background().deadlineNanos());
        return await(submit(() -> manualFlush(jobCtx)), ctx);
    }

    public Optional<StorageException> lastError() {
        return Optional.ofNullable(lastError);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    void awaitIdle(OpContext ctx) throws StorageException {
        await(submit(() -> null), ctx);
    }

    private void autoFlush() {
        autoFlushQueued.set(false);
        try {
            flushJob.flushOldest(null, OpContext.background());
        } catch (StorageException e) {
            record("FLUSH", e);
        } catch (RuntimeException e) {
            record("FLUSH", new StorageException(ErrorCode.INTERNAL_ERROR, null, "Automatic flush failed", e));
        }
    }

    private ManualFlushResult manualFlush(OpContext ctx) throws StorageException {
        try {
            long target = host.maxSequenceInEngine(ctx);
            List<Long> published = new ArrayList<>();
            while (manifests.current().flushedThroughSequence() < target) {
                Optional<FlushJob.Outcome> outcome = flushJob.flushOldest(target, ctx);
                if (outcome.isPresent()) {
                    published.add(outcome.get().tableId());
                } else if (!host.rotateIfActiveContainsUpTo(target, ctx)) {
                    throw new StorageException(ErrorCode.INTERNAL_ERROR, null,
                            "Target sequence " + target + " is in no unflushed generation");
                }
            }
            Manifest manifest = manifests.current();
            return new ManualFlushResult(
                    published.isEmpty() ? ManualFlushResult.Result.NO_OP : ManualFlushResult.Result.PUBLISHED,
                    target, published, manifest.generation(), manifest.flushedThroughSequence());
        } catch (StorageException e) {
            record("FLUSH", e);
            throw e;
        } catch (RuntimeException e) {
            StorageException error = new StorageException(ErrorCode.INTERNAL_ERROR, null, "Manual flush failed", e);
            record("FLUSH", error);
            throw error;
        }
    }

    private void record(String operation, StorageException e) {
        lastError = e;
        log.error("MAINTENANCE_FAILED", Map.of(
                "operation", operation,
                "errorCode", e.category() != null ? e.category() : e.code().name(),
                "message", e.getMessage()));
    }

    private <T> Future<T> submit(Callable<T> task) throws StorageException {
        try {
            return executor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new StorageException(ErrorCode.NODE_NOT_READY, null, "Maintenance worker is stopped", e);
        }
    }

    private static <T> T await(Future<T> future, OpContext ctx) throws StorageException {
        try {
            return future.get(Math.max(0, ctx.remainingNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new StorageException(ErrorCode.DEADLINE_EXCEEDED, null,
                    "Maintenance did not finish before the deadline", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof StorageException storage) {
                throw storage;
            }
            throw new StorageException(ErrorCode.INTERNAL_ERROR, null, "Maintenance job failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException(ErrorCode.INTERNAL_ERROR, null, "Interrupted while waiting for maintenance", e);
        }
    }
}
