package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class EngineRotationTest {

    /** WAL whose segments remember what was appended to them. */
    private static final class RecordingWal implements WalFactory {
        final Map<Long, Segment> segments = new TreeMap<>();
        long nextId = 1;
        boolean failNextCreate;

        @Override
        public MutationLog createSegment(OpContext ctx) throws IOException {
            if (failNextCreate) {
                failNextCreate = false;
                throw new IOException("injected segment creation failure");
            }
            Segment segment = new Segment(nextId++);
            segments.put(segment.id, segment);
            return segment;
        }

        List<Long> appendedTo(long segmentId) {
            return segments.get(segmentId).appended;
        }
    }

    private static final class Segment implements MutationLog {
        final long id;
        final List<Long> appended = new ArrayList<>();
        boolean closed;

        Segment(long id) {
            this.id = id;
        }

        @Override
        public long segmentId() {
            return id;
        }

        @Override
        public void append(InternalRecord record, OpContext ctx) {
            assertFalse(closed, "append to a closed segment");
            appended.add(record.sequence());
        }

        @Override
        public void force(OpContext ctx) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Counts flushNeeded calls. */
    private static final class CountingScheduler implements FlushScheduler {
        int flushNeeded;

        @Override
        public void flushNeeded() {
            flushNeeded++;
        }

        @Override
        public void checkAutoCompaction() {
        }
    }

    private final RecordingWal wal = new RecordingWal();
    private final CountingScheduler scheduler = new CountingScheduler();

    private Engine engine(long memtableMaxBytes) throws IOException {
        Engine engine = new Engine(new EngineConfig(memtableMaxBytes), wal,
                wal.createSegment(ctx()), SequenceAllocator.forNewDatabase(),
                EngineView.EMPTY);
        engine.setFlushScheduler(scheduler);
        return engine;
    }

    private static OpContext ctx() {
        return OpContext.withTimeout("t", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String get(Engine engine, String key) throws StorageException {
        GetResult r = engine.get(key, ctx());
        return r.isFound() ? new String(r.value(), StandardCharsets.UTF_8) : null;
    }

    private static List<String> scan(Engine engine) throws StorageException {
        List<String> out = new ArrayList<>();
        for (InternalRecord r : engine.scan("a", "z", 100, ctx()).items()) {
            out.add(r.key() + "=" + new String(r.value(), StandardCharsets.UTF_8));
        }
        return out;
    }

    // ---------- manual rotation (manual flush, CP1 §23.1) ----------

    @Test
    void manualRotationFreezesActiveOntoItsSegment() throws Exception {
        Engine engine = engine(1 << 20);
        engine.put("alpha", bytes("A"), ctx());
        engine.delete("beta", ctx());

        assertEquals(2, engine.maxSequenceInEngine(ctx()));
        assertTrue(engine.rotateIfActiveContainsUpTo(2, ctx()));

        ImmutableMemTable frozen = engine.currentView().immutable();
        assertEquals(1, frozen.generation());
        assertEquals(List.of(1L), frozen.walSegmentIds());
        assertEquals(2, frozen.entryCount());
        assertEquals(2, frozen.maxSequence());
        assertTrue(wal.segments.get(1L).closed);
        assertEquals(1, scheduler.flushNeeded);

        // New writes go to the new pair: generation 2 on segment 2.
        engine.put("gamma", bytes("G"), ctx());
        assertEquals(List.of(1L, 2L), wal.appendedTo(1));
        assertEquals(List.of(3L), wal.appendedTo(2));
        assertEquals(3, engine.maxSequenceInEngine(ctx()));
    }

    @Test
    void readsSeeFrozenAndActiveGenerations() throws Exception {
        Engine engine = engine(1 << 20);
        engine.put("alpha", bytes("old"), ctx());
        engine.put("beta", bytes("B"), ctx());
        engine.put("delta", bytes("D"), ctx());
        engine.rotateIfActiveContainsUpTo(3, ctx());

        engine.put("alpha", bytes("new"), ctx());   // newer than the frozen one
        engine.delete("beta", ctx());               // hides the frozen value

        assertEquals("new", get(engine, "alpha"));
        assertNull(get(engine, "beta"));
        assertEquals("D", get(engine, "delta"));
        assertEquals(List.of("alpha=new", "delta=D"), scan(engine));
    }

    @Test
    void noRotationWhenActiveHoldsNothingUpToTarget() throws Exception {
        Engine engine = engine(1 << 20);
        assertFalse(engine.rotateIfActiveContainsUpTo(0, ctx()), "empty active");
        assertEquals(0, engine.maxSequenceInEngine(ctx()));

        engine.put("a", bytes("1"), ctx());
        assertFalse(engine.rotateIfActiveContainsUpTo(0, ctx()), "only newer records");
        assertNull(engine.currentView().immutable());
        assertEquals(0, scheduler.flushNeeded);
    }

    @Test
    void secondRotationWhileFrozenExistsIsRejected() throws Exception {
        Engine engine = engine(1 << 20);
        engine.put("a", bytes("1"), ctx());
        engine.rotateIfActiveContainsUpTo(1, ctx());
        engine.put("b", bytes("2"), ctx());

        assertThrows(IllegalStateException.class,
                () -> engine.rotateIfActiveContainsUpTo(2, ctx()));
        assertEquals(1, engine.currentView().immutable().generation());
    }

    @Test
    void failedSegmentCreationKeepsTheOldPair() throws Exception {
        Engine engine = engine(1 << 20);
        engine.put("a", bytes("1"), ctx());
        wal.failNextCreate = true;

        StorageException e = assertThrows(StorageException.class,
                () -> engine.rotateIfActiveContainsUpTo(1, ctx()));
        assertEquals(ErrorCode.STORAGE_IO, e.code());

        // Not fatal: nothing was appended behind an uncertain tail.
        assertEquals(EngineMode.SERVING, engine.mode());
        assertNull(engine.currentView().immutable());
        engine.put("b", bytes("2"), ctx());
        assertEquals(List.of(1L, 2L), wal.appendedTo(1));
        assertEquals("1", get(engine, "a"));
    }

    // ---------- automatic rotation (CP1 §10) ----------

    @Test
    void fullGenerationRotatesBeforeTheNextAppend() throws Exception {
        // Each "kN"/"v" entry is 32 + 2 + 1 = 35 logical bytes.
        Engine engine = engine(70);
        engine.put("k1", bytes("v"), ctx());
        engine.put("k2", bytes("v"), ctx());   // reaches the limit; still completes
        assertNull(engine.currentView().immutable());

        engine.put("k3", bytes("v"), ctx());   // rotation happens first

        ImmutableMemTable frozen = engine.currentView().immutable();
        assertEquals(List.of("k1", "k2"), keys(frozen));
        assertEquals(List.of(1L, 2L), wal.appendedTo(1));
        assertEquals(List.of(3L), wal.appendedTo(2));
        assertEquals(1, scheduler.flushNeeded);
        assertEquals(List.of("k1=v", "k2=v", "k3=v"), scan(engine));
    }

    @Test
    void oversizedValueIsAcceptedThenRotated() throws Exception {
        Engine engine = engine(64);
        engine.put("big", new byte[1000], ctx());
        engine.put("next", bytes("x"), ctx());

        assertEquals(List.of("big"), keys(engine.currentView().immutable()));
        assertEquals(1000, engine.get("big", ctx()).value().length);
    }

    @Test
    void failedAutomaticRotationFailsOnlyThatMutation() throws Exception {
        Engine engine = engine(35);
        engine.put("k1", bytes("v"), ctx());
        wal.failNextCreate = true;

        StorageException e = assertThrows(StorageException.class,
                () -> engine.put("k2", bytes("v"), ctx()));
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertNull(get(engine, "k2"));

        // The next mutation retries the rotation and succeeds.
        engine.put("k2", bytes("v"), ctx());
        assertEquals(List.of("k1"), keys(engine.currentView().immutable()));
        assertEquals("v", get(engine, "k2"));
        assertEquals(EngineMode.SERVING, engine.mode());
    }

    @Test
    void maxSequenceIncludesPublishedTables() throws Exception {
        InMemoryWal memory = new InMemoryWal();
        Engine engine = new Engine(EngineConfig.defaults(), memory,
                memory.createSegment(ctx()), SequenceAllocator.after(40),
                new EngineView(null, List.of(new FakeTable(1,
                        InternalRecord.put("a", 40, new byte[0])))));

        assertEquals(40, engine.maxSequenceInEngine(ctx()));
        engine.delete("b", ctx());
        assertEquals(41, engine.maxSequenceInEngine(ctx()));
    }

    @Test
    void inMemoryWalHandsOutIncreasingSegmentIds() {
        InMemoryWal memory = new InMemoryWal();

        assertEquals(1, memory.createSegment(ctx()).segmentId());
        assertEquals(2, memory.createSegment(ctx()).segmentId());
    }

    private static List<String> keys(ImmutableMemTable table) {
        List<String> keys = new ArrayList<>();
        table.iterator().forEachRemaining(r -> keys.add(r.key()));
        return keys;
    }
}
