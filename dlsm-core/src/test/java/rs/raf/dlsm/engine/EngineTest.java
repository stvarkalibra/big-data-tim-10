package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    private static final EngineConfig CONFIG = EngineConfig.defaults();

    /** Records every call; can fail append or force on demand. */
    private static class RecordingLog implements MutationLog {
        final List<Long> appended = Collections.synchronizedList(new ArrayList<>());
        final List<Long> forced = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failAppend;
        volatile boolean failForce;
        private long lastAppended;

        @Override
        public long segmentId() {
            return 1;
        }

        @Override
        public void close() {
        }

        @Override
        public void append(InternalRecord record, OpContext ctx) throws IOException {
            if (failAppend) {
                throw new IOException("injected append failure");
            }
            lastAppended = record.sequence();
            appended.add(record.sequence());
        }

        @Override
        public void force(OpContext ctx) throws IOException {
            if (failForce) {
                throw new IOException("injected force failure");
            }
            forced.add(lastAppended);
        }
    }

    private static OpContext ctx() {
        return OpContext.withTimeout("test", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(GetResult result) {
        return new String(result.value(), StandardCharsets.UTF_8);
    }

    private static List<String> pairs(ScanResult result) {
        List<String> out = new ArrayList<>();
        for (InternalRecord r : result.items()) {
            out.add(r.key() + "=" + new String(r.value(), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Engine engine(MutationLog log) {
        return new Engine(CONFIG, log, SequenceAllocator.forNewDatabase());
    }

    // ---------- basic semantics ----------

    @Test
    void startsServingAndReady() {
        Engine engine = Engine.openInMemory(CONFIG);

        assertEquals(EngineMode.SERVING, engine.mode());
        assertEquals(Lifecycle.READY, engine.lifecycle());
        assertTrue(engine.lastFatalError().isEmpty());
    }

    @Test
    void putGetOverwriteAndEmptyValue() throws StorageException {
        // CP1-API-001 shape, directly against the engine.
        Engine engine = Engine.openInMemory(CONFIG);
        byte[] binary = {0, (byte) 0xFF, 7};

        assertEquals(1, engine.put("alpha", binary, ctx()).sequence());
        assertArrayEquals(binary, engine.get("alpha", ctx()).value());

        assertEquals(2, engine.put("alpha", bytes("B"), ctx()).sequence());
        assertEquals("B", text(engine.get("alpha", ctx())));

        assertEquals(3, engine.put("alpha", new byte[0], ctx()).sequence());
        GetResult empty = engine.get("alpha", ctx());
        assertTrue(empty.isFound());
        assertEquals(0, empty.value().length);

        assertEquals(List.of("alpha="), pairs(engine.scan("alpha", "alphb", 10, ctx())));
    }

    @Test
    void deleteHidesKeyAndDeletingUnknownKeySucceeds() throws StorageException {
        // CP1-API-002 shape.
        Engine engine = Engine.openInMemory(CONFIG);
        engine.put("alpha", bytes("A"), ctx());

        assertEquals(2, engine.delete("alpha", ctx()).sequence());
        assertFalse(engine.get("alpha", ctx()).isFound());

        assertEquals(3, engine.delete("ghost", ctx()).sequence());
        assertFalse(engine.get("ghost", ctx()).isFound());
    }

    @Test
    void valueIsCopiedOnTheWayIn() throws StorageException {
        Engine engine = Engine.openInMemory(CONFIG);
        byte[] value = bytes("abc");
        engine.put("k", value, ctx());

        value[0] = 'X';
        assertEquals("abc", text(engine.get("k", ctx())));
    }

    @Test
    void scanFollowsCp1Api003() throws StorageException {
        Engine engine = Engine.openInMemory(CONFIG);
        engine.put("a", bytes("A"), ctx());
        engine.put("alpha", bytes("A1"), ctx());
        engine.put("beta", bytes("B"), ctx());
        engine.put("delta", bytes("D1"), ctx());
        engine.put("epsilon", new byte[0], ctx());
        engine.put("omega", bytes("O"), ctx());
        engine.put("zulu", bytes("Z"), ctx());
        engine.put("delta", bytes("D2"), ctx());
        engine.delete("beta", ctx());

        assertEquals(List.of("alpha=A1", "delta=D2", "epsilon="),
                pairs(engine.scan("alpha", "omega", 100, ctx())));
        assertEquals(List.of("alpha=A1", "delta=D2"),
                pairs(engine.scan("alpha", "omega", 2, ctx())));
        assertEquals(List.of("a=A"), pairs(engine.scan("a", "alpha", 100, ctx())));
        assertEquals(List.of("omega=O"), pairs(engine.scan("omega", "zulu", 100, ctx())));
    }

    // ---------- validation ----------

    @Test
    void invalidRequestsHaveNoSideEffects() throws StorageException {
        RecordingLog log = new RecordingLog();
        Engine engine = engine(log);

        assertCode(ErrorCode.INVALID_REQUEST, () -> engine.put("bad key", bytes("v"), ctx()));
        assertCode(ErrorCode.INVALID_REQUEST, () -> engine.put("k", null, ctx()));
        assertCode(ErrorCode.VALUE_TOO_LARGE, () -> engine.put("k",
                new byte[InternalRecord.MAX_VALUE_BYTES + 1], ctx()));
        assertCode(ErrorCode.INVALID_REQUEST, () -> engine.delete("", ctx()));
        assertCode(ErrorCode.INVALID_REQUEST, () -> engine.get("a/b", ctx()));
        assertCode(ErrorCode.INVALID_REQUEST, () -> engine.scan("b", "a", 10, ctx()));
        assertCode(ErrorCode.INVALID_REQUEST, () -> engine.scan("a", "b", 0, ctx()));

        // Nothing reached the WAL and no sequence was consumed.
        assertTrue(log.appended.isEmpty());
        assertEquals(1, engine.put("k", bytes("v"), ctx()).sequence());
        assertEquals(EngineMode.SERVING, engine.mode());
    }

    @Test
    void maximumValueIsAccepted() throws StorageException {
        Engine engine = Engine.openInMemory(CONFIG);
        engine.put("k", new byte[InternalRecord.MAX_VALUE_BYTES], ctx());

        assertEquals(InternalRecord.MAX_VALUE_BYTES, engine.get("k", ctx()).value().length);
    }

    // ---------- WAL ordering and failures ----------

    @Test
    void walOrderMatchesSequenceOrderAndEveryMutationIsForced() throws StorageException {
        RecordingLog log = new RecordingLog();
        Engine engine = engine(log);
        engine.put("a", bytes("1"), ctx());
        engine.delete("b", ctx());
        engine.put("a", bytes("2"), ctx());

        assertEquals(List.of(1L, 2L, 3L), log.appended);
        assertEquals(List.of(1L, 2L, 3L), log.forced);
    }

    @Test
    void appendFailureMovesToFailedAndStopsAllWalWrites() {
        RecordingLog log = new RecordingLog();
        Engine engine = engine(log);
        log.failAppend = true;

        assertCode(ErrorCode.STORAGE_IO, () -> engine.put("a", bytes("1"), ctx()));
        assertFailed(engine, ErrorCode.STORAGE_IO);

        log.failAppend = false;
        assertCode(ErrorCode.NODE_NOT_READY, () -> engine.put("b", bytes("2"), ctx()));
        assertCode(ErrorCode.NODE_NOT_READY, () -> engine.delete("b", ctx()));
        assertTrue(log.appended.isEmpty(), "never append behind an uncertain tail");
    }

    @Test
    void forceFailureMovesToFailedWithoutApplying() {
        RecordingLog log = new RecordingLog();
        Engine engine = engine(log);
        log.failForce = true;

        assertCode(ErrorCode.STORAGE_IO, () -> engine.put("a", bytes("1"), ctx()));
        assertFailed(engine, ErrorCode.STORAGE_IO);

        // Reads are rejected once FAILED (team choice), and no further appends.
        assertCode(ErrorCode.NODE_NOT_READY, () -> engine.get("a", ctx()));
        assertCode(ErrorCode.NODE_NOT_READY, () -> engine.scan("a", "z", 10, ctx()));
        assertEquals(List.of(1L), log.appended);
    }

    @Test
    void mutationIsInvisibleUntilForceCompletes() throws Exception {
        // I2: a reader running while the WAL force is in progress must not
        // see the new value; it is blocked by the write lock.
        AtomicReference<Object> readDuringForce = new AtomicReference<>();
        Engine[] holder = new Engine[1];

        MutationLog log = new MutationLog() {
            @Override
            public long segmentId() {
                return 1;
            }

            @Override
            public void close() {
            }

            @Override
            public void append(InternalRecord record, OpContext ctx) {
            }

            @Override
            public void force(OpContext ctx) throws IOException {
                Thread reader = new Thread(() -> {
                    try {
                        readDuringForce.set(holder[0].get("alpha",
                                OpContext.withTimeout("reader", 200)));
                    } catch (StorageException e) {
                        readDuringForce.set(e.code());
                    }
                });
                reader.start();
                try {
                    reader.join();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        };

        holder[0] = engine(log);
        holder[0].put("alpha", bytes("A"), ctx());

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, readDuringForce.get());
        assertEquals("A", text(holder[0].get("alpha", ctx())));
    }

    @Test
    void writerWaitingForTheLockGivesUpAtItsDeadline() throws Exception {
        CountDownLatch inForce = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingLog log = new RecordingLog() {
            @Override
            public void force(OpContext ctx) throws IOException {
                inForce.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                super.force(ctx);
            }
        };
        Engine engine = engine(log);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MutationResult> first = pool.submit(
                    () -> engine.put("a", bytes("1"), ctx()));
            assertTrue(inForce.await(5, TimeUnit.SECONDS));

            assertCode(ErrorCode.DEADLINE_EXCEEDED, () -> engine.put("b", bytes("2"),
                    OpContext.withTimeout("late", 100)));

            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS).sequence());
            // The timed-out writer never got a sequence.
            assertEquals(2, engine.put("c", bytes("3"), ctx()).sequence());
            assertEquals(EngineMode.SERVING, engine.mode());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void expiredDeadlineIsRejectedBeforeAnyWork() {
        RecordingLog log = new RecordingLog();
        Engine engine = engine(log);
        OpContext expired = new OpContext("old", System.nanoTime() - 1);

        assertCode(ErrorCode.DEADLINE_EXCEEDED, () -> engine.put("a", bytes("1"), expired));
        assertCode(ErrorCode.DEADLINE_EXCEEDED, () -> engine.get("a", expired));
        assertTrue(log.appended.isEmpty());
    }

    // ---------- concurrency ----------

    @Test
    void concurrentWritersToOneKeyAreTotallyOrdered() throws Exception {
        int threads = 8;
        int perThread = 250;
        RecordingLog log = new RecordingLog();
        Engine engine = engine(log);
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
        List<String> values = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String value = thread + ":" + i;
                        long seq = engine.put("alpha", bytes(value),
                                OpContext.withTimeout("w", 10_000)).sequence();
                        synchronized (sequences) {
                            sequences.add(seq);
                            values.add(value);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int total = threads * perThread;
        assertEquals(total, sequences.stream().distinct().count(), "sequences are unique");
        assertEquals(total, sequences.stream().mapToLong(Long::longValue).max().orElseThrow());

        // The WAL saw sequences in strictly increasing order.
        for (int i = 1; i < log.appended.size(); i++) {
            assertTrue(log.appended.get(i) > log.appended.get(i - 1));
        }

        // The visible value is the one written with the greatest sequence.
        String winner = values.get(sequences.indexOf((long) total));
        assertEquals(winner, text(engine.get("alpha", ctx())));
    }

    // ---------- sequence exhaustion ----------

    @Test
    void exhaustedSequencesMoveToFailed() throws StorageException {
        Engine engine = new Engine(CONFIG, new InMemoryWal().createSegment(ctx()),
                SequenceAllocator.after(Long.MAX_VALUE - 1));

        assertEquals(Long.MAX_VALUE, engine.put("a", bytes("1"), ctx()).sequence());
        assertCode(ErrorCode.INTERNAL_ERROR, () -> engine.put("b", bytes("2"), ctx()));
        assertFailed(engine, ErrorCode.INTERNAL_ERROR);
        assertEquals(SequenceAllocator.EXHAUSTED_CATEGORY,
                engine.lastFatalError().orElseThrow().category());
    }

    // ---------- helpers ----------

    @FunctionalInterface
    private interface Call {
        void run() throws StorageException;
    }

    private static void assertCode(ErrorCode expected, Call call) {
        StorageException e = assertThrows(StorageException.class, call::run);
        assertEquals(expected, e.code(), e.getMessage());
    }

    private static void assertFailed(Engine engine, ErrorCode cause) {
        assertEquals(EngineMode.FAILED, engine.mode());
        assertEquals(Lifecycle.FAILED, engine.lifecycle());
        assertEquals(cause, engine.lastFatalError().orElseThrow().code());
    }
}
