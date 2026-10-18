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
        Engine engine = Engine.openInMemory(new EngineConfig(LIMIT));
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
