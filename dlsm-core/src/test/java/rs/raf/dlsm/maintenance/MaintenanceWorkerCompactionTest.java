package rs.raf.dlsm.maintenance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.FlushScheduler;
import rs.raf.dlsm.engine.InMemoryWal;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.RecoveredState;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.stats.StorageStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceWorkerCompactionTest {

    @TempDir
    Path root;

    private DataLayout layout;
    private DefaultStorageIO io;
    private FailpointRegistry failpoints;
    private StorageStats stats;
    private ManifestManager manifests;
    private TableIdAllocator tableIds;
    private MaintenanceProgress progress;
    private JsonLog log;
    private ByteArrayOutputStream logOutput;
    private Engine engine;
    private MaintenanceWorker worker;

    private static OpContext ctx() {
        return OpContext.withTimeout("worker-compaction-test", 5000);
    }

    @BeforeEach
    void setUp() throws IOException {
        layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        failpoints = new FailpointRegistry(true);
        io = new DefaultStorageIO(failpoints);
        stats = new StorageStats();
        manifests = new ManifestManager(layout, io);
        manifests.publishInitialManifest(ctx());
        tableIds = TableIdAllocator.fromDisk(manifests.current(), layout.sstDir(), layout.tmpDir());
        progress = new MaintenanceProgress();
        logOutput = new ByteArrayOutputStream();
        log = new JsonLog("test", new PrintStream(logOutput, true, StandardCharsets.UTF_8), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private MaintenanceWorker newWorker(rs.raf.dlsm.engine.MaintenanceHost host) {
        return new MaintenanceWorker(host, manifests,
                new FlushJob(layout, io, failpoints, stats, manifests, tableIds, host, progress),
                new CompactionJob(layout, io, failpoints, stats, manifests, tableIds, host, progress), log);
    }

    private void openWithWorker() {
        InMemoryWal wal = new InMemoryWal();
        engine = Engine.open(new EngineConfig(EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES, failpoints),
                new RecoveredState(0, null, List.of(), wal.createSegment(ctx()), wal),
                host -> worker = newWorker(host));
    }

    private void arm(String name, Action action) {
        failpoints.addRule(new FailpointRegistry.Rule("tok-" + name, name, action, 0, 1, 10_000, Map.of()));
    }

    private void flushTable(String key, String value) throws StorageException {
        engine.put(key, value.getBytes(StandardCharsets.UTF_8), ctx());
        assertEquals(ManualFlushResult.Result.PUBLISHED, worker.flush(ctx()).result());
    }

    private Map<String, String> answers(List<String> keys) throws StorageException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            var result = engine.get(key, ctx());
            out.put(key, result.isFound() ? new String(result.value(), StandardCharsets.UTF_8) : null);
        }
        return out;
    }

    private List<Long> publishedIds() {
        return manifests.current().sstables().stream().map(SSTableMeta::tableId).toList();
    }

    @Test
    void manualCompactionWithOneTableIsNoOp() throws StorageException {
        openWithWorker();
        flushTable("alpha", "A");

        CompactionJob.Outcome outcome = worker.compact(ctx());

        assertEquals(new CompactionJob.Outcome(List.of(1L), null, 1), outcome);
        assertEquals(0, stats.compactionAttempts.get());
    }

    @Test
    void manualCompactionMergesTwoTables() throws StorageException {
        openWithWorker();
        flushTable("alpha", "A1");
        flushTable("alpha", "A2");
        Map<String, String> before = answers(List.of("alpha"));

        CompactionJob.Outcome outcome = worker.compact(ctx());

        assertEquals(new CompactionJob.Outcome(List.of(1L, 2L), 3L, 3), outcome);
        assertEquals(List.of(3L), publishedIds());
        assertEquals(before, answers(List.of("alpha")));
        assertEquals("A2", answers(List.of("alpha")).get("alpha"));
    }

    @Test
    void threeTablesDoNotTriggerAutomaticCompaction() throws StorageException {
        openWithWorker();
        flushTable("alpha", "A");
        flushTable("beta", "B");
        flushTable("gamma", "G");

        worker.awaitIdle(ctx());

        assertEquals(List.of(1L, 2L, 3L), publishedIds());
        assertFalse(worker.autoCompactionPending());
        assertEquals(0, stats.compactionAttempts.get());
    }

    @Test
    void fourthPublicationTriggersAutomaticCompaction() throws StorageException {
        openWithWorker();
        List<String> keys = List.of("alpha", "beta", "gamma", "delta");
        for (String key : keys) {
            flushTable(key, key.toUpperCase());
        }

        worker.awaitIdle(ctx());

        assertEquals(List.of(5L), publishedIds());
        assertEquals(List.of(5L), engine.currentView().tableIds());
        assertEquals(Map.of("alpha", "ALPHA", "beta", "BETA", "gamma", "GAMMA", "delta", "DELTA"),
                Map.copyOf(answers(keys)));
        assertEquals(1, stats.compactionAttempts.get());
        assertEquals(1, stats.compactionPublications.get());
        assertFalse(worker.autoCompactionPending());
        assertEquals(List.of(SSTableMeta.fileName(5)), files(layout.sstDir()));
    }

    @Test
    void automaticCompactionFailureIsRecordedAndRetriedOnNextPublication() throws Exception {
        openWithWorker();
        arm(FailpointNames.COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT, Action.IO_ERROR);
        for (String key : List.of("alpha", "beta", "gamma", "delta")) {
            flushTable(key, key);
        }
        worker.awaitIdle(ctx());

        assertEquals(List.of(1L, 2L, 3L, 4L), publishedIds());
        assertEquals(ErrorCode.STORAGE_IO, worker.lastError().orElseThrow().code());
        String logged = logOutput.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("COMPACTION"), logged);
        assertFalse(worker.autoCompactionPending());
        assertEquals(Lifecycle.READY, engine.lifecycle());

        flushTable("omega", "omega");
        worker.awaitIdle(ctx());

        assertEquals(List.of(7L), publishedIds());
        assertEquals("omega", answers(List.of("omega")).get("omega"));
        assertEquals(2, stats.compactionAttempts.get());
        assertEquals(1, stats.compactionPublications.get());
    }

    @Test
    void checkAfterRecoveryCompactsExistingTables() throws StorageException {
        InMemoryWal wal = new InMemoryWal();
        engine = Engine.open(new EngineConfig(EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES, failpoints),
                new RecoveredState(0, null, List.of(), wal.createSegment(ctx()), wal),
                host -> FlushScheduler.NONE);
        FlushJob direct = new FlushJob(layout, io, failpoints, stats, manifests, tableIds, engine, progress);
        for (String key : List.of("alpha", "beta", "gamma", "delta")) {
            engine.put(key, key.getBytes(StandardCharsets.UTF_8), ctx());
            engine.rotateIfActiveContainsUpTo(engine.maxSequenceInEngine(ctx()), ctx());
            direct.flushOldest(null, ctx());
        }
        assertEquals(List.of(1L, 2L, 3L, 4L), publishedIds());
        worker = newWorker(engine);

        worker.checkAutoCompaction();
        worker.awaitIdle(ctx());

        assertEquals(List.of(5L), publishedIds());
        worker.close();
    }

    @Test
    void manualCompactionFailureIsReturnedAndRecorded() throws StorageException {
        openWithWorker();
        flushTable("alpha", "A");
        flushTable("beta", "B");
        arm(FailpointNames.COMPACTION_AFTER_OUTPUT_FINALIZE_BEFORE_MANIFEST, Action.IO_ERROR);

        StorageException e = assertThrows(StorageException.class, () -> worker.compact(ctx()));

        assertSame(e, worker.lastError().orElseThrow());
        assertEquals(List.of(1L, 2L), publishedIds());
        assertEquals(new CompactionJob.Outcome(List.of(1L, 2L), 4L, 3), worker.compact(ctx()));
    }

    @Test
    void compactionAfterCloseIsNotReady() {
        openWithWorker();
        worker.close();

        StorageException e = assertThrows(StorageException.class, () -> worker.compact(ctx()));

        assertEquals(ErrorCode.NODE_NOT_READY, e.code());
    }

    private List<String> files(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
