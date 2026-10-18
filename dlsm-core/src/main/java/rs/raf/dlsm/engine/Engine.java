package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The storage engine core: ordered mutations, two in-memory generations and
 * reads that resolve versions by sequence.
 *
 * <p>Locking follows the recommended baseline (CP1 §13.1): one fair
 * {@code engineLock}. PUT and DELETE hold the write lock from the mode check
 * through sequence allocation, WAL append and force, and MemTable apply, so
 * WAL order, sequence order and visibility order are the same. GET and
 * SCAN hold the read lock for the whole lookup, which makes a SCAN a stable
 * snapshot. Every lock wait is bounded by the request deadline.
 *
 * <p>Not yet here: rotation and backpressure, flush and SSTable sources,
 * recovery, failpoints and statistics. Their places are marked in the code.
 */
public final class Engine implements StorageEngine {

    private final ReentrantReadWriteLock engineLock = new ReentrantReadWriteLock(true);
    private final MutationLog log;
    private final SequenceAllocator sequences;

    /** Guarded by engineLock. */
    private MemTable active;
    /** Guarded by engineLock; set by rotation, cleared by flush. */
    private ImmutableMemTable immutable;

    /** Written under the write lock; volatile so health can read it without locking. */
    private volatile EngineMode mode;
    private volatile StorageException lastFatalError;

    Engine(EngineConfig config, MutationLog log, SequenceAllocator sequences) {
        Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.sequences = Objects.requireNonNull(sequences, "sequences");
        this.active = new MemTable(1, config.memtableMaxBytes());
        this.mode = EngineMode.SERVING;
    }

    /**
     * A fresh engine that keeps data only in memory: nothing survives a
     * restart. Used until the WAL and recovery are wired in (milestone M1).
     */
    public static Engine openInMemory(EngineConfig config) {
        return new Engine(config, MutationLog.NONE, SequenceAllocator.forNewDatabase());
    }

    @Override
    public MutationResult put(String key, byte[] value, OpContext ctx)
            throws StorageException {
        // CP1 §9 step 1: validate before touching sequences or the WAL.
        requireValidKey(key);
        if (value == null) {
            throw new StorageException(ErrorCode.INVALID_REQUEST, "PUT requires a value");
        }
        if (value.length > InternalRecord.MAX_VALUE_BYTES) {
            throw new StorageException(ErrorCode.VALUE_TOO_LARGE, "Value too large");
        }
        return mutate(key, Kind.PUT, value, ctx);
    }

    @Override
    public MutationResult delete(String key, OpContext ctx) throws StorageException {
        requireValidKey(key);
        return mutate(key, Kind.DELETE, null, ctx);
    }

    private MutationResult mutate(String key, Kind kind, byte[] value, OpContext ctx)
            throws StorageException {

        Lock writeLock = engineLock.writeLock();
        acquire(writeLock, ctx);                                  // step 2
        try {
            requireMode("Engine does not accept mutations");      // step 3
            // TODO(rotation): if the active MemTable requires rotation, rotate
            // it, or wait on backpressure while an immutable one exists (CP1 §10).

            long sequence;
            try {
                sequence = sequences.allocate();                  // step 4
            } catch (StorageException e) {
                throw fail(e);
            }
            InternalRecord record = new InternalRecord(key, sequence, kind, value);

            try {
                log.append(record, ctx);                          // steps 5–6
                // Durability boundary and linearization point (CP1 §9.2).
                log.force(ctx);                                   // step 7
            } catch (IOException e) {
                // The WAL tail is now uncertain: never append behind it.
                throw fail(new StorageException(ErrorCode.STORAGE_IO, null,
                        "WAL append or force failed", e));
            }

            // failpoint: wal.after-sync.before-memtable
            try {
                active.apply(record);                             // step 8
            } catch (RuntimeException e) {
                // Durable but not applied: memory no longer matches the WAL.
                throw fail(new StorageException(ErrorCode.INTERNAL_ERROR, null,
                        "MemTable apply failed after WAL force", e));
            }
            // failpoint: memtable.after-apply.before-response

            return new MutationResult(sequence);
        } finally {
            // Step 9: readers see the record once the write lock is released.
            writeLock.unlock();                                   // step 10
        }
    }

    @Override
    public GetResult get(String key, OpContext ctx) throws StorageException {
        requireValidKey(key);

        Lock readLock = engineLock.readLock();
        acquire(readLock, ctx);
        try {
            requireMode("Engine is not serving reads");

            // CP1 §11: every candidate competes by sequence, never by source.
            InternalRecord newest = newer(
                    active.get(key),
                    immutable == null ? Optional.empty() : immutable.get(key));
            // TODO(sstable): add candidates from published SSTables.

            return newest == null || newest.isTombstone()
                    ? GetResult.notFound()
                    : GetResult.found(newest.value());
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public ScanResult scan(String startInclusive, String endExclusive, int limit,
                           OpContext ctx) throws StorageException {
        try {
            LiveScan.validate(startInclusive, endExclusive, limit);
        } catch (IllegalArgumentException e) {
            throw new StorageException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }

        Lock readLock = engineLock.readLock();
        acquire(readLock, ctx);
        try {
            requireMode("Engine is not serving reads");

            // Holding the read lock for the whole scan makes the sources one
            // stable snapshot (CP1 §12 step 1, §13.1).
            List<Iterator<InternalRecord>> sources = new ArrayList<>();
            sources.add(active.iterator(startInclusive));
            if (immutable != null) {
                sources.add(immutable.iterator(startInclusive));
            }
            // TODO(sstable): add iterators of published SSTables.

            // failpoint: scan.after-snapshot.before-merge
            return new ScanResult(LiveScan.collect(
                    sources, startInclusive, endExclusive, limit));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Lifecycle lifecycle() {
        return mode.lifecycle();
    }

    @Override
    public EngineMode mode() {
        return mode;
    }

    /** The error that moved the engine to {@code FAILED}, if any. */
    public Optional<StorageException> lastFatalError() {
        return Optional.ofNullable(lastFatalError);
    }

    @Override
    public void close() {
        // The in-memory engine owns no files yet.
    }

    /**
     * TEAM CHOICE (docs/ODLUKE.md): reads are rejected too once FAILED, so
     * a broken engine never serves state it can no longer vouch for.
     */
    private void requireMode(String message) throws StorageException {
        EngineMode current = mode;
        if (current != EngineMode.SERVING && current != EngineMode.BACKPRESSURED) {
            throw new StorageException(ErrorCode.NODE_NOT_READY, message);
        }
    }

    /** Moves to FAILED (first error wins) and returns the error to throw. */
    private StorageException fail(StorageException error) {
        if (lastFatalError == null) {
            lastFatalError = error;
        }
        mode = EngineMode.FAILED;
        return error;
    }

    private static void acquire(Lock lock, OpContext ctx) throws StorageException {
        Objects.requireNonNull(ctx, "ctx");
        long remaining = ctx.remainingNanos();
        try {
            if (remaining <= 0 || !lock.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                throw new StorageException(ErrorCode.DEADLINE_EXCEEDED,
                        "Request deadline expired");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException(ErrorCode.INTERNAL_ERROR,
                    "Interrupted while waiting for the engine");
        }
    }

    private static void requireValidKey(String key) throws StorageException {
        if (!Keys.isValid(key)) {
            throw new StorageException(ErrorCode.INVALID_REQUEST, "Invalid key");
        }
    }

    private static InternalRecord newer(Optional<InternalRecord> a,
                                        Optional<InternalRecord> b) {
        if (a.isEmpty()) {
            return b.orElse(null);
        }
        if (b.isEmpty()) {
            return a.get();
        }
        return a.get().sequence() >= b.get().sequence() ? a.get() : b.get();
    }
}
