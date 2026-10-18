package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine reading published SSTables from real files through the disk
 * reader (CP1 §11, §12, §17): the engine side of plan item "sstable-reads".
 */
class EngineDiskTablesTest {

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

    /** An engine opened over published tables, as after recovery; no maintenance worker. */
    private static Engine engineOver(List<TableSource> tables) {
        long lastUsed = new EngineView(null, tables).maxSequence();
        InMemoryWal wal = new InMemoryWal();
        return Engine.open(EngineConfig.defaults(),
                new RecoveredState(lastUsed, null, tables, wal.createSegment(ctx()), wal),
                host -> FlushScheduler.NONE);
    }

    private static String get(Engine engine, String key) throws StorageException {
        GetResult r = engine.get(key, ctx());
        return r.isFound() ? new String(r.value(), StandardCharsets.UTF_8) : null;
    }

    private static List<String> scan(Engine engine, String start, String end, int limit)
            throws StorageException {
        List<String> out = new ArrayList<>();
        for (InternalRecord r : engine.scan(start, end, limit, ctx()).items()) {
            out.add(r.key() + "=" + new String(r.value(), StandardCharsets.UTF_8));
        }
        return out;
    }

    private long counter(String name) {
        return stats.snapshot().get(name);
    }

    @Test
    void readsResolveVersionsAcrossFilesAndMemory() throws Exception {
        // Table 1 is older, table 2 newer; ids say nothing about freshness.
        TableSource older = DiskTables.write(dir, 2, List.of(
                put("alpha", 3, "A1"), put("beta", 4, "B"), put("gamma", 6, "G")), stats);
        TableSource newer = DiskTables.write(dir, 1, List.of(
                put("alpha", 8, "A2"), InternalRecord.delete("gamma", 9)), stats);
        Engine engine = engineOver(List.of(older, newer));

        engine.put("delta", bytes("D"), ctx());      // sequence 10, in memory
        engine.delete("beta", ctx());                // sequence 11 hides table 2's beta

        assertEquals("A2", get(engine, "alpha"));
        assertNull(get(engine, "beta"));
        assertNull(get(engine, "gamma"), "tombstone on disk hides the older value");
        assertEquals("D", get(engine, "delta"));
        assertEquals(List.of("alpha=A2", "delta=D"), scan(engine, "a", "z", 100));
    }

    @Test
    void emptyValueAndTombstoneStayDistinctOnDisk() throws Exception {
        Engine engine = engineOver(List.of(DiskTables.write(dir, 1, List.of(
                put("empty", 1, ""), InternalRecord.delete("gone", 2)), stats)));

        GetResult empty = engine.get("empty", ctx());
        assertTrue(empty.isFound());
        assertEquals(0, empty.value().length);
        assertFalse(engine.get("gone", ctx()).isFound());
    }

    @Test
    void missingKeyExampleOfCp1Section11() throws Exception {
        // A [alpha, delta], B [epsilon, omega], C [beta, zulu]; GET(kappa).
        TableSource a = DiskTables.write(dir, 1, List.of(
                put("alpha", 1, "x"), put("delta", 2, "x")), stats);
        TableSource b = DiskTables.write(dir, 2, List.of(
                put("epsilon", 3, "x"), put("omega", 4, "x")), stats);
        TableSource c = DiskTables.write(dir, 3, List.of(
                put("beta", 5, "x"), put("zulu", 6, "x")), stats);
        Engine engine = engineOver(List.of(a, b, c));
        stats.reset();

        assertNull(get(engine, "kappa"));

        Map<String, Long> s = stats.snapshot();
        // A is ruled out by its key range: it is not even consulted.
        // B and C are consulted: each Bloom answer is counted once.
        assertEquals(2, s.get("bloomNegativeSkips") + s.get("bloomPositiveProbes"));
        // Only a "maybe present" answer leads to reading one candidate block.
        assertEquals(s.get("bloomPositiveProbes"), s.get("sstableDataBlocksRead"));
        assertEquals(0, s.get("rangeScanBlocksRead"));
    }

    @Test
    void pointLookupReadsAtMostOneBlockOfALargeTable() throws Exception {
        List<InternalRecord> records = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            records.add(put("k%05d".formatted(i), i + 1, "value-" + i));
        }
        Engine engine = engineOver(List.of(DiskTables.write(dir, 1, records, stats)));
        stats.reset();

        assertEquals("value-4321", get(engine, "k04321"));
        assertEquals(1, counter("sstableDataBlocksRead"));
        assertEquals(0, counter("rangeScanBlocksRead"));
    }

    @Test
    void boundedScanNearTheEndReadsOnlyTheLastBlocks() throws Exception {
        // SST-004 at engine level: 20 000 keys spread over many 4 KiB blocks.
        List<InternalRecord> records = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            records.add(put("k%05d".formatted(i), i + 1, "value-" + i));
        }
        Engine engine = engineOver(List.of(DiskTables.write(dir, 1, records, stats)));
        stats.reset();

        // Reading every block would cost this many; the scan must cost almost none.
        assertTrue(engine.scan("k00000", "k99999", 1000, ctx()).items().size() == 1000);
        long blocksForThousand = counter("rangeScanBlocksRead");
        assertTrue(blocksForThousand >= 5, "fixture spans many blocks: " + blocksForThousand);
        stats.reset();

        List<String> result = scan(engine, "k19990", "k19995", 100);

        assertEquals(List.of("k19990=value-19990", "k19991=value-19991",
                "k19992=value-19992", "k19993=value-19993", "k19994=value-19994"), result);
        // Positioned through the sparse index: never from the first block.
        long blocks = counter("rangeScanBlocksRead");
        assertTrue(blocks >= 1 && blocks <= 2, "read " + blocks + " blocks");
        assertEquals(blocks, counter("sstableDataBlocksRead"));
        assertEquals(0, counter("bloomPositiveProbes") + counter("bloomNegativeSkips"),
                "range scans never consult Bloom filters");
    }

    @Test
    void scanLimitStopsReadingBlocks() throws Exception {
        List<InternalRecord> records = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            records.add(put("k%05d".formatted(i), i + 1, "v"));
        }
        Engine engine = engineOver(List.of(DiskTables.write(dir, 1, records, stats)));
        stats.reset();

        assertEquals(3, scan(engine, "k00000", "k99999", 3).size());
        assertEquals(1, counter("rangeScanBlocksRead"), "the first block holds all three");
    }
}
