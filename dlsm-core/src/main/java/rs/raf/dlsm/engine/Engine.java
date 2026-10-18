package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
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
 * <p>Reads consult the active MemTable and the {@link EngineView}: the
 * frozen generation and the published tables. A storage error from a table
 * (corruption, I/O) moves the engine to {@code FAILED}; it is never turned
 * into "not found" (I7, CP1 §22).
 *
 * <p>The active MemTable and its WAL segment form one pair that rotation
 * replaces as a whole (CP1 §10): a full generation is frozen into the view
 * and handed to the flush worker through {@link FlushScheduler}.
 *
 * <p>Not yet here: backpressure, recovery, failpoints and statistics.
 * Their places are marked in the code.
 */
public final class Engine implements StorageEngine, MaintenanceHost {

    /** For engines built without rotation support (tests of other parts). */
    private static final WalFactory NO_ROTATION = ctx -> {
        throw new IOException("This engine was built without a WAL factory");
    };

    private final ReentrantReadWriteLock engineLock = new ReentrantReadWriteLock(true);
    private final long memtableMaxBytes;
    private final WalFactory walFactory;
    private final SequenceAllocator sequences;
    private final Object failureLock = new Object();
    private volatile FlushScheduler flushScheduler = FlushScheduler.NONE;

    /** The active pair (CP1 §10): changed only together, under the write lock. */
    private MemTable active;
    private MutationLog activeLog;
    /**
     * Replaced as a whole under the write lock, never modified; volatile so
     * maintenance can read the current view without locking.
     */
    private volatile EngineView view;

    /** Written under a lock; volatile so health can read it without locking. */
    private volatile EngineMode mode;
    private volatile StorageException lastFatalError;

    Engine(EngineConfig config, MutationLog activeLog, SequenceAllocator sequences) {
        this(config, NO_ROTATION, activeLog, sequences, EngineView.EMPTY);
    }

    Engine(EngineConfig config, MutationLog activeLog, SequenceAllocator sequences,
           EngineView initialView) {
        this(config, NO_ROTATION, activeLog, sequences, initialView);
    }

    /**
     * @param walFactory  creates the next WAL segment on rotation
     * @param activeLog   the durable, empty segment the active MemTable uses
     * @param initialView published tables (and, after recovery, the
     *                    recovered frozen generation) to start from
     */
    Engine(EngineConfig config, WalFactory walFactory, MutationLog activeLog,
           SequenceAllocator sequences, EngineView initialView) {
        Objects.requireNonNull(config, "config");
        this.memtableMaxBytes = config.memtableMaxBytes();
        this.walFactory = Objects.requireNonNull(walFactory, "walFactory");
        this.activeLog = Objects.requireNonNull(activeLog, "activeLog");
        this.sequences = Objects.requireNonNull(sequences, "sequences");
        this.view = Objects.requireNonNull(initialView, "initialView");
        long generation = initialView.immutable() == null
                ? 1 : initialView.immutable().generation() + 1;
        this.active = new MemTable(generation, memtableMaxBytes);
        this.mode = EngineMode.SERVING;
    }

    /**
     * A fresh engine that keeps data only in memory: nothing survives a
     * restart. Used until the WAL and recovery are wired in (milestone M1).
     */
    public static Engine openInMemory(EngineConfig config) {
        InMemoryWal wal = new InMemoryWal();
        return new Engine(config, wal, wal.createSegment(OpContext.background()),
                SequenceAllocator.forNewDatabase(), EngineView.EMPTY);
    }

    /**
     * Connects the maintenance worker, which is created after the engine
     * because it needs the engine as its {@link MaintenanceHost}.
     */
    public void setFlushScheduler(FlushScheduler scheduler) {
        this.flushScheduler = Objects.requireNonNull(scheduler, "scheduler");
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
            // CP1 §10: a full active generation must rotate before the next
            // mutation appends. If rotation fails this mutation fails too, and
            // the old pair stays current.
            if (active.rotationRequired() && view.immutable() == null) {
                rotateLocked(ctx);
            }
            // TODO(backpressure): while a frozen generation still waits for
            // flush, wait here (bounded by the deadline) instead of growing.

            long sequence;
            try {
                sequence = sequences.allocate();                  // step 4
            } catch (StorageException e) {
                throw fail(e);
            }
            InternalRecord record = new InternalRecord(key, sequence, kind, value);

            try {
                activeLog.append(record, ctx);                    // steps 5–6
                // Durability boundary and linearization point (CP1 §9.2).
                activeLog.force(ctx);                             // step 7
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

    /**
     * Rotation (CP1 §10). The caller holds the write lock; no frozen
     * generation may exist and the active one must hold records.
     */
    private void rotateLocked(OpContext ctx) throws StorageException {
        if (view.immutable() != null) {
            throw new IllegalStateException("A frozen generation already waits for flush");
        }
        if (active.isEmpty()) {
            throw new IllegalStateException("An empty generation is never rotated");
        }

        // Step 1: the new segment is created, forced and finalized before
        // anything may be appended to it. On failure the old pair stays.
        MutationLog nextLog;
        try {
            nextLog = walFactory.createSegment(ctx);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.STORAGE_IO, null,
                    "Cannot create a new WAL segment", e);
        }
        // failpoint: rotation.after-new-wal-sync.before-switch

        // Step 2: freeze the old generation together with its WAL segment.
        ImmutableMemTable frozen = active.freeze(List.of(activeLog.segmentId()));
        MutationLog previousLog = activeLog;

        // Step 3: swap the pair as one change: the linearization point of
        // rotation. Writers after this use only the new pair.
        active = new MemTable(active.generation() + 1, memtableMaxBytes);
        activeLog = nextLog;
        view = new EngineView(frozen, view.tables());
        previousLog.close();
        // failpoint: rotation.after-switch.before-flush

        // Step 4: hand the frozen generation to the flush worker.
        flushScheduler.flushNeeded();
    }

    // ---------- MaintenanceHost ----------

    @Override
    public long maxSequenceInEngine(OpContext ctx) throws StorageException {
        Lock readLock = engineLock.readLock();
        acquire(readLock, ctx);
        try {
            requireMode("Engine is not serving");
            return Math.max(active.maxSequence().orElse(0), view.maxSequence());
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public EngineView currentView() {
        return view;
    }

    @Override
    public Optional<FlushInput> oldestImmutable() {
        // At most one frozen generation exists, so it is also the oldest.
        return Optional.ofNullable(view.immutable()).map(FlushInput::new);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A {@code FAILED} engine publishes nothing: the step is not run and
     * {@code NODE_NOT_READY} is thrown.
     */
    @Override
    public <T> T underEngineWriteLock(OpContext ctx, Callable<T> step) throws Exception {
        Objects.requireNonNull(step, "step");
        Lock writeLock = engineLock.writeLock();
        acquire(writeLock, ctx);
        try {
            requireMode("Engine is not serving");
            return step.call();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void installFlushResult(long memtableGeneration, TableSource table) {
        Objects.requireNonNull(table, "table");
        requireWriteLockHeld();

        EngineView current = view;
        ImmutableMemTable frozen = current.immutable();
        if (frozen == null || frozen.generation() != memtableGeneration) {
            throw new IllegalStateException("Generation " + memtableGeneration
                    + " is not the frozen generation waiting for flush");
        }
        if (current.table(table.tableId()).isPresent()) {
            throw new IllegalStateException("Table " + table.tableId() + " is already published");
        }
        // The table must hold exactly the frozen records: every key once,
        // and the newest version, which carries the generation's maxSequence.
        if (table.meta().entryCount() != frozen.entryCount()
                || table.meta().maxSequence() != frozen.maxSequence()) {
            throw new IllegalStateException("Table " + table.tableId()
                    + " does not match generation " + memtableGeneration);
        }

        // CP1 §19.1 step 11: one atomic swap; readers see the frozen records
        // either in memory or in the table, never in neither.
        List<TableSource> tables = new ArrayList<>(current.tables());
        tables.add(table);
        view = new EngineView(null, tables);
        // TODO(backpressure): wake writers waiting for the frozen slot.
    }

    @Override
    public void installCompactionResult(Set<Long> inputTableIds, TableSource output) {
        Objects.requireNonNull(inputTableIds, "inputTableIds");
        Objects.requireNonNull(output, "output");
        requireWriteLockHeld();

        EngineView current = view;
        if (inputTableIds.isEmpty()) {
            throw new IllegalStateException("Compaction needs at least one input");
        }
        long inputMaxSequence = 0;
        for (long id : inputTableIds) {
            TableSource input = current.table(id).orElseThrow(() -> new IllegalStateException(
                    "Compaction input " + id + " is no longer published"));
            inputMaxSequence = Math.max(inputMaxSequence, input.meta().maxSequence());
        }
        if (current.table(output.tableId()).isPresent()) {
            throw new IllegalStateException("Table " + output.tableId() + " is already published");
        }
        // Compaction keeps the newest version of every key, so the newest
        // input sequence must survive in the output.
        if (output.meta().maxSequence() != inputMaxSequence) {
            throw new IllegalStateException("Compaction output " + output.tableId()
                    + " does not match its inputs");
        }

        // CP1 §20.3 step 8: replace exactly the inputs, keep everything else.
        List<TableSource> tables = new ArrayList<>();
        for (TableSource table : current.tables()) {
            if (!inputTableIds.contains(table.tableId())) {
                tables.add(table);
            }
        }
        tables.add(output);
        view = new EngineView(current.immutable(), tables);
    }

    private void requireWriteLockHeld() {
        if (!engineLock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Call this inside underEngineWriteLock");
        }
    }

    @Override
    public boolean rotateIfActiveContainsUpTo(long targetSequence, OpContext ctx)
            throws StorageException {
        Lock writeLock = engineLock.writeLock();
        acquire(writeLock, ctx);
        try {
            requireMode("Engine is not serving");
            if (view.immutable() != null) {
                throw new IllegalStateException(
                        "Flush the frozen generation first (FIFO, at most one)");
            }
            if (active.isEmpty() || active.minSequence().getAsLong() > targetSequence) {
                return false; // nothing at or below the target is in the active generation
            }
            rotateLocked(ctx);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public GetResult get(String key, OpContext ctx) throws StorageException {
        requireValidKey(key);

        Lock readLock = engineLock.readLock();
        acquire(readLock, ctx);
        try {
            requireMode("Engine is not serving reads");

            // CP1 §11: every candidate competes by sequence, never by source
            // position or table id.
            InternalRecord newest = active.get(key).orElse(null);
            if (view.immutable() != null) {
                newest = newer(newest, view.immutable().get(key).orElse(null));
            }
            for (TableSource table : view.tables()) {
                if (mayContain(table, key)) {
                    newest = newer(newest, tableGet(table, key));
                }
            }

            return newest == null || newest.isTombstone()
                    ? GetResult.notFound()
                    : GetResult.found(newest.value());
        } finally {
            readLock.unlock();
        }
    }

    private InternalRecord tableGet(TableSource table, String key)
            throws StorageException {
        try {
            return table.get(key).orElse(null);
        } catch (StorageException e) {
            // A corrupt table is a storage failure, never absence (I7).
            throw fail(e);
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
            if (view.immutable() != null) {
                sources.add(view.immutable().iterator(startInclusive));
            }
            for (TableSource table : view.tables()) {
                if (overlaps(table, startInclusive, endExclusive)) {
                    try {
                        sources.add(table.iterator(startInclusive));
                    } catch (StorageException e) {
                        throw fail(e);
                    }
                }
            }

            // failpoint: scan.after-snapshot.before-merge
            try {
                return new ScanResult(LiveScan.collect(
                        sources, startInclusive, endExclusive, limit));
            } catch (UncheckedStorageException e) {
                throw fail(e.getCause());
            }
        } finally {
            readLock.unlock();
        }
    }

    /** The table's key range can contain {@code key} (CP1 §11.2 step 1). */
    private static boolean mayContain(TableSource table, String key) {
        return Keys.compare(key, table.meta().minKey()) >= 0
                && Keys.compare(key, table.meta().maxKey()) <= 0;
    }

    /** The table's key range intersects {@code [start, end)} (CP1 §12). */
    private static boolean overlaps(TableSource table, String start, String end) {
        return Keys.compare(table.meta().maxKey(), start) >= 0
                && Keys.compare(table.meta().minKey(), end) < 0;
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

    /**
     * Moves to FAILED (first error wins) and returns the error to throw.
     * Readers may call this concurrently under the read lock.
     */
    private StorageException fail(StorageException error) {
        synchronized (failureLock) {
            if (lastFatalError == null) {
                lastFatalError = error;
            }
            mode = EngineMode.FAILED;
        }
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

    /** The version with the greater sequence; either may be null. */
    private static InternalRecord newer(InternalRecord a, InternalRecord b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.sequence() > a.sequence() ? b : a;
    }
}
