package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryFlushTest {

    private static OpContext ctx() {
        return OpContext.withTimeout("mem", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static FlushInput frozen(InternalRecord... records) {
        MemTable table = new MemTable(3, 1 << 20);
        for (InternalRecord r : records) {
            table.apply(r);
        }
        return new FlushInput(table.freeze(List.of(3L)));
    }

    // ---------- MemoryTable ----------

    @Test
    void memoryTableHoldsExactlyTheFrozenRecords() {
        MemoryTable table = MemoryTable.of(7, frozen(
                InternalRecord.put("beta", 10, bytes("B")),
                InternalRecord.delete("alpha", 11),
                InternalRecord.put("gamma", 12, new byte[0])));

        assertEquals(7, table.tableId());
        assertEquals("sst-00000000000000000007.sst", table.meta().file());
        assertEquals("alpha", table.meta().minKey());
        assertEquals("gamma", table.meta().maxKey());
        assertEquals(10, table.meta().minSequence());
        assertEquals(12, table.meta().maxSequence());
        assertEquals(3, table.meta().entryCount());
        assertEquals((32 + 4 + 1) + (32 + 5) + (32 + 5), table.meta().sizeBytes());

        assertTrue(table.get("alpha").orElseThrow().isTombstone());
        assertEquals(0, table.get("gamma").orElseThrow().valueLength());
        assertTrue(table.get("delta").isEmpty());

        List<String> fromBeta = new ArrayList<>();
        table.iterator("b").forEachRemaining(r -> fromBeta.add(r.key()));
        assertEquals(List.of("beta", "gamma"), fromBeta);

        Iterator<InternalRecord> it = table.iterator("a");
        it.next();
        assertThrows(UnsupportedOperationException.class, it::remove);
    }

    // ---------- the in-memory node keeps writing past many generations ----------

    @Test
    void smallMemTableNeverStallsWrites() throws Exception {
        // ~2 entries per generation: dozens of rotations, flushes and
        // backpressure episodes, all resolved by the background flush.
        Engine engine = Engine.openInMemory(new EngineConfig(80));
        try {
            Map<String, String> model = new TreeMap<>();
            Random random = new Random(7);
            for (int i = 0; i < 300; i++) {
                String key = "k" + random.nextInt(60);
                if (random.nextInt(5) == 0) {
                    engine.delete(key, OpContext.withTimeout("w", 2000));
                    model.remove(key);
                } else {
                    String value = "v" + i;
                    engine.put(key, bytes(value), OpContext.withTimeout("w", 2000));
                    model.put(key, value);
                }
            }

            for (int k = 0; k < 60; k++) {
                String key = "k" + k;
                GetResult r = engine.get(key, ctx());
                assertEquals(model.get(key),
                        r.isFound() ? new String(r.value(), StandardCharsets.UTF_8) : null, key);
            }
            List<String> scanned = new ArrayList<>();
            for (InternalRecord r : engine.scan("a", "z", 1000, ctx()).items()) {
                scanned.add(r.key());
            }
            assertEquals(List.copyOf(model.keySet()), scanned);
            assertFalse(engine.currentView().tables().isEmpty(), "flushes happened");
        } finally {
            engine.close();
        }
    }

    @Test
    void concurrentWritersAllSucceed() throws Exception {
        Engine engine = Engine.openInMemory(new EngineConfig(80));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 50; i++) {
                        engine.put("t" + thread + "-" + i, bytes("v"),
                                OpContext.withTimeout("w", 5000));
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            assertEquals(200, engine.scan("a", "z", 1000, ctx()).items().size());
        } finally {
            pool.shutdownNow();
            engine.close();
        }
    }

    // ---------- failure handling ----------

    @Test
    void flusherPublishesNothingIntoAFailedEngine() throws Exception {
        Engine engine = TestEngines.withoutFlusher(new EngineConfig(70));
        engine.put("k1", bytes("v"), ctx());
        engine.put("k2", bytes("v"), ctx());
        engine.put("k3", bytes("v"), ctx());   // generation 1 is now frozen
        StorageException cause = new StorageException(ErrorCode.STORAGE_IO, "earlier");
        engine.reportFatalError(cause);

        InMemoryFlusher flusher = new InMemoryFlusher(engine);
        try {
            flusher.flushNeeded();
            flusher.awaitIdle(5, TimeUnit.SECONDS);

            assertNotNull(engine.currentView().immutable(), "nothing was published");
            assertTrue(engine.currentView().tables().isEmpty());
            assertSame(cause, engine.lastFatalError().orElseThrow(), "first error kept");
        } finally {
            flusher.close();
        }
    }

    @Test
    void flushRequestWithoutFrozenGenerationDoesNothing() throws Exception {
        Engine engine = TestEngines.withoutFlusher(EngineConfig.defaults());
        InMemoryFlusher flusher = new InMemoryFlusher(engine);
        try {
            flusher.flushNeeded();
            flusher.awaitIdle(5, TimeUnit.SECONDS);

            assertEquals(EngineView.EMPTY, engine.currentView());
            assertEquals(EngineMode.SERVING, engine.mode());
        } finally {
            flusher.close();
        }
    }

    @Test
    void closingTheEngineStopsTheFlusher() {
        Engine engine = Engine.openInMemory(EngineConfig.defaults());
        engine.close();

        // A late request after close is ignored, never thrown at the writer.
        InMemoryFlusher flusher = new InMemoryFlusher(engine);
        flusher.close();
        assertDoesNotThrow(flusher::flushNeeded);
    }
}
