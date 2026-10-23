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
import rs.raf.dlsm.engine.InMemoryWal;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.RecoveredState;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.stats.StorageStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceWorkerTest {

    @TempDir
    Path root;

    private FailpointRegistry failpoints;
    private StorageStats stats;
    private ManifestManager manifests;
    private MaintenanceProgress progress;
    private ByteArrayOutputStream logOutput;
    private Engine engine;
    private MaintenanceWorker worker;

    private static OpContext ctx() {
        return OpContext.withTimeout("worker-test", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() throws IOException {
        DataLayout layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        failpoints = new FailpointRegistry(true);
        DefaultStorageIO io = new DefaultStorageIO(failpoints);
        stats = new StorageStats();
        manifests = new ManifestManager(layout, io);
        manifests.publishInitialManifest(ctx());
        TableIdAllocator tableIds = TableIdAllocator.fromDisk(manifests.current(), layout.sstDir(), layout.tmpDir());
        progress = new MaintenanceProgress();
        logOutput = new ByteArrayOutputStream();
        JsonLog log = new JsonLog("test", new PrintStream(logOutput, true, StandardCharsets.UTF_8), Clock.systemUTC());

        InMemoryWal wal = new InMemoryWal();
        engine = Engine.open(new EngineConfig(EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES, failpoints),
                new RecoveredState(0, null, List.of(), wal.createSegment(ctx()), wal),
                host -> worker = new MaintenanceWorker(host, manifests,
                        new FlushJob(layout, io, failpoints, stats, manifests, tableIds, host, progress),
                        new CompactionJob(layout, io, failpoints, stats, manifests, tableIds, host, progress), log));
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private void arm(String name, Action action) {
        failpoints.addRule(new FailpointRegistry.Rule("tok-" + name, name, action, 0, 1, 10_000, Map.of()));
    }

    private void put(String key, String value) throws StorageException {
        engine.put(key, bytes(value), ctx());
    }

    private String value(String key) throws StorageException {
        return new String(engine.get(key, ctx()).value(), StandardCharsets.UTF_8);
    }

    private void awaitTriggered(String token) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (failpoints.stateOf(token) != FailpointRegistry.State.TRIGGERED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(FailpointRegistry.State.TRIGGERED, failpoints.stateOf(token));
    }

    @Test
    void manualFlushOfEmptyEngineIsNoOp() throws StorageException {
        ManualFlushResult result = worker.flush(ctx());

        assertEquals(new ManualFlushResult(ManualFlushResult.Result.NO_OP, 0, List.of(), 0, 0), result);
        assertEquals(0, stats.flushAttempts.get());
        assertEquals(0, stats.flushPublications.get());
    }

    @Test
    void manualFlushRotatesActiveAndPublishesIt() throws StorageException {
        put("alpha", "A");
        put("beta", "B");
        engine.delete("beta", ctx());

        ManualFlushResult result = worker.flush(ctx());

        assertEquals(new ManualFlushResult(ManualFlushResult.Result.PUBLISHED, 3, List.of(1L), 1, 3), result);
        assertEquals(Manifest.initial().generation() + 1, manifests.current().generation());
        assertTrue(engine.oldestImmutable().isEmpty());
        assertEquals(List.of(1L), engine.currentView().tableIds());
        assertEquals("A", value("alpha"));
        assertFalse(engine.get("beta", ctx()).isFound());
        assertEquals(1, stats.flushAttempts.get());
        assertEquals(1, stats.flushPublications.get());
    }

    @Test
    void repeatedManualFlushIsNoOpWithUnchangedGeneration() throws StorageException {
        put("alpha", "A");
        worker.flush(ctx());

        ManualFlushResult again = worker.flush(ctx());

        assertEquals(new ManualFlushResult(ManualFlushResult.Result.NO_OP, 1, List.of(), 1, 1), again);
        assertEquals(1, stats.flushAttempts.get());
    }

    @Test
    void manualFlushAfterQueuedAutoFlushPublishesOnlyWhatIsLeft() throws StorageException {
        put("alpha", "A");
        engine.rotateIfActiveContainsUpTo(1, ctx());
        put("beta", "B");

        ManualFlushResult result = worker.flush(ctx());

        assertEquals(ManualFlushResult.Result.PUBLISHED, result.result());
        assertEquals(2, result.targetSequence());
        assertEquals(List.of(2L), result.publishedTableIds());
        assertEquals(2, result.manifestGeneration());
        assertEquals(2, result.flushedThroughSequence());
        assertEquals(List.of(1L, 2L), engine.currentView().tableIds());
        assertEquals("A", value("alpha"));
        assertEquals("B", value("beta"));
    }

    @Test
    void manualFlushFlushesFrozenGenerationFirstThenActive() throws Exception {
        arm(FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST, Action.PAUSE);
        put("alpha", "A");
        engine.rotateIfActiveContainsUpTo(1, ctx());
        awaitTriggered("tok-" + FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST);
        put("beta", "B");

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ManualFlushResult> manual = caller.submit(() -> worker.flush(ctx()));
            failpoints.releaseRule("tok-" + FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST);

            ManualFlushResult result = manual.get();
            assertEquals(List.of(2L), result.publishedTableIds());
            assertEquals(2, result.flushedThroughSequence());
        } finally {
            caller.shutdownNow();
        }
        assertEquals(2, stats.flushPublications.get());
    }

    @Test
    void autoFlushPublishesFrozenGeneration() throws StorageException {
        put("alpha", "A");
        engine.rotateIfActiveContainsUpTo(1, ctx());

        worker.awaitIdle(ctx());

        assertTrue(engine.oldestImmutable().isEmpty());
        assertEquals(1, manifests.current().generation());
        assertEquals(1, manifests.current().flushedThroughSequence());
        assertEquals("A", value("alpha"));
        assertTrue(worker.lastError().isEmpty());
    }

    @Test
    void autoFlushFailureIsRecordedAndLogged() throws StorageException {
        arm(FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC, Action.IO_ERROR);
        put("alpha", "A");
        engine.rotateIfActiveContainsUpTo(1, ctx());

        worker.awaitIdle(ctx());

        assertTrue(worker.lastError().isPresent());
        assertEquals(ErrorCode.STORAGE_IO, worker.lastError().get().code());
        String logged = logOutput.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("MAINTENANCE_FAILED"), logged);
        assertTrue(logged.contains("FLUSH"), logged);
        assertEquals(Lifecycle.READY, engine.lifecycle());
        assertTrue(engine.oldestImmutable().isPresent());

        ManualFlushResult retry = worker.flush(ctx());
        assertEquals(List.of(2L), retry.publishedTableIds());
        assertEquals("A", value("alpha"));
    }

    @Test
    void manualFlushFailureIsReturnedAndQueuedAutoFlushCompletesIt() throws StorageException {
        put("alpha", "A");
        arm(FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME, Action.IO_ERROR);

        StorageException e = assertThrows(StorageException.class, () -> worker.flush(ctx()));

        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertSame(e, worker.lastError().orElseThrow());
        assertEquals(Lifecycle.READY, engine.lifecycle());

        worker.awaitIdle(ctx());
        assertEquals(1, manifests.current().generation());
        assertTrue(engine.oldestImmutable().isEmpty());
        assertEquals("A", value("alpha"));

        ManualFlushResult retry = worker.flush(ctx());
        assertEquals(new ManualFlushResult(ManualFlushResult.Result.NO_OP, 1, List.of(), 1, 1), retry);
        assertEquals(2, stats.flushAttempts.get());
        assertEquals(1, stats.flushPublications.get());
    }

    @Test
    void manualFlushAfterFatalErrorIsNotReady() throws StorageException {
        put("alpha", "A");
        arm(FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, Action.IO_ERROR);
        assertThrows(StorageException.class, () -> worker.flush(ctx()));
        assertEquals(Lifecycle.FAILED, engine.lifecycle());

        StorageException e = assertThrows(StorageException.class, () -> worker.flush(ctx()));

        assertEquals(ErrorCode.NODE_NOT_READY, e.code());
    }

    @Test
    void deadlineExpiresButAcceptedFlushStillFinishes() throws Exception {
        arm(FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST, Action.PAUSE);
        put("alpha", "A");

        StorageException e = assertThrows(StorageException.class,
                () -> worker.flush(OpContext.withTimeout("short", 200)));
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.code());

        awaitTriggered("tok-" + FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST);
        failpoints.releaseRule("tok-" + FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST);
        worker.awaitIdle(ctx());

        assertEquals(1, manifests.current().generation());
        assertEquals(1, manifests.current().flushedThroughSequence());
    }

    @Test
    void concurrentManualFlushesAreSerialized() throws Exception {
        put("alpha", "A");
        put("beta", "B");
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Callable<ManualFlushResult> call = () -> worker.flush(ctx());
            Future<ManualFlushResult> first = callers.submit(call);
            Future<ManualFlushResult> second = callers.submit(call);

            List<ManualFlushResult> results = List.of(first.get(), second.get());

            assertEquals(1, results.stream().filter(r -> r.result() == ManualFlushResult.Result.PUBLISHED).count());
            assertTrue(results.stream().allMatch(r -> r.flushedThroughSequence() == 2));
        } finally {
            callers.shutdownNow();
        }
        assertEquals(1, manifests.current().generation());
        assertEquals(1, stats.flushPublications.get());
    }

    @Test
    void flushAfterCloseIsNotReady() {
        worker.close();

        StorageException e = assertThrows(StorageException.class, () -> worker.flush(ctx()));

        assertEquals(ErrorCode.NODE_NOT_READY, e.code());
    }
}
