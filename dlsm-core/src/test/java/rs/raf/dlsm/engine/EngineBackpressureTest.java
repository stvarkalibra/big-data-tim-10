package rs.raf.dlsm.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class EngineBackpressureTest {

    /** Two "kN"/"v" entries (35 logical bytes each) fill a generation. */
    private static final long LIMIT = 70;

    private final ExecutorService writers = Executors.newCachedThreadPool();

    @AfterEach
    void stopWriters() {
        writers.shutdownNow();
    }

    private static OpContext ctx() {
        return OpContext.withTimeout("bp", 5000);
    }

    private static byte[] v() {
        return "v".getBytes(StandardCharsets.UTF_8);
    }

    /** Bounded polling of an observable state: the condition is the proof. */
    private static void awaitTrue(BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("Timed out waiting until " + what);
            }
            Thread.sleep(1);
        }
    }

    /** Fills generation 1, rotates it, then fills generation 2: frozen slot taken. */
    private static Engine backpressuredEngine() throws StorageException {
        Engine engine = TestEngines.withoutFlusher(new EngineConfig(LIMIT));
        engine.put("k1", v(), ctx());
        engine.put("k2", v(), ctx());   // generation 1 full
        engine.put("k3", v(), ctx());   // rotates generation 1 into the frozen slot
        engine.put("k4", v(), ctx());   // generation 2 full while 1 waits for flush
        return engine;
    }

    /** What the flush worker does, without files. */
    private static void flush(Engine engine, long tableId) throws Exception {
        FlushInput input = engine.oldestImmutable().orElseThrow();
        FakeTable table = FakeTable.flushOf(tableId, input.frozen());
        engine.underEngineWriteLock(ctx(), () -> {
            engine.installFlushResult(input.memtableGeneration(), table);
            return null;
        });
    }

    @Test
    void fullActiveWithFrozenSlotTakenIsBackpressured() throws Exception {
        Engine engine = backpressuredEngine();

        assertEquals(EngineMode.BACKPRESSURED, engine.mode());
        assertTrue(engine.writeBackpressure());
        assertEquals(Lifecycle.READY, engine.lifecycle(), "still READY (CP1 §22)");
        assertEquals(0, engine.backpressuredWriters());
    }

    @Test
    void writerWaitsWithoutBlockingReadsAndResumesAfterFlush() throws Exception {
        Engine engine = backpressuredEngine();

        Future<MutationResult> blocked = writers.submit(() -> engine.put("k5", v(), ctx()));
        awaitTrue(() -> engine.backpressuredWriters() == 1, "the writer waits");
        assertFalse(blocked.isDone());

        // The waiting writer released the lock: reads still work (CP1 §10 example).
        assertTrue(engine.get("k1", ctx()).isFound());
        assertTrue(engine.get("k4", ctx()).isFound());
        assertEquals(4, engine.scan("k", "l", 10, ctx()).items().size());

        // Flush publishes generation 1 and wakes the writer, which rotates 2.
        flush(engine, 1);
        assertEquals(5, blocked.get(5, TimeUnit.SECONDS).sequence());
        assertEquals(0, engine.backpressuredWriters());
        assertEquals(2, engine.currentView().immutable().generation());
        assertEquals(EngineMode.SERVING, engine.mode());
        assertTrue(engine.get("k5", ctx()).isFound());
    }

    @Test
    void writerGivesUpAtItsDeadlineWithoutConsumingASequence() throws Exception {
        Engine engine = backpressuredEngine();

        long start = System.nanoTime();
        StorageException e = assertThrows(StorageException.class,
                () -> engine.put("k5", v(), OpContext.withTimeout("late", 150)));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.code());
        assertTrue(waitedMs >= 100, "waited " + waitedMs + " ms");
        assertEquals(0, engine.backpressuredWriters());
        assertFalse(engine.get("k5", ctx()).isFound(), "never became visible");

        // Not fatal: after the flush the next write gets the very next sequence.
        flush(engine, 1);
        assertEquals(5, engine.put("k5", v(), ctx()).sequence());
    }

    @Test
    void allWaitingWritersCompleteAfterFlushes() throws Exception {
        Engine engine = backpressuredEngine();

        List<Future<MutationResult>> waiting = new ArrayList<>();
        for (int i = 5; i <= 8; i++) {
            String key = "k" + i;
            waiting.add(writers.submit(() -> engine.put(key, v(), ctx())));
        }
        awaitTrue(() -> engine.backpressuredWriters() == 4, "four writers wait");

        // Each flush frees the slot for one more rotation; keep flushing
        // until every writer is through, never dropping a write (CP1 §10).
        long tableId = 1;
        while (!waiting.stream().allMatch(Future::isDone)) {
            if (engine.oldestImmutable().isPresent()) {
                flush(engine, tableId++);
            }
            Thread.sleep(1);
        }

        List<Long> sequences = new ArrayList<>();
        for (Future<MutationResult> f : waiting) {
            sequences.add(f.get().sequence());
        }
        assertEquals(List.of(5L, 6L, 7L, 8L), sequences.stream().sorted().toList());
        for (int i = 1; i <= 8; i++) {
            assertTrue(engine.get("k" + i, ctx()).isFound(), "k" + i);
        }
        assertEquals(0, engine.backpressuredWriters());
    }

    // ---------- fatal transitions wake waiting writers (CP1 §22) ----------

    /** Like backpressuredEngine, plus a published table that is corrupt for key "zz". */
    private static Engine backpressuredEngineWithCorruptTable() throws StorageException {
        FakeTable corrupt = new FakeTable(9,
                rs.raf.dlsm.common.InternalRecord.put("zz", 1, v()));
        corrupt.corruptGet = true;
        InMemoryWal wal = new InMemoryWal();
        Engine engine = new Engine(new EngineConfig(LIMIT), wal, wal.createSegment(ctx()),
                SequenceAllocator.after(1), new EngineView(null, List.of(corrupt)));
        engine.put("k1", v(), ctx());
        engine.put("k2", v(), ctx());
        engine.put("k3", v(), ctx());
        engine.put("k4", v(), ctx());
        assertEquals(EngineMode.BACKPRESSURED, engine.mode());
        return engine;
    }

    private Future<ErrorCode> waitingWriter(Engine engine) {
        return writers.submit(() -> {
            try {
                engine.put("k9", v(), OpContext.withTimeout("waiter", 10_000));
                return null;
            } catch (StorageException e) {
                return e.code();
            }
        });
    }

    @Test
    void corruptionFoundByAReaderWakesWaitingWriters() throws Exception {
        Engine engine = backpressuredEngineWithCorruptTable();
        Future<ErrorCode> waiter = waitingWriter(engine);
        awaitTrue(() -> engine.backpressuredWriters() == 1, "the writer waits");

        // A reader, holding only the read lock, finds the corrupt table.
        assertThrows(StorageException.class, () -> engine.get("zz", ctx()));

        // The writer fails promptly, long before its 10 s deadline.
        assertEquals(ErrorCode.NODE_NOT_READY, waiter.get(3, TimeUnit.SECONDS));
        assertEquals(EngineMode.FAILED, engine.mode());
        assertEquals("DATA_BLOCK", engine.lastFatalError().orElseThrow().category());
        assertEquals(0, engine.backpressuredWriters());
    }

    @Test
    void reportFatalErrorFromMaintenanceWakesWaitingWriters() throws Exception {
        Engine engine = backpressuredEngine();
        Future<ErrorCode> waiter = waitingWriter(engine);
        awaitTrue(() -> engine.backpressuredWriters() == 1, "the writer waits");

        StorageException cause = new StorageException(ErrorCode.STORAGE_IO,
                "MANIFEST_INVALID", "Directory sync after manifest rename failed");
        MaintenanceHost host = engine;
        host.reportFatalError(cause);   // without any engine lock

        assertEquals(ErrorCode.NODE_NOT_READY, waiter.get(3, TimeUnit.SECONDS));
        assertSame(cause, engine.lastFatalError().orElseThrow());
        assertEquals(Lifecycle.FAILED, engine.lifecycle());
    }

    @Test
    void reportFatalErrorUnderTheWriteLockWakesWritersToo() throws Exception {
        Engine engine = backpressuredEngine();
        Future<ErrorCode> waiter = waitingWriter(engine);
        awaitTrue(() -> engine.backpressuredWriters() == 1, "the writer waits");

        engine.underEngineWriteLock(ctx(), () -> {
            engine.reportFatalError(new StorageException(ErrorCode.STORAGE_IO, "lost"));
            return null;
        });

        assertEquals(ErrorCode.NODE_NOT_READY, waiter.get(3, TimeUnit.SECONDS));
    }

    @Test
    void firstFatalErrorIsKeptAndEverythingIsRefusedAfterwards() throws Exception {
        Engine engine = backpressuredEngine();
        StorageException first = new StorageException(ErrorCode.STORAGE_IO, "first");
        engine.reportFatalError(first);
        engine.reportFatalError(new StorageException(ErrorCode.INTERNAL_ERROR, "second"));

        assertSame(first, engine.lastFatalError().orElseThrow());
        for (Callable call : List.<Callable>of(
                () -> engine.put("x", v(), ctx()),
                () -> engine.get("k1", ctx()),
                () -> engine.maxSequenceInEngine(ctx()),
                () -> engine.rotateIfActiveContainsUpTo(1, ctx()),
                () -> engine.underEngineWriteLock(ctx(), () -> "never"))) {
            StorageException e = assertThrows(StorageException.class, call::run);
            assertEquals(ErrorCode.NODE_NOT_READY, e.code());
        }
    }

    @FunctionalInterface
    private interface Callable {
        Object run() throws Exception;
    }

    @Test
    void flushAloneEndsBackpressureEvenWithoutWaiters() throws Exception {
        Engine engine = backpressuredEngine();

        flush(engine, 1);

        assertEquals(EngineMode.SERVING, engine.mode());
        assertFalse(engine.writeBackpressure());
        // The next write rotates the full generation 2 and proceeds at once.
        assertEquals(5, engine.put("k5", v(), ctx()).sequence());
        assertEquals(2, engine.currentView().immutable().generation());
    }
}
