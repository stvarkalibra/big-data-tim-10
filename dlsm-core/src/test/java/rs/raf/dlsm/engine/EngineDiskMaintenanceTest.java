package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failures of published files and maintenance over real SSTable files:
 * corruption is never absence (I7), and flush and compaction change the
 * files but never the answers (CP1 §19, §20).
 */
class EngineDiskMaintenanceTest {

    private static final List<String> KEYS =
            List.of("alpha", "beta", "delta", "empty", "gamma", "ghost", "omega");

    @TempDir
    Path dir;

    private final StorageStats stats = new StorageStats();

    private static OpContext ctx() {
        return OpContext.withTimeout("disk", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static InternalRecord put(String key, long seq, String value) {
        return InternalRecord.put(key, seq, bytes(value));
    }

    private static Engine engineOver(List<TableSource> tables) {
        long lastUsed = new EngineView(null, tables).maxSequence();
        InMemoryWal wal = new InMemoryWal();
        return Engine.open(EngineConfig.defaults(),
                new RecoveredState(lastUsed, null, tables, wal.createSegment(ctx()), wal),
                host -> FlushScheduler.NONE);
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

    // ---------- failures of published files ----------

    @Test
    void corruptBlockOnDiskFailsTheEngineInsteadOfNotFound() throws Exception {
        TableSource table = DiskTables.write(dir, 1, List.of(
                put("alpha", 1, "A"), put("beta", 2, "B")), stats);
        Engine engine = engineOver(List.of(table));

        // Damage the first data block after the table was opened and validated.
        Path file = DiskTables.fileOf(dir, table);
        byte[] content = Files.readAllBytes(file);
        content[16 + 24] ^= 0x01;   // header is 16 bytes; inside the first block's records
        Files.write(file, content);

        StorageException e = assertThrows(StorageException.class,
                () -> engine.get("alpha", ctx()));
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals("DATA_BLOCK", e.category());
        assertEquals(EngineMode.FAILED, engine.mode());
        assertEquals("DATA_BLOCK", engine.lastFatalError().orElseThrow().category());

        // Every later request is refused; nothing is ever served as absent.
        StorageException after = assertThrows(StorageException.class,
                () -> engine.get("beta", ctx()));
        assertEquals(ErrorCode.NODE_NOT_READY, after.code());
    }

    @Test
    void corruptBlockFoundDuringScanFailsTheEngine() throws Exception {
        TableSource table = DiskTables.write(dir, 1, List.of(
                put("alpha", 1, "A"), put("beta", 2, "B")), stats);
        Engine engine = engineOver(List.of(table));
        Path file = DiskTables.fileOf(dir, table);
        byte[] content = Files.readAllBytes(file);
        content[16 + 24] ^= 0x01;
        Files.write(file, content);

        StorageException e = assertThrows(StorageException.class,
                () -> engine.scan("a", "z", 10, ctx()));
        assertEquals("DATA_BLOCK", e.category());
        assertEquals(EngineMode.FAILED, engine.mode());
    }

    @Test
    void referencedFileThatDisappearedFailsTheEngine() throws Exception {
        TableSource table = DiskTables.write(dir, 1, List.of(put("alpha", 1, "A")), stats);
        Engine engine = engineOver(List.of(table));

        Files.delete(DiskTables.fileOf(dir, table));

        StorageException e = assertThrows(StorageException.class,
                () -> engine.get("alpha", ctx()));
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(EngineMode.FAILED, engine.mode());
    }

    // ---------- flush and compaction with real files ----------

    @Test
    void flushToFileThenCompactionKeepEveryAnswer() throws Exception {
        Engine engine = engineOver(List.of());

        // Generation 1 → table 1 on disk.
        engine.put("alpha", bytes("A1"), ctx());
        engine.put("beta", bytes("B"), ctx());
        engine.put("empty", new byte[0], ctx());
        Map<String, String> expected = answers(engine);
        TableSource first = flushToDisk(engine, 1);
        assertEquals(expected, answers(engine), "after flush 1");

        // Generation 2, with newer versions and tombstones → table 2 on disk.
        engine.put("alpha", bytes("A2"), ctx());
        engine.delete("beta", ctx());
        engine.delete("ghost", ctx());
        engine.put("delta", bytes("D"), ctx());
        expected = answers(engine);
        TableSource second = flushToDisk(engine, 2);
        assertEquals(expected, answers(engine), "after flush 2");

        // Reads now come from the files.
        stats.reset();
        assertEquals("A2", new String(engine.get("alpha", ctx()).value(), StandardCharsets.UTF_8));
        assertTrue(stats.snapshot().get("sstableDataBlocksRead") >= 1);

        // Writes continue in memory meanwhile.
        engine.put("omega", bytes("O"), ctx());
        expected = answers(engine);

        // Major compaction of both files into table 3, then retire the inputs.
        TableSource compacted = compactToDisk(engine, List.of(first, second), 3);
        assertEquals(expected, answers(engine), "after compaction");
        assertEquals(List.of(3L), engine.currentView().tableIds());

        // After publication no reader uses the inputs: deleting them is safe.
        assertTrue(Files.deleteIfExists(DiskTables.fileOf(dir, first)));
        assertTrue(Files.deleteIfExists(DiskTables.fileOf(dir, second)));
        assertEquals(expected, answers(engine), "after deleting the inputs");

        // Tombstones survive compaction on disk too (I8).
        assertTrue(compacted.get("beta").orElseThrow().isTombstone());
        assertTrue(compacted.get("ghost").orElseThrow().isTombstone());
        assertEquals(EngineMode.SERVING, engine.mode());
    }

    /** Flush as the maintenance worker will do it, minus finalize and manifest (CP1 §19.1). */
    private TableSource flushToDisk(Engine engine, long tableId) throws Exception {
        long target = engine.maxSequenceInEngine(ctx());
        assertTrue(engine.rotateIfActiveContainsUpTo(target, ctx()));
        FlushInput input = engine.oldestImmutable().orElseThrow();

        // Built outside the engine lock: writers may continue meanwhile.
        TableSource table = DiskTables.write(dir, tableId, input.records(),
                input.entryCount(), stats);

        engine.underEngineWriteLock(ctx(), () -> {
            engine.installFlushResult(input.memtableGeneration(), table);
            return null;
        });
        return table;
    }

    /** Major compaction over files, minus finalize and manifest (CP1 §20.3). */
    private TableSource compactToDisk(Engine engine, List<TableSource> inputs, long outputId)
            throws Exception {
        List<Iterator<InternalRecord>> sources = new ArrayList<>();
        for (TableSource input : inputs) {
            sources.add(input.iterator(input.meta().minKey()));
        }
        List<InternalRecord> merged = new ArrayList<>();
        new MergingIterator(sources).forEachRemaining(merged::add);
        TableSource output = DiskTables.write(dir, outputId, merged, stats);

        Set<Long> inputIds = Set.copyOf(inputs.stream().map(TableSource::tableId).toList());
        engine.underEngineWriteLock(ctx(), () -> {
            engine.installCompactionResult(inputIds, output);
            return null;
        });
        return output;
    }
}
