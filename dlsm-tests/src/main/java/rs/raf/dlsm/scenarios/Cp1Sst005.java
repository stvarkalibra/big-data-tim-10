
package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.*;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.sstable.*;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class Cp1Sst005 {

    public static final String ID = "CP1-SST-005";

    private Cp1Sst005() {
    }

    public static void run(long seed, Report report)
            throws Exception {

        Path dir = Files.createTempDirectory(
                "cp1-sst-005-" + seed + "-"
        );

        Engine engine = null;

        try {
            StorageStats stats = new StorageStats();

            // Older table has a larger ID deliberately.
            TableSource older = writeTable(
                    dir, 20, stats, List.of(
                            put("alpha", 1, "A1"),
                            put("beta", 2, "B1"),
                            put("epsilon", 3, "E1")
                    )
            );

            // Newer versions are in the smaller table ID.
            TableSource newer = writeTable(
                    dir, 10, stats, List.of(
                            put("alpha", 4, "A2"),
                            put("delta", 6, "D2"),
                            put("gamma", 5, "G2")
                    )
            );

            InMemoryWal wal = new InMemoryWal();

            engine = Engine.open(
                    EngineConfig.defaults(),
                    new RecoveredState(
                            6,
                            null,
                            List.of(older, newer),
                            wal.createSegment(ctx()),
                            wal
                    ),
                    host -> FlushScheduler.NONE
            );

            // These two mutations will become immutable.
            long betaSeq =
                    engine.delete("beta", ctx()).sequence();

            long deltaSeq =
                    engine.put("delta", bytes("D3"), ctx())
                            .sequence();

            check(betaSeq == 7, "Incorrect beta sequence");
            check(deltaSeq == 8, "Incorrect delta sequence");

            // Rotate without running a background flush worker.
            check(
                    engine.rotateIfActiveContainsUpTo(
                            deltaSeq, ctx()
                    ),
                    "Expected MemTable rotation"
            );

            var frozen = engine.currentView().immutable();

            check(frozen != null, "Missing immutable MemTable");
            check(frozen.entryCount() == 2,
                    "Expected two frozen entries");

            // New active MemTable.
            engine.put("alpha", bytes("A4"), ctx());
            engine.delete("gamma", ctx());
            engine.put("zeta", bytes("Z4"), ctx());

            check(
                    engine.currentView().tables().size() == 2,
                    "Expected two published SSTables"
            );

            // Stage 1: active + immutable + two SSTables.
            verifyLogicalState(engine, "before flush");

            // Simulate the already-published flush result.
            List<InternalRecord> frozenRecords =
                    collect(frozen.iterator());

            TableSource flushed = writeTable(
                    dir, 30, stats, frozenRecords
            );

            Engine current = engine;

            engine.underEngineWriteLock(
                    ctx(),
                    () -> {
                        current.installFlushResult(
                                frozen.generation(), flushed
                        );
                        return null;
                    }
            );

            check(
                    engine.currentView().immutable() == null,
                    "Immutable MemTable not released"
            );

            check(
                    engine.currentView().tables().size() == 3,
                    "Expected three SSTables after flush"
            );

            // Stage 2: the same logical results after flush.
            verifyLogicalState(engine, "after flush");

            // Known winner for each key among the three
            // physical SSTable sources.
            List<InternalRecord> compactedRecords = List.of(
                    put("alpha", 4, "A2"),
                    InternalRecord.delete("beta", betaSeq),
                    put("delta", deltaSeq, "D3"),
                    put("epsilon", 3, "E1"),
                    put("gamma", 5, "G2")
            );

            TableSource compacted = writeTable(
                    dir, 40, stats, compactedRecords
            );

            engine.underEngineWriteLock(
                    ctx(),
                    () -> {
                        current.installCompactionResult(
                                Set.of(10L, 20L, 30L),
                                compacted
                        );
                        return null;
                    }
            );

            check(
                    engine.currentView().tables().size() == 1,
                    "Expected one SSTable after compaction"
            );

            // Stage 3: same logical results after compaction.
            verifyLogicalState(engine, "after compaction");

            report.pass();

        } finally {
            if (engine != null) {
                engine.close();
            }

            try (var paths = Files.walk(dir)) {
                for (Path path : paths
                        .sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void verifyLogicalState(
            Engine engine,
            String stage) throws Exception {

        expectValue(engine, "alpha", "A4", stage);
        expectMissing(engine, "beta", stage);
        expectValue(engine, "delta", "D3", stage);
        expectValue(engine, "epsilon", "E1", stage);
        expectMissing(engine, "gamma", stage);
        expectValue(engine, "zeta", "Z4", stage);

        List<InternalRecord> results = engine.scan(
                "alpha", "zzzz", 100, ctx()
        ).items();

        List<String> actual = new ArrayList<>();

        for (InternalRecord record : results) {
            actual.add(
                    record.key() + "="
                            + new String(
                            record.value(),
                            StandardCharsets.UTF_8
                    )
            );
        }

        List<String> expected = List.of(
                "alpha=A4",
                "delta=D3",
                "epsilon=E1",
                "zeta=Z4"
        );

        check(
                actual.equals(expected),
                stage + ": wrong SCAN: " + actual
        );
    }

    private static void expectValue(
            Engine engine,
            String key,
            String expected,
            String stage) throws Exception {

        var result = engine.get(key, ctx());

        check(
                result.isFound(),
                stage + ": missing key " + key
        );

        check(
                Arrays.equals(
                        result.value(),
                        bytes(expected)
                ),
                stage + ": wrong value for " + key
        );
    }

    private static void expectMissing(
            Engine engine,
            String key,
            String stage) throws Exception {

        check(
                !engine.get(key, ctx()).isFound(),
                stage + ": deleted key visible " + key
        );
    }

    private static TableSource writeTable(
            Path dir,
            long id,
            StorageStats stats,
            List<InternalRecord> records)
            throws Exception {

        List<BlockCodec.Record> encoded =
                new ArrayList<>();

        for (InternalRecord record : records) {
            encoded.add(new BlockCodec.Record(
                    record.key(),
                    record.sequence(),
                    record.kind().code(),
                    record.value()
            ));
        }

        SSTableWriter.Result result =
                SSTableWriter.build(
                        id, encoded.iterator(), encoded.size()
                );

        Path path = dir.resolve(
                result.meta().file()
        );

        Files.write(path, result.bytes());

        return DiskSSTableTableSource.open(
                path, result.meta(), stats
        );
    }

    private static List<InternalRecord> collect(
            Iterator<InternalRecord> iterator) {

        List<InternalRecord> result = new ArrayList<>();

        iterator.forEachRemaining(result::add);

        return result;
    }

    private static InternalRecord put(
            String key,
            long sequence,
            String value) {

        return InternalRecord.put(
                key, sequence, bytes(value)
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static OpContext ctx() {
        return OpContext.withTimeout(
                "cp1-sst-005", 10_000
        );
    }

    private static void check(
            boolean condition,
            String message) {

        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
