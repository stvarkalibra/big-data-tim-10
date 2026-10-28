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
import rs.raf.dlsm.engine.FlushInput;
import rs.raf.dlsm.engine.FlushScheduler;
import rs.raf.dlsm.engine.InMemoryWal;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.RecoveredState;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.wal.RecordingStorageIO;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlushJobTest {

    private static final long ACTIVE_WAL_MARKER = 999;

    @TempDir
    Path root;

    private DataLayout layout;
    private FailpointRegistry failpoints;
    private RecordingStorageIO io;
    private StorageStats stats;
    private ManifestManager manifests;
    private TableIdAllocator tableIds;
    private MaintenanceProgress progress;
    private Engine engine;
    private FlushJob job;

    private static OpContext ctx() {
        return OpContext.withTimeout("flush-test", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() throws IOException {
        layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        failpoints = new FailpointRegistry(true);
        io = new RecordingStorageIO(failpoints);
        stats = new StorageStats();
        manifests = new ManifestManager(layout, io);
        manifests.publishInitialManifest(ctx());
        tableIds = TableIdAllocator.fromDisk(manifests.current(), layout.sstDir(), layout.tmpDir());
        progress = new MaintenanceProgress();

        InMemoryWal wal = new InMemoryWal();
        engine = Engine.open(new EngineConfig(EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES, failpoints),
                new RecoveredState(0, null, List.of(), wal.createSegment(ctx()), wal),
                host -> FlushScheduler.NONE);
        job = new FlushJob(layout, io, failpoints, stats, manifests, tableIds, engine, progress);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private void arm(String name, Action action) {
        failpoints.addRule(new FailpointRegistry.Rule("tok-" + name, name, action, 0, 1, 0, Map.of()));
    }

    private FlushInput freeze(String... keyValues) throws StorageException, IOException {
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] == null) {
                engine.delete(keyValues[i], ctx());
            } else {
                engine.put(keyValues[i], bytes(keyValues[i + 1]), ctx());
            }
        }
        assertTrue(engine.rotateIfActiveContainsUpTo(engine.maxSequenceInEngine(ctx()), ctx()));
        FlushInput input = engine.oldestImmutable().orElseThrow();
        for (long id : input.walSegmentIds()) {
            Files.write(layout.walDir().resolve(WalFileNames.segmentFileName(id)), new byte[1]);
        }
        Files.write(layout.walDir().resolve(WalFileNames.segmentFileName(ACTIVE_WAL_MARKER)), new byte[1]);
        return input;
    }

    private Optional<String> value(String key) throws StorageException {
        var result = engine.get(key, ctx());
        return result.isFound() ? Optional.of(new String(result.value(), StandardCharsets.UTF_8)) : Optional.empty();
    }

    private List<String> files(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private Manifest diskManifest() throws Exception {
        return new ManifestManager(layout, new RecordingStorageIO(failpoints)).loadAndValidate();
    }

    @Test
    void noFrozenGenerationIsNoOp() throws Exception {
        assertEquals(Optional.empty(), job.flushOldest(null, ctx()));

        assertEquals(0, stats.flushAttempts.get());
        assertEquals(Manifest.initial(), diskManifest());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    @Test
    void flushPublishesTableAndRetiresFrozenGeneration() throws Exception {
        FlushInput input = freeze("alpha", "A", "beta", "B", "gone", "X", "gone", null);

        FlushJob.Outcome outcome = job.flushOldest(null, ctx()).orElseThrow();

        assertEquals(new FlushJob.Outcome(1, 1, 4), outcome);
        Manifest manifest = diskManifest();
        assertEquals(manifest, manifests.current());
        assertEquals(1, manifest.generation());
        assertEquals(2, manifest.nextTableId());
        assertEquals(4, manifest.flushedThroughSequence());
        assertEquals(1, manifest.sstables().size());
        SSTableMeta meta = manifest.sstables().get(0);
        assertEquals(1, meta.tableId());
        assertEquals(3, meta.entryCount());
        assertEquals(1, meta.minSequence());
        assertEquals(4, meta.maxSequence());

        assertEquals(List.of(SSTableMeta.fileName(1)), files(layout.sstDir()));
        assertTrue(files(layout.tmpDir()).isEmpty());
        assertTrue(engine.oldestImmutable().isEmpty());
        assertEquals(List.of(1L), engine.currentView().tableIds());

        assertEquals(Optional.of("A"), value("alpha"));
        assertEquals(Optional.of("B"), value("beta"));
        assertEquals(Optional.empty(), value("gone"));

        assertFalse(input.walSegmentIds().isEmpty());
        assertEquals(List.of(WalFileNames.segmentFileName(ACTIVE_WAL_MARKER)), files(layout.walDir()));

        assertEquals(1, stats.flushAttempts.get());
        assertEquals(1, stats.flushPublications.get());
        assertEquals(0, stats.temporaryFilesRemoved.get());
        assertEquals("IDLE", progress.snapshot().phase());
        assertEquals("NONE", progress.snapshot().operation());
    }

    @Test
    void secondFlushRebasesOnFirstAndKeepsBothTables() throws Exception {
        freeze("alpha", "A1");
        job.flushOldest(null, ctx());
        freeze("alpha", "A2", "beta", "B");

        FlushJob.Outcome outcome = job.flushOldest(null, ctx()).orElseThrow();

        assertEquals(new FlushJob.Outcome(2, 2, 3), outcome);
        Manifest manifest = diskManifest();
        assertEquals(List.of(1L, 2L), manifest.sstables().stream().map(SSTableMeta::tableId).toList());
        assertEquals(3, manifest.nextTableId());
        assertEquals(Optional.of("A2"), value("alpha"));
        assertEquals(Optional.of("B"), value("beta"));
    }

    @Test
    void progressReportsTargetDuringFlush() throws Exception {
        freeze("alpha", "A");
        List<String> seen = new ArrayList<>();
        failpoints.addRule(new FailpointRegistry.Rule("tok-pause",
                FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST, Action.PAUSE, 0, 1, 10_000, Map.of()));

        Thread flusher = new Thread(() -> {
            try {
                job.flushOldest(42L, ctx());
            } catch (StorageException e) {
                seen.add("error " + e.getMessage());
            }
        });
        flusher.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (failpoints.stateOf("tok-pause") != FailpointRegistry.State.TRIGGERED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(FailpointRegistry.State.TRIGGERED, failpoints.stateOf("tok-pause"));

        var snapshot = progress.snapshot();
        assertEquals("FLUSH", snapshot.operation());
        assertEquals("FINALIZE_OUTPUT", snapshot.phase());
        assertEquals(42L, snapshot.targetSequence());
        assertEquals(List.of(), snapshot.inputTableIds());

        failpoints.releaseRule("tok-pause");
        flusher.join(5000);
        assertFalse(flusher.isAlive());
        assertTrue(seen.isEmpty(), seen.toString());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    @Test
    void ioErrorBeforeSstableSyncFailsJobAndRemovesTemp() throws Exception {
        freeze("alpha", "A");
        arm(FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.flushOldest(null, ctx()));

        assertKeptOldState();
        assertEquals(1, stats.temporaryFilesRemoved.get());
        assertEquals(0, stats.orphanFilesRemoved.get());
        assertRetrySucceeds();
    }

    @Test
    void ioErrorAfterSstableRenameRemovesUnpublishedFinalFile() throws Exception {
        freeze("alpha", "A");
        arm(FailpointNames.SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.flushOldest(null, ctx()));

        assertKeptOldState();
        assertEquals(0, stats.temporaryFilesRemoved.get());
        assertEquals(1, stats.orphanFilesRemoved.get());
        assertRetrySucceeds();
    }

    @Test
    void ioErrorAfterFinalizeBeforeManifestKeepsWalAuthoritative() throws Exception {
        freeze("alpha", "A");
        arm(FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.flushOldest(null, ctx()));

        assertKeptOldState();
        assertEquals(1, stats.orphanFilesRemoved.get());
        assertRetrySucceeds();
    }

    @Test
    void ioErrorBeforeManifestRenameFailsJobOnly() throws Exception {
        freeze("alpha", "A");
        arm(FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.flushOldest(null, ctx()));

        assertKeptOldState();
        assertRetrySucceeds();
    }

    @Test
    void ioErrorAfterManifestRenameFailsEngineAndKeepsFiles() throws Exception {
        FlushInput input = freeze("alpha", "A");
        arm(FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.flushOldest(null, ctx()));

        assertEquals(Lifecycle.FAILED, engine.lifecycle());
        assertTrue(engine.lastFatalError().isPresent());
        assertEquals(List.of(SSTableMeta.fileName(1)), files(layout.sstDir()));
        assertEquals(1, diskManifest().generation());
        for (long id : input.walSegmentIds()) {
            assertTrue(Files.exists(layout.walDir().resolve(WalFileNames.segmentFileName(id))));
        }
        assertEquals(0, stats.flushPublications.get());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    @Test
    void ioErrorBeforeWalDeleteKeepsPublishedStateAndServing() throws Exception {
        FlushInput input = freeze("alpha", "A");
        arm(FailpointNames.FLUSH_AFTER_MANIFEST_PUBLISH_BEFORE_WAL_DELETE, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.flushOldest(null, ctx()));

        assertEquals(Lifecycle.READY, engine.lifecycle());
        assertEquals(1, diskManifest().generation());
        assertTrue(engine.oldestImmutable().isEmpty());
        assertEquals(Optional.of("A"), value("alpha"));
        assertEquals(1, stats.flushPublications.get());
        for (long id : input.walSegmentIds()) {
            assertTrue(Files.exists(layout.walDir().resolve(WalFileNames.segmentFileName(id))));
        }
    }

    // ===== WAL retirement through the WAL module (CP1 §19.1 steps 12–13) =====

    @Test
    void retirerGetsExactlyTheFlushedGenerationsSegments() throws Exception {
        List<List<Long>> retired = new ArrayList<>();
        job = jobWith((ids, ctx) -> retired.add(List.copyOf(ids)));
        FlushInput input = freeze("alpha", "A");

        job.flushOldest(null, ctx()).orElseThrow();

        assertEquals(List.of(input.walSegmentIds()), retired);
    }

    // CP1-FLS-004: the failpoint fires before any WAL segment is touched.
    @Test
    void failpointBeforeWalDeleteFiresBeforeTheRetirer() throws Exception {
        List<List<Long>> retired = new ArrayList<>();
        job = jobWith((ids, ctx) -> retired.add(List.copyOf(ids)));
        freeze("alpha", "A");
        arm(FailpointNames.FLUSH_AFTER_MANIFEST_PUBLISH_BEFORE_WAL_DELETE, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.flushOldest(null, ctx()));

        assertEquals(List.of(), retired);
    }

    // docs/ODLUKE.md, 23.10.2026: an error after the published flush fails the job only.
    @Test
    void retirerFailureFailsJobButEngineKeepsServing() throws Exception {
        job = jobWith((ids, ctx) -> {
            throw new IOException("delete failed");
        });
        freeze("alpha", "A");

        StorageException error = assertThrows(StorageException.class,
                () -> job.flushOldest(null, ctx()));

        assertEquals(ErrorCode.STORAGE_IO, error.code());
        assertEquals(Lifecycle.READY, engine.lifecycle());
        assertEquals(1, diskManifest().generation());
        assertEquals(1, stats.flushPublications.get());
        assertEquals(Optional.of("A"), value("alpha"));
    }

    private FlushJob jobWith(WalRetirer wal) {
        return new FlushJob(layout, io, failpoints, stats, manifests, tableIds, engine, progress, wal);
    }

    private void assertKeptOldState() throws Exception {
        assertEquals(Lifecycle.READY, engine.lifecycle());
        assertEquals(Manifest.initial(), diskManifest());
        assertEquals(Manifest.initial(), manifests.current());
        assertTrue(engine.oldestImmutable().isPresent());
        assertTrue(files(layout.sstDir()).isEmpty());
        assertTrue(files(layout.tmpDir()).isEmpty());
        assertEquals(Optional.of("A"), value("alpha"));
        assertEquals(1, stats.flushAttempts.get());
        assertEquals(0, stats.flushPublications.get());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    private void assertRetrySucceeds() throws Exception {
        FlushJob.Outcome outcome = job.flushOldest(null, ctx()).orElseThrow();

        assertEquals(2, outcome.tableId());
        assertEquals(1, outcome.manifestGeneration());
        assertEquals(List.of(SSTableMeta.fileName(2)), files(layout.sstDir()));
        assertEquals(3, diskManifest().nextTableId());
        assertEquals(Optional.of("A"), value("alpha"));
        assertEquals(2, stats.flushAttempts.get());
        assertEquals(1, stats.flushPublications.get());
    }
}
