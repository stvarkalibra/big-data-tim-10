package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.InjectedIOException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

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
 * and handed to the flush worker through {@link FlushScheduler}. At most
 * one frozen generation exists: when the active one fills up again first,
 * writers wait for the flush (backpressure, mode {@code BACKPRESSURED}).
 *
 * <p>The engine's five failpoints (CP1 §26.1) are hit through
 * {@link EngineConfig#failpoints()}; outside test mode they do nothing.
 */
public final class Engine implements StorageEngine, MaintenanceHost {

    /** For engines built without rotation support (tests of other parts). */
    private static final WalFactory NO_ROTATION = ctx -> {
        throw new IOException("This engine was built without a WAL factory");
    };

    private final ReentrantReadWriteLock engineLock = new ReentrantReadWriteLock(true);
    /** Signalled when the frozen slot becomes free; bound to the write lock. */
    private final Condition rotationSlotFree = engineLock.writeLock().newCondition();
    private final long memtableMaxBytes;
    private final Failpoints failpoints;
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

    /** Changed under the write lock; volatile so status can read it without locking. */
    private volatile int waitingWriters;

    /**
     * Greatest sequence whose WAL record was forced, or that was already
     * durable when the engine opened. Guarded by engineLock.
     */
    private long lastForcedSequence;

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
        this.failpoints = config.failpoints();
        this.walFactory = Objects.requireNonNull(walFactory, "walFactory");
        this.activeLog = Objects.requireNonNull(activeLog, "activeLog");
        this.sequences = Objects.requireNonNull(sequences, "sequences");
        this.view = Objects.requireNonNull(initialView, "initialView");
        long generation = initialView.immutable() == null
                ? 1 : initialView.immutable().generation() + 1;
        this.active = new MemTable(generation, memtableMaxBytes);
        // Everything the engine opens with (recovered generation, published
        // tables) is already durable.
        this.lastForcedSequence = initialView.maxSequence();
        this.mode = EngineMode.SERVING;
    }

    /**
     * A fresh engine that keeps data only in memory: nothing survives a
     * restart. Used until the WAL and recovery are wired in (milestone M1).
     */
    public static Engine openInMemory(EngineConfig config) {
        InMemoryWal wal = new InMemoryWal();
        // Frozen generations are "flushed" into in-memory tables, so the
        // frozen slot frees up and backpressure ends as with a real flush.
        return open(config,
                RecoveredState.empty(wal.createSegment(OpContext.background()), wal),
                InMemoryFlusher::new);
    }

    /**
     * Opens the engine over the state startup recovery rebuilt (CP1 §21.1,
     * steps 10–15): the engine is {@code READY} when this returns.
     *
     * <p>{@code maintenance} creates the flush worker from the engine,
     * which is its {@link MaintenanceHost}. If recovery produced a frozen
     * generation it is handed to the worker at once, so it is flushed
     * right after {@code READY} (plan §6.2, allowed by CP1 §21.1); and the
     * worker is asked to check the automatic compaction threshold
     * (CP1 §20.1). {@link #close()} closes the worker.
     *
     * @throws IllegalArgumentException if the state could make the engine
     *                                  reuse a sequence or a WAL segment
     */
    public static Engine open(EngineConfig config, RecoveredState state,
                              Function<MaintenanceHost, FlushScheduler> maintenance) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(maintenance, "maintenance");

        EngineView view = new EngineView(state.recoveredGeneration(), state.tables());
        if (view.maxSequence() > state.lastUsedSequence()) {
            throw new IllegalArgumentException("lastUsedSequence " + state.lastUsedSequence()
                    + " is below recovered sequence " + view.maxSequence());
        }
        if (state.recoveredGeneration() != null) {
            List<Long> recoveredSegments = state.recoveredGeneration().walSegmentIds();
            long newestRecovered = recoveredSegments.get(recoveredSegments.size() - 1);
            if (state.activeLog().segmentId() <= newestRecovered) {
                // CP1 §21.1 step 12: never append to a previously used segment.
                throw new IllegalArgumentException("Active WAL segment "
                        + state.activeLog().segmentId() + " is not newer than recovered segment "
                        + newestRecovered);
            }
        }

        Engine engine = new Engine(config, state.walFactory(), state.activeLog(),
                SequenceAllocator.after(state.lastUsedSequence()), view);
        FlushScheduler scheduler = Objects.requireNonNull(
                maintenance.apply(engine), "maintenance worker");
        engine.setFlushScheduler(scheduler);

        if (state.recoveredGeneration() != null) {
            scheduler.flushNeeded();
        }
        scheduler.checkAutoCompaction();
        return engine;
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
            // mutation appends. With the frozen slot still taken the writer
            // waits for flush; if rotation fails this mutation fails too, and
            // the old pair stays current.
            awaitRotationSlot(ctx);
            if (active.rotationRequired()) {
                rotateLocked(ctx);
            }

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
                lastForcedSequence = sequence;
            } catch (IOException e) {
                // The WAL tail is now uncertain: never append behind it.
                throw fail(new StorageException(ErrorCode.STORAGE_IO, null,
                        "WAL append or force failed", e));
            }

            // Durable, not yet visible: a crash here must still replay it.
            failpoint(FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE, ctx);
            try {
                active.apply(record);                             // step 8
            } catch (RuntimeException e) {
                // Durable but not applied: memory no longer matches the WAL.
                throw fail(new StorageException(ErrorCode.INTERNAL_ERROR, null,
                        "MemTable apply failed after WAL force", e));
            }
            // This mutation may have filled the active generation while the
            // frozen one still waits: from now on writers are backpressured.
            updatePressureLocked();
            // Durable and applied, the client does not know yet.
            failpoint(FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE, ctx);

            return new MutationResult(sequence);
        } finally {
            // Step 9: readers see the record once the write lock is released.
            writeLock.unlock();                                   // step 10
        }
    }

    /**
     * Backpressure (CP1 §10, §13.1): while the active generation is full and
     * the frozen slot is still taken, wait for flush. {@code awaitNanos}
     * releases the write lock, so the flush can publish and the readers keep
     * reading; the wait is bounded by the request deadline. A writer that
     * gives up has consumed no sequence and touched no WAL.
     */
    private void awaitRotationSlot(OpContext ctx) throws StorageException {
        if (!slotBlocked()) {
            return;
        }
        waitingWriters++;
        updatePressureLocked();
        try {
            while (slotBlocked()) {
                long remaining = ctx.remainingNanos();
                if (remaining <= 0) {
                    throw new StorageException(ErrorCode.DEADLINE_EXCEEDED,
                            "Deadline expired while writes wait for flush");
                }
                try {
                    rotationSlotFree.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new StorageException(ErrorCode.INTERNAL_ERROR,
                            "Interrupted while waiting for flush");
                }
                requireMode("Engine does not accept mutations");
            }
        } finally {
            waitingWriters--;
        }
    }

    /** The active generation must rotate, but the frozen slot is taken. */
    private boolean slotBlocked() {
        return active.rotationRequired() && view.immutable() != null;
    }

    /**
     * SERVING ⇄ BACKPRESSURED (CP1 §22). Called under the write lock after
     * every change of the active generation or the frozen slot; never
     * leaves FAILED.
     */
    private void updatePressureLocked() {
        synchronized (failureLock) {
            if (mode == EngineMode.SERVING || mode == EngineMode.BACKPRESSURED) {
                mode = slotBlocked() ? EngineMode.BACKPRESSURED : EngineMode.SERVING;
            }
        }
    }

    /** Status {@code writeBackpressure}: writes cannot proceed until a flush. */
    public boolean writeBackpressure() {
        return mode == EngineMode.BACKPRESSURED;
    }

    /** Status {@code backpressuredWriters}: writers currently waiting for flush. */
    public int backpressuredWriters() {
        return waitingWriters;
    }

    /**
     * The engine part of the storage status (CP1 §24) as one consistent
     * snapshot taken under the read lock. Works in every mode, FAILED
     * included: that is when the status matters most.
     */
    public EngineStatus status(OpContext ctx) throws StorageException {
        Lock readLock = engineLock.readLock();
        acquire(readLock, ctx);
        try {
            EngineView current = view;
            EngineMode currentMode = mode;
            StorageException fatal = lastFatalError;

            long tablesMax = 0;
            List<rs.raf.dlsm.sstable.SSTableMeta> tables = new ArrayList<>();
            for (TableSource table : current.tables()) {
                tables.add(table.meta());
                tablesMax = Math.max(tablesMax, table.meta().maxSequence());
            }

            ImmutableMemTable frozen = current.immutable();
            EngineStatus.Memtable frozenStatus = frozen == null ? null
                    : new EngineStatus.Memtable(frozen.generation(), null,
                            frozen.walSegmentIds(), frozen.entryCount(),
                            frozen.logicalBytes(), frozen.minSequence(),
                            frozen.maxSequence(), null);

            EngineStatus.Memtable activeStatus = new EngineStatus.Memtable(
                    active.generation(), activeLog.segmentId(), null,
                    active.entryCount(), active.logicalBytes(),
                    boxed(active.minSequence()), boxed(active.maxSequence()),
                    active.rotationRequired());

            return new EngineStatus(
                    currentMode.lifecycle(),
                    currentMode,
                    currentMode == EngineMode.BACKPRESSURED,
                    waitingWriters,
                    fatal == null ? null : EngineStatus.FatalError.of(fatal),
                    boxed(sequences.nextSequence()),
                    Math.max(active.maxSequence().orElse(0), current.maxSequence()),
                    Math.max(lastForcedSequence, tablesMax),
                    activeStatus,
                    frozenStatus,
                    tables);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Runs {@code step} under the engine read lock, so a status handler can
     * read other modules' state (e.g. the manifest, which is published
     * under the write lock) consistently with {@link #status}. Works in
     * every mode; the wait is bounded by the deadline.
     */
    public <T> T underEngineReadLock(OpContext ctx, Callable<T> step) throws Exception {
        Objects.requireNonNull(step, "step");
        Lock readLock = engineLock.readLock();
        acquire(readLock, ctx);
        try {
            return step.call();
        } finally {
            readLock.unlock();
        }
    }

    private static Long boxed(java.util.OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
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
        // New segment durable, old pair still current: a crash here leaves
        // an empty finalized segment for recovery to retire.
        try {
            failpoint(FailpointNames.ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH, ctx);
        } catch (StorageException e) {
            nextLog.close();
            throw e;
        }

        // Step 2: freeze the old generation together with its WAL segment.
        ImmutableMemTable frozen = active.freeze(List.of(activeLog.segmentId()));
        MutationLog previousLog = activeLog;

        // Step 3: swap the pair as one change: the linearization point of
        // rotation. Writers after this use only the new pair.
        active = new MemTable(active.generation() + 1, memtableMaxBytes);
        activeLog = nextLog;
        view = new EngineView(frozen, view.tables());
        previousLog.close();
        updatePressureLocked();
        // Pair switched, flush not yet requested.
        failpoint(FailpointNames.ROTATION_AFTER_SWITCH_BEFORE_FLUSH, ctx);

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

        // The frozen slot is free again: waiting writers may rotate now.
        updatePressureLocked();
        rotationSlotFree.signalAll();
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

            // Snapshot taken: the read lock keeps it stable, so a PAUSE here
            // shows writers waiting and the scan seeing the old state (API-005).
            failpoint(FailpointNames.SCAN_AFTER_SNAPSHOT_BEFORE_MERGE, ctx);
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
        // Stops the maintenance worker; the engine itself owns no files yet.
        flushScheduler.close();
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
     * Hits an engine failpoint (CP1 §26.1). These five points do no I/O of
     * their own, so {@code IO_ERROR} is not one of their mappings; if a
     * rule injects one anyway, the engine treats it as an unexpected
     * internal failure and enters FAILED rather than guess an outcome
     * (TEAM CHOICE, docs/ODLUKE.md). {@code PAUSE} and {@code CRASH} act
     * inside the failpoint implementation.
     */
    private void failpoint(String name, OpContext ctx) throws StorageException {
        try {
            failpoints.hit(name, ctx);
        } catch (InjectedIOException e) {
            throw fail(new StorageException(ErrorCode.INTERNAL_ERROR, null,
                    "Injected failure at " + name, e));
        }
    }

    /**
     * Moves to FAILED (first error wins), wakes writers waiting on
     * backpressure so they fail at once instead of at their deadline
     * (CP1 §22), and returns the error to throw. Callable with the write
     * lock, with the read lock, or with no engine lock at all.
     */
    private StorageException fail(StorageException error) {
        synchronized (failureLock) {
            if (lastFatalError == null) {
                lastFatalError = error;
            }
            mode = EngineMode.FAILED;
        }
        wakeWaitingWriters();
        return error;
    }

    private void wakeWaitingWriters() {
        if (engineLock.isWriteLockedByCurrentThread()) {
            rotationSlotFree.signalAll();
            return;
        }
        // Signalling needs the write lock. A reader cannot upgrade its read
        // lock, so a short-lived thread takes the write lock once it is free.
        // A writer about to wait still holds the write lock, so this signal
        // can only arrive after it is already waiting: none is missed.
        Thread.ofVirtual().name("engine-failed-wakeup").start(() -> {
            Lock writeLock = engineLock.writeLock();
            writeLock.lock();
            try {
                rotationSlotFree.signalAll();
            } finally {
                writeLock.unlock();
            }
        });
    }

    @Override
    public void reportFatalError(StorageException cause) {
        fail(Objects.requireNonNull(cause, "cause"));
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
