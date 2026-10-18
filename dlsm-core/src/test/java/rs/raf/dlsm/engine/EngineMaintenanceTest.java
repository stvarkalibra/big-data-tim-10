package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EngineMaintenanceTest {

    private static final List<String> KEYS =
            List.of("alpha", "beta", "delta", "empty", "gamma", "ghost", "omega");

    private static OpContext ctx() {
        return OpContext.withTimeout("m", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Engine engine() {
        return TestEngines.withoutFlusher(EngineConfig.defaults());
    }

    /** Everything a client can observe: every GET plus one full SCAN. */
    private static Map<String, String> answers(Engine engine) throws StorageException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            GetResult r = engine.get(key, ctx());
            out.put("get " + key, r.isFound()
                    ? new String(r.value(), StandardCharsets.UTF_8) : "<none>");
        }
        List<String> scan = new ArrayList<>();
        for (InternalRecord r : engine.scan("a", "z", 1000, ctx()).items()) {
            scan.add(r.key() + "=" + new String(r.value(), StandardCharsets.UTF_8));
        }
        out.put("scan", scan.toString());
        return out;
    }

    /** What a flush worker does, minus the files: CP1 §19.1. */
    private static void flush(Engine engine, long tableId) throws Exception {
        FlushInput input = engine.oldestImmutable().orElseThrow();
        FakeTable table = FakeTable.flushOf(tableId, input.frozen());
        engine.underEngineWriteLock(ctx(), () -> {
            // ... the manifest naming the table would be published here ...
            engine.installFlushResult(input.memtableGeneration(), table);
            return null;
        });
    }

    /** What a compaction worker does, minus the files: CP1 §20.3. */
    private static void compactAll(Engine engine, long outputId) throws Exception {
        EngineView selected = engine.currentView();
        List<java.util.Iterator<InternalRecord>> inputs = new ArrayList<>();
        for (TableSource table : selected.tables()) {
            inputs.add(table.iterator(table.meta().minKey()));
        }
        List<InternalRecord> merged = new ArrayList<>();
        new MergingIterator(inputs).forEachRemaining(merged::add);
        FakeTable output = new FakeTable(outputId, merged.toArray(new InternalRecord[0]));

        engine.underEngineWriteLock(ctx(), () -> {
            engine.installCompactionResult(Set.copyOf(selected.tableIds()), output);
            return null;
        });
    }

    @Test
    void engineIsTheMaintenanceHost() {
        MaintenanceHost host = engine();

        assertTrue(host.oldestImmutable().isEmpty());
        assertEquals(EngineView.EMPTY, host.currentView());
    }

    @Test
    void fullCycleNeverChangesWhatClientsSee() throws Exception {
        Engine engine = engine();
        engine.put("alpha", bytes("A1"), ctx());
        engine.put("beta", bytes("B"), ctx());
        engine.put("empty", new byte[0], ctx());
        engine.put("alpha", bytes("A2"), ctx());
        Map<String, String> expected = answers(engine);

        // Rotate generation 1 and flush it as table 1.
        assertTrue(engine.rotateIfActiveContainsUpTo(engine.maxSequenceInEngine(ctx()), ctx()));
        assertEquals(expected, answers(engine), "after rotation");
        FlushInput input = engine.oldestImmutable().orElseThrow();
        assertEquals(1, input.memtableGeneration());
        assertEquals(List.of(1L), input.walSegmentIds());
        assertEquals(4, input.maxSequence());

        flush(engine, 1);
        assertEquals(expected, answers(engine), "after first flush");
        assertNull(engine.currentView().immutable());
        assertEquals(List.of(1L), engine.currentView().tableIds());

        // Newer versions and a tombstone go to generation 2, flushed as table 2.
        engine.delete("beta", ctx());
        engine.put("delta", bytes("D"), ctx());
        engine.put("alpha", bytes("A3"), ctx());
        engine.delete("ghost", ctx());
        expected = answers(engine);
        assertTrue(engine.rotateIfActiveContainsUpTo(engine.maxSequenceInEngine(ctx()), ctx()));
        assertEquals(2, engine.oldestImmutable().orElseThrow().memtableGeneration());
        flush(engine, 2);
        assertEquals(expected, answers(engine), "after second flush");
        assertEquals(List.of(1L, 2L), engine.currentView().tableIds());

        // Writes keep going into generation 3 meanwhile.
        engine.put("omega", bytes("O"), ctx());
        expected = answers(engine);

        // Major compaction of tables 1 and 2 into table 3.
        compactAll(engine, 3);
        assertEquals(expected, answers(engine), "after compaction");
        assertEquals(List.of(3L), engine.currentView().tableIds());

        // Tombstones survive compaction (I8): beta stays deleted.
        TableSource compacted = engine.currentView().table(3).orElseThrow();
        assertTrue(compacted.get("beta").orElseThrow().isTombstone());
        assertTrue(compacted.get("ghost").orElseThrow().isTombstone());
    }

    @Test
    void installRequiresTheEngineWriteLock() throws Exception {
        Engine engine = engine();
        engine.put("a", bytes("1"), ctx());
        engine.rotateIfActiveContainsUpTo(1, ctx());
        FlushInput input = engine.oldestImmutable().orElseThrow();
        FakeTable table = FakeTable.flushOf(1, input.frozen());

        assertThrows(IllegalStateException.class,
                () -> engine.installFlushResult(input.memtableGeneration(), table));
        assertThrows(IllegalStateException.class,
                () -> engine.installCompactionResult(Set.of(1L), table));
        assertNotNull(engine.currentView().immutable(), "view unchanged");
    }

    @Test
    void installFlushResultRejectsWrongGenerationOrTable() throws Exception {
        Engine engine = engine();
        engine.put("a", bytes("1"), ctx());
        engine.put("b", bytes("2"), ctx());

        // No frozen generation yet.
        assertInstall(engine, 1, new FakeTable(1, InternalRecord.put("a", 1, bytes("1"))));

        engine.rotateIfActiveContainsUpTo(2, ctx());
        ImmutableMemTable frozen = engine.currentView().immutable();

        assertInstall(engine, 99, FakeTable.flushOf(1, frozen));          // wrong generation
        assertInstall(engine, 1, new FakeTable(1,                         // missing a record
                InternalRecord.put("b", 2, bytes("2"))));
        assertInstall(engine, 1, new FakeTable(1,                         // stale content
                InternalRecord.put("a", 1, bytes("1")), InternalRecord.put("b", 1_000, bytes("2"))));

        assertSame(frozen, engine.currentView().immutable(), "view unchanged");
        flush(engine, 1);
        assertEquals(List.of(1L), engine.currentView().tableIds());
    }

    @Test
    void installFlushResultRejectsAlreadyPublishedTableId() throws Exception {
        Engine engine = engine();
        engine.put("a", bytes("1"), ctx());
        engine.rotateIfActiveContainsUpTo(1, ctx());
        flush(engine, 1);
        engine.put("b", bytes("2"), ctx());
        engine.rotateIfActiveContainsUpTo(2, ctx());

        assertInstall(engine, 2, FakeTable.flushOf(1, engine.currentView().immutable()));
    }

    private static void assertInstall(Engine engine, long generation, TableSource table) {
        Exception e = assertThrows(Exception.class, () -> engine.underEngineWriteLock(ctx(), () -> {
            engine.installFlushResult(generation, table);
            return null;
        }));
        assertInstanceOf(IllegalStateException.class, e);
    }

    @Test
    void installCompactionResultRejectsStaleInputs() throws Exception {
        Engine engine = engine();
        engine.put("a", bytes("1"), ctx());
        engine.rotateIfActiveContainsUpTo(1, ctx());
        flush(engine, 1);
        FakeTable output = new FakeTable(5, InternalRecord.put("a", 1, bytes("1")));

        for (Set<Long> inputs : List.of(Set.<Long>of(), Set.of(1L, 7L))) {
            Exception e = assertThrows(Exception.class, () -> engine.underEngineWriteLock(ctx(), () -> {
                engine.installCompactionResult(inputs, output);
                return null;
            }));
            assertInstanceOf(IllegalStateException.class, e, inputs.toString());
        }

        // An output that lost the newest version is rejected too.
        FakeTable wrong = new FakeTable(6, InternalRecord.put("a", 9, bytes("x")));
        assertThrows(IllegalStateException.class, () -> engine.underEngineWriteLock(ctx(), () -> {
            engine.installCompactionResult(Set.of(1L), wrong);
            return null;
        }));
        assertEquals(List.of(1L), engine.currentView().tableIds(), "view unchanged");
    }

    @Test
    void compactionKeepsTablesItDidNotSelect() throws Exception {
        Engine engine = engine();
        for (int i = 1; i <= 3; i++) {
            engine.put("k" + i, bytes("v" + i), ctx());
            engine.rotateIfActiveContainsUpTo(i, ctx());
            flush(engine, i);
        }
        Map<String, String> before = Map.of(
                "k1", "v1", "k2", "v2", "k3", "v3");

        List<java.util.Iterator<InternalRecord>> inputs = List.of(
                engine.currentView().table(1).orElseThrow().iterator("k1"),
                engine.currentView().table(2).orElseThrow().iterator("k1"));
        List<InternalRecord> merged = new ArrayList<>();
        new MergingIterator(inputs).forEachRemaining(merged::add);
        FakeTable output = new FakeTable(4, merged.toArray(new InternalRecord[0]));

        engine.underEngineWriteLock(ctx(), () -> {
            engine.installCompactionResult(Set.of(1L, 2L), output);
            return null;
        });

        assertEquals(List.of(3L, 4L), engine.currentView().tableIds());
        for (Map.Entry<String, String> e : before.entrySet()) {
            assertArrayEquals(bytes(e.getValue()), engine.get(e.getKey(), ctx()).value());
        }
    }

    @Test
    void stepRunsWhileReadersAreBlocked() throws Exception {
        // Publication is atomic for readers: none can run inside the step.
        Engine engine = engine();
        engine.put("a", bytes("1"), ctx());
        AtomicReference<ErrorCode> readerResult = new AtomicReference<>();

        engine.underEngineWriteLock(ctx(), () -> {
            Thread reader = new Thread(() -> {
                try {
                    engine.get("a", OpContext.withTimeout("r", 150));
                } catch (StorageException e) {
                    readerResult.set(e.code());
                }
            });
            reader.start();
            reader.join();
            return null;
        });

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, readerResult.get());
    }

    @Test
    void stepFailurePropagatesAndReleasesTheLock() throws Exception {
        Engine engine = engine();
        IOException failure = new IOException("manifest rename failed");

        assertSame(failure, assertThrows(IOException.class,
                () -> engine.underEngineWriteLock(ctx(), () -> {
                    throw failure;
                })));
        assertEquals(1, engine.put("a", bytes("1"), ctx()).sequence(), "lock released");
        assertEquals("ok", engine.underEngineWriteLock(ctx(), () -> "ok"));
    }

    @Test
    void underEngineWriteLockHonorsTheDeadline() throws Exception {
        Engine engine = engine();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> engine.underEngineWriteLock(ctx(), () -> {
                holding.countDown();
                return release.await(5, TimeUnit.SECONDS);
            }));
            assertTrue(holding.await(5, TimeUnit.SECONDS));

            StorageException e = assertThrows(StorageException.class,
                    () -> engine.underEngineWriteLock(OpContext.withTimeout("late", 100), () -> "never"));
            assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.code());

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failedEnginePublishesNothing() throws Exception {
        FakeTable corrupt = new FakeTable(1, InternalRecord.put("a", 1, bytes("1")));
        corrupt.corruptGet = true;
        InMemoryWal wal = new InMemoryWal();
        Engine engine = new Engine(EngineConfig.defaults(), wal, wal.createSegment(ctx()),
                SequenceAllocator.after(1), new EngineView(null, List.of(corrupt)));
        assertThrows(StorageException.class, () -> engine.get("a", ctx()));

        StorageException e = assertThrows(StorageException.class,
                () -> engine.underEngineWriteLock(ctx(), () -> "never"));
        assertEquals(ErrorCode.NODE_NOT_READY, e.code());
    }
}
