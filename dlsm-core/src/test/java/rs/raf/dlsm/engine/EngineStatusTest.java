package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EngineStatusTest {

    private static OpContext ctx() {
        return OpContext.withTimeout("status", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void flush(Engine engine, long tableId) throws Exception {
        FlushInput input = engine.oldestImmutable().orElseThrow();
        FakeTable table = FakeTable.flushOf(tableId, input.frozen());
        engine.underEngineWriteLock(ctx(), () -> {
            engine.installFlushResult(input.memtableGeneration(), table);
            return null;
        });
    }

    @Test
    void freshEngine() throws Exception {
        EngineStatus s = TestEngines.withoutFlusher(EngineConfig.defaults()).status(ctx());

        assertEquals(Lifecycle.READY, s.lifecycle());
        assertEquals(EngineMode.SERVING, s.engineMode());
        assertFalse(s.writeBackpressure());
        assertEquals(0, s.backpressuredWriters());
        assertNull(s.lastFatalError());
        assertEquals(1L, s.nextSequence());
        assertEquals(0, s.lastAcceptedSequence(), "0 on a new empty database");
        assertEquals(0, s.lastDurableSequence());
        assertEquals(new EngineStatus.Memtable(1, 1L, null, 0, 0, null, null, false),
                s.activeMemtable());
        assertNull(s.immutableMemtable());
        assertTrue(s.sstables().isEmpty());
    }

    @Test
    void cp1Api001Oracle() throws Exception {
        // Three PUTs of alpha: nextSequence 4, accepted = durable = 3, one entry.
        Engine engine = TestEngines.withoutFlusher(EngineConfig.defaults());
        engine.put("alpha", bytes("A"), ctx());
        engine.put("alpha", bytes("BB"), ctx());
        engine.put("alpha", new byte[0], ctx());

        EngineStatus s = engine.status(ctx());
        assertEquals(4L, s.nextSequence());
        assertEquals(3, s.lastAcceptedSequence());
        assertEquals(3, s.lastDurableSequence());
        assertEquals(1, s.activeMemtable().entryCount());
        assertEquals(32 + 5, s.activeMemtable().logicalBytes());
        assertEquals(1L, s.activeMemtable().minSequence());
        assertEquals(3L, s.activeMemtable().maxSequence());
    }

    @Test
    void rotationAndFlushAreVisible() throws Exception {
        Engine engine = TestEngines.withoutFlusher(EngineConfig.defaults());
        engine.put("a", bytes("1"), ctx());
        engine.delete("b", ctx());
        engine.rotateIfActiveContainsUpTo(2, ctx());
        engine.put("c", bytes("3"), ctx());

        EngineStatus rotated = engine.status(ctx());
        assertEquals(new EngineStatus.Memtable(1, null, List.of(1L), 2, (32 + 1 + 1) + (32 + 1),
                1L, 2L, null), rotated.immutableMemtable());
        assertEquals(2, rotated.activeMemtable().generation());
        assertEquals(2L, rotated.activeMemtable().walSegmentId());
        assertEquals(3, rotated.lastAcceptedSequence());

        flush(engine, 7);
        EngineStatus flushed = engine.status(ctx());
        assertNull(flushed.immutableMemtable());
        assertEquals(1, flushed.sstables().size());
        assertEquals(7, flushed.sstables().get(0).tableId());
        assertEquals(2, flushed.sstables().get(0).maxSequence());
        assertEquals(3, flushed.lastAcceptedSequence());
        assertEquals(3, flushed.lastDurableSequence());
    }

    @Test
    void tablesOpenedAtStartCountAsAcceptedAndDurable() throws Exception {
        InMemoryWal wal = new InMemoryWal();
        Engine engine = new Engine(EngineConfig.defaults(), wal, wal.createSegment(ctx()),
                SequenceAllocator.after(40), new EngineView(null, List.of(
                new FakeTable(9, InternalRecord.put("x", 40, new byte[0])),
                new FakeTable(4, InternalRecord.put("y", 12, new byte[0])))));

        EngineStatus s = engine.status(ctx());
        assertEquals(40, s.lastAcceptedSequence());
        assertEquals(40, s.lastDurableSequence());
        assertEquals(41L, s.nextSequence());
        assertEquals(List.of(4L, 9L), s.sstables().stream().map(m -> m.tableId()).toList(),
                "ascending by table id");
    }

    @Test
    void backpressureIsVisible() throws Exception {
        Engine engine = TestEngines.withoutFlusher(new EngineConfig(70));
        for (int i = 1; i <= 4; i++) {
            engine.put("k" + i, bytes("v"), ctx());
        }
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = pool.submit(() -> engine.put("k5", bytes("v"), ctx()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (engine.status(ctx()).backpressuredWriters() != 1) {
                assertTrue(System.nanoTime() - deadline < 0, "writer never waited");
                Thread.sleep(1);
            }

            EngineStatus s = engine.status(ctx());
            assertEquals(EngineMode.BACKPRESSURED, s.engineMode());
            assertTrue(s.writeBackpressure());
            assertEquals(Lifecycle.READY, s.lifecycle());
            assertTrue(s.activeMemtable().rotationRequired());

            flush(engine, 1);
            waiter.get(5, TimeUnit.SECONDS);
            assertEquals(0, engine.status(ctx()).backpressuredWriters());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void statusStillWorksWhenFailed() throws Exception {
        Engine engine = TestEngines.withoutFlusher(EngineConfig.defaults());
        engine.put("a", bytes("1"), ctx());
        engine.reportFatalError(new StorageException(ErrorCode.STORAGE_IO, "WAL_FRAME",
                "Corrupt WAL frame at offset 24"));

        EngineStatus s = engine.status(ctx());
        assertEquals(Lifecycle.FAILED, s.lifecycle());
        assertEquals(EngineMode.FAILED, s.engineMode());
        assertEquals(new EngineStatus.FatalError("WAL_FRAME", "Corrupt WAL frame at offset 24"),
                s.lastFatalError());
        assertEquals(1, s.lastAcceptedSequence());
    }

    @Test
    void fatalErrorWithoutCategoryUsesTheErrorCode() {
        assertEquals(new EngineStatus.FatalError("STORAGE_IO", "WAL append or force failed"),
                EngineStatus.FatalError.of(new StorageException(ErrorCode.STORAGE_IO,
                        "WAL append or force failed")));
    }

    @Test
    void failedForceConsumesTheSequenceButIsNotDurable() throws Exception {
        MutationLog failing = new MutationLog() {
            @Override
            public long segmentId() {
                return 1;
            }

            @Override
            public void append(InternalRecord record, OpContext ctx) {
            }

            @Override
            public void force(OpContext ctx) throws IOException {
                throw new IOException("injected");
            }

            @Override
            public void close() {
            }
        };
        Engine engine = new Engine(EngineConfig.defaults(), failing, SequenceAllocator.forNewDatabase());
        assertThrows(StorageException.class, () -> engine.put("a", bytes("1"), ctx()));

        EngineStatus s = engine.status(ctx());
        assertEquals(2L, s.nextSequence(), "sequence 1 was consumed (a gap)");
        assertEquals(0, s.lastAcceptedSequence());
        assertEquals(0, s.lastDurableSequence());
        assertEquals("STORAGE_IO", s.lastFatalError().code());
    }

    @Test
    void exhaustedSequencesShowNullNextSequence() throws Exception {
        InMemoryWal wal = new InMemoryWal();
        Engine engine = new Engine(EngineConfig.defaults(), wal.createSegment(ctx()),
                SequenceAllocator.after(Long.MAX_VALUE));

        assertNull(engine.status(ctx()).nextSequence());
    }

    @Test
    void readLockStepExcludesWritersButAllowsStatus() throws Exception {
        Engine engine = TestEngines.withoutFlusher(EngineConfig.defaults());

        EngineStatus inside = engine.underEngineReadLock(ctx(), () -> {
            StorageException e = assertThrows(StorageException.class, () -> {
                // A writer started now cannot get in before the step ends.
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    pool.submit(() -> engine.put("a", bytes("1"),
                            OpContext.withTimeout("w", 100))).get();
                } catch (java.util.concurrent.ExecutionException ex) {
                    throw (StorageException) ex.getCause();
                } finally {
                    pool.shutdownNow();
                }
            });
            assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.code());
            return engine.status(ctx());   // re-entrant read is fine
        });

        assertEquals(0, inside.lastAcceptedSequence());
    }
}
