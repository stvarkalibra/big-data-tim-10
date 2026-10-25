package rs.raf.dlsm.maintenance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
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
import rs.raf.dlsm.wal.RecordingStorageIO;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompactionJobTest {

    private static final List<String> KEYS = List.of("alpha", "beta", "delta", "gamma", "omega");

    @TempDir
    Path root;

    private DataLayout layout;
    private FailpointRegistry failpoints;
    private StorageStats stats;
    private ManifestManager manifests;
    private MaintenanceProgress progress;
    private Engine engine;
    private FlushJob flushJob;
    private CompactionJob job;

    private static OpContext ctx() {
        return OpContext.withTimeout("compaction-test", 5000);
    }

    @BeforeEach
    void setUp() throws IOException {
        layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        failpoints = new FailpointRegistry(true);
        RecordingStorageIO io = new RecordingStorageIO(failpoints);
        stats = new StorageStats();
        manifests = new ManifestManager(layout, io);
        manifests.publishInitialManifest(ctx());
        TableIdAllocator tableIds = TableIdAllocator.fromDisk(manifests.current(), layout.sstDir(), layout.tmpDir());
        progress = new MaintenanceProgress();

        InMemoryWal wal = new InMemoryWal();
        engine = Engine.open(new EngineConfig(EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES, failpoints),
                new RecoveredState(0, null, List.of(), wal.createSegment(ctx()), wal),
                host -> FlushScheduler.NONE);
        flushJob = new FlushJob(layout, io, failpoints, stats, manifests, tableIds, engine, progress);
        job = new CompactionJob(layout, io, failpoints, stats, manifests, tableIds, engine, progress);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private void arm(String name, Action action) {
        failpoints.addRule(new FailpointRegistry.Rule("tok-" + name, name, action, 0, 1, 10_000, Map.of()));
    }

    private void table(String... keyValues) throws StorageException {
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] == null) {
                engine.delete(keyValues[i], ctx());
            } else {
                engine.put(keyValues[i], keyValues[i + 1].getBytes(StandardCharsets.UTF_8), ctx());
            }
        }
        assertTrue(engine.rotateIfActiveContainsUpTo(engine.maxSequenceInEngine(ctx()), ctx()));
        assertTrue(flushJob.flushOldest(null, ctx()).isPresent());
    }

    private void specExampleTables() throws StorageException {
        table("alpha", "A", "beta", "B");
        table("alpha", null, "gamma", "G");
    }

    private Map<String, String> answers() throws StorageException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            var result = engine.get(key, ctx());
            out.put(key, result.isFound() ? new String(result.value(), StandardCharsets.UTF_8) : null);
        }
        StringBuilder scan = new StringBuilder();
        for (InternalRecord record : engine.scan("a", "z", 1000, ctx()).items()) {
            scan.append(record.key()).append('=')
                    .append(new String(record.value(), StandardCharsets.UTF_8)).append(';');
        }
        out.put("scan", scan.toString());
        return out;
    }

    private List<String> files(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private Manifest diskManifest() throws Exception {
        return new ManifestManager(layout, new RecordingStorageIO(failpoints)).loadAndValidate();
    }

    private List<InternalRecord> records(SSTableMeta meta) throws StorageException {
        DiskSSTableTableSource table = DiskSSTableTableSource.open(layout.sstDir().resolve(meta.file()), meta);
        List<InternalRecord> out = new ArrayList<>();
        for (Iterator<InternalRecord> it = table.iterator(""); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    @Test
    void fewerThanTwoTablesIsNoOp() throws Exception {
        CompactionJob.Outcome none = job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx());
        assertEquals(new CompactionJob.Outcome(List.of(), null, 0), none);
        assertFalse(none.published());

        table("alpha", "A");
        CompactionJob.Outcome one = job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx());
        assertEquals(new CompactionJob.Outcome(List.of(1L), null, 1), one);

        assertEquals(0, stats.compactionAttempts.get());
        assertEquals(0, stats.compactionPublications.get());
        assertEquals(1, diskManifest().generation());
    }

    @Test
    void automaticThresholdNeedsFourTables() throws Exception {
        table("alpha", "A");
        table("beta", "B");
        table("gamma", "G");

        CompactionJob.Outcome below = job.compact(CompactionJob.AUTOMATIC_MINIMUM_INPUTS, ctx());
        assertEquals(new CompactionJob.Outcome(List.of(), null, 3), below);
        assertEquals(0, stats.compactionAttempts.get());

        table("delta", "D");
        CompactionJob.Outcome outcome = job.compact(CompactionJob.AUTOMATIC_MINIMUM_INPUTS, ctx());
        assertEquals(List.of(1L, 2L, 3L, 4L), outcome.inputTableIds());
        assertEquals(5L, outcome.outputTableId());
    }

    @Test
    void specExampleKeepsNewestVersionAndTombstone() throws Exception {
        specExampleTables();
        Map<String, String> before = answers();
        long flushedThrough = manifests.current().flushedThroughSequence();

        CompactionJob.Outcome outcome = job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx());

        assertEquals(new CompactionJob.Outcome(List.of(1L, 2L), 3L, 3), outcome);
        assertEquals(before, answers());

        Manifest manifest = diskManifest();
        assertEquals(manifest, manifests.current());
        assertEquals(flushedThrough, manifest.flushedThroughSequence());
        assertEquals(4, manifest.nextTableId());
        assertEquals(1, manifest.sstables().size());
        SSTableMeta output = manifest.sstables().get(0);
        assertEquals(3, output.tableId());
        assertEquals(3, output.entryCount());

        List<InternalRecord> records = records(output);
        assertEquals(List.of("alpha", "beta", "gamma"), records.stream().map(InternalRecord::key).toList());
        assertEquals(Kind.DELETE, records.get(0).kind());
        assertEquals(3, records.get(0).sequence());
        assertEquals(Kind.PUT, records.get(1).kind());
        assertEquals(2, records.get(1).sequence());
        assertEquals(4, records.get(2).sequence());

        assertEquals(List.of(SSTableMeta.fileName(3)), files(layout.sstDir()));
        assertTrue(files(layout.tmpDir()).isEmpty());
        assertEquals(List.of(3L), engine.currentView().tableIds());
        assertEquals(1, stats.compactionAttempts.get());
        assertEquals(1, stats.compactionPublications.get());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    @Test
    void compactionKeepsLaterFlushesWorking() throws Exception {
        specExampleTables();
        job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx());

        table("alpha", "A2", "delta", "D");
        Map<String, String> before = answers();
        CompactionJob.Outcome again = job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx());

        assertEquals(List.of(3L, 4L), again.inputTableIds());
        assertEquals(5L, again.outputTableId());
        assertEquals(before, answers());
        assertEquals("A2", answers().get("alpha"));
    }

    @Test
    void progressShowsSelectedInputsDuringCompaction() throws Exception {
        specExampleTables();
        arm(FailpointNames.COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT, Action.PAUSE);
        String token = "tok-" + FailpointNames.COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT;
        List<Throwable> errors = new ArrayList<>();

        Thread compactor = new Thread(() -> {
            try {
                job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx());
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        compactor.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (failpoints.stateOf(token) != FailpointRegistry.State.TRIGGERED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(FailpointRegistry.State.TRIGGERED, failpoints.stateOf(token));

        var snapshot = progress.snapshot();
        assertEquals("COMPACTION", snapshot.operation());
        assertEquals("CAPTURE", snapshot.phase());
        assertNull(snapshot.targetSequence());
        assertEquals(List.of(1L, 2L), snapshot.inputTableIds());

        failpoints.releaseRule(token);
        compactor.join(5000);
        assertFalse(compactor.isAlive());
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    @Test
    void corruptInputFailsEngineWithItsCategoryAndPublishesNothing() throws Exception {
        specExampleTables();
        Path first = layout.sstDir().resolve(SSTableMeta.fileName(1));
        byte[] content = Files.readAllBytes(first);
        content[16 + 24] ^= 0x01;
        Files.write(first, content);

        StorageException e = assertThrows(StorageException.class,
                () -> job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx()));

        assertEquals("DATA_BLOCK", e.category());
        assertEquals(Lifecycle.FAILED, engine.lifecycle());
        assertSame(e, engine.lastFatalError().orElseThrow());
        assertEquals(2, diskManifest().generation());
        assertEquals(List.of(1L, 2L), diskManifest().sstables().stream().map(SSTableMeta::tableId).toList());
        assertEquals(List.of(SSTableMeta.fileName(1), SSTableMeta.fileName(2)), files(layout.sstDir()));
        assertTrue(files(layout.tmpDir()).isEmpty());
        assertEquals(1, stats.compactionAttempts.get());
        assertEquals(0, stats.compactionPublications.get());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    @Test
    void ioErrorWritingOutputIsNotFatal() throws Exception {
        specExampleTables();
        Map<String, String> before = answers();
        arm(FailpointNames.SSTABLE_AFTER_SYNC_BEFORE_RENAME, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx()));

        assertInputsKept(before);
        assertTrue(engine.lastFatalError().isEmpty());
        assertRetrySucceeds(before);
    }

    @Test
    void ioErrorAfterSelectionKeepsInputs() throws Exception {
        specExampleTables();
        Map<String, String> before = answers();
        arm(FailpointNames.COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx()));

        assertInputsKept(before);
        assertEquals(0, stats.orphanFilesRemoved.get());
        assertRetrySucceeds(before);
    }

    @Test
    void ioErrorAfterOutputFinalizeRemovesUnpublishedOutput() throws Exception {
        specExampleTables();
        Map<String, String> before = answers();
        arm(FailpointNames.COMPACTION_AFTER_OUTPUT_FINALIZE_BEFORE_MANIFEST, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx()));

        assertInputsKept(before);
        assertEquals(1, stats.orphanFilesRemoved.get());
        assertRetrySucceeds(before);
    }

    @Test
    void ioErrorBeforeSstableSyncRemovesTemp() throws Exception {
        specExampleTables();
        Map<String, String> before = answers();
        arm(FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx()));

        assertInputsKept(before);
        assertEquals(1, stats.temporaryFilesRemoved.get());
        assertRetrySucceeds(before);
    }

    @Test
    void ioErrorBeforeManifestRenameKeepsInputs() throws Exception {
        specExampleTables();
        Map<String, String> before = answers();
        arm(FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx()));

        assertInputsKept(before);
        assertRetrySucceeds(before);
    }

    @Test
    void ioErrorAfterManifestRenameFailsEngineAndDeletesNothing() throws Exception {
        specExampleTables();
        arm(FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx()));

        assertEquals(Lifecycle.FAILED, engine.lifecycle());
        assertEquals(List.of(SSTableMeta.fileName(1), SSTableMeta.fileName(2), SSTableMeta.fileName(3)),
                files(layout.sstDir()));
        assertEquals(0, stats.compactionPublications.get());
    }

    @Test
    void ioErrorBeforeInputDeleteKeepsPublishedOutputServing() throws Exception {
        specExampleTables();
        Map<String, String> before = answers();
        arm(FailpointNames.COMPACTION_AFTER_MANIFEST_PUBLISH_BEFORE_INPUT_DELETE, Action.IO_ERROR);

        assertThrows(StorageException.class, () -> job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx()));

        assertEquals(Lifecycle.READY, engine.lifecycle());
        assertEquals(List.of(3L), engine.currentView().tableIds());
        assertEquals(List.of(3L), diskManifest().sstables().stream().map(SSTableMeta::tableId).toList());
        assertEquals(List.of(SSTableMeta.fileName(1), SSTableMeta.fileName(2), SSTableMeta.fileName(3)),
                files(layout.sstDir()));
        assertEquals(before, answers());
        assertEquals(1, stats.compactionPublications.get());
    }

    private void assertInputsKept(Map<String, String> before) throws Exception {
        assertEquals(Lifecycle.READY, engine.lifecycle());
        assertEquals(2, diskManifest().generation());
        assertEquals(List.of(1L, 2L), engine.currentView().tableIds());
        assertEquals(List.of(SSTableMeta.fileName(1), SSTableMeta.fileName(2)), files(layout.sstDir()));
        assertTrue(files(layout.tmpDir()).isEmpty());
        assertEquals(before, answers());
        assertEquals(0, stats.compactionPublications.get());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    private void assertRetrySucceeds(Map<String, String> before) throws Exception {
        CompactionJob.Outcome outcome = job.compact(CompactionJob.MANUAL_MINIMUM_INPUTS, ctx());

        assertEquals(List.of(1L, 2L), outcome.inputTableIds());
        assertEquals(4L, outcome.outputTableId());
        assertEquals(List.of(SSTableMeta.fileName(4)), files(layout.sstDir()));
        assertEquals(before, answers());
        assertEquals(2, stats.compactionAttempts.get());
        assertEquals(1, stats.compactionPublications.get());
    }
}
