package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.FlushScheduler;
import rs.raf.dlsm.engine.MaintenanceHost;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableRecordAdapter;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.RecordingStorageIO;
import rs.raf.dlsm.wal.WalFileNames;
import rs.raf.dlsm.wal.WalFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static rs.raf.dlsm.recovery.WalRecoveryHarness.put;
import static rs.raf.dlsm.recovery.WalRecoveryHarness.segment;

/** CP1 §21.1 end to end: new, existing and damaged directories. */
class RecoveryProcedureTest {

    private static final OpContext CTX = OpContext.background();
    private static final String NODE = "standalone";
    private static final long MAX_BYTES = 1L << 20;

    @TempDir
    Path tempDir;

    private DataLayout layout;
    private final FailpointRegistry failpoints = new FailpointRegistry(true);
    private final RecordingStorageIO io = new RecordingStorageIO(failpoints);
    private StorageStats stats;
    private final List<RecoveryProcedure> procedures = new ArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        layout = new DataLayout(tempDir.resolve("dlsm"));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable resource : opened) {
            resource.close();
        }
        for (RecoveryProcedure procedure : procedures) {
            procedure.close();
        }
    }

    private RecoveryProcedure procedure(String nodeId) {
        stats = new StorageStats();
        JsonLog log = new JsonLog("test",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), Clock.systemUTC());
        RecoveryProcedure procedure = new RecoveryProcedure(layout, nodeId, io, failpoints, stats, log);
        procedures.add(procedure);
        return procedure;
    }

    /** One start that only recovers; the new active segment is closed at the end of the test. */
    private RecoveryResult recover() throws Exception {
        RecoveryResult result = procedure(NODE).recover(MAX_BYTES, CTX);
        opened.add(result.wal().activeLog()::close);
        return result;
    }

    /**
     * One start that opens the engine. No flush runs: the recovered
     * generation stays frozen, which is enough for these reads. (Mladen's
     * {@code InMemoryFlusher} numbers its tables from 1, which clashes with
     * real tables from the manifest; the real worker allocates IDs from
     * {@link RecoveryResult#tableIds()}.)
     */
    private Engine start() throws Exception {
        Engine engine = procedure(NODE).openEngine(
                new EngineConfig(MAX_BYTES, failpoints), (host, recovered) -> FlushScheduler.NONE, CTX);
        opened.add(engine);
        return engine;
    }

    /** Ends a start like a crash would, but releases the lock so the test can start again. */
    private void stop() throws Exception {
        tearDown();
        opened.clear();
        procedures.clear();
    }

    private SSTableMeta writeTable(long tableId, InternalRecord... sorted) throws IOException {
        SSTableWriter.Result table = SSTableWriter.build(tableId,
                SSTableRecordAdapter.adapt(List.of(sorted).iterator()), sorted.length);
        Files.write(layout.sstDir().resolve(table.meta().file()), table.bytes());
        return table.meta();
    }

    private void publishManifest(long flushedThrough, SSTableMeta... tables) throws Exception {
        ManifestManager manifests = new ManifestManager(layout, io);
        manifests.loadAndValidate();
        long nextTableId = Arrays.stream(tables).mapToLong(SSTableMeta::tableId).max().orElse(0) + 1;
        manifests.publish(base -> new Manifest(base.formatVersion(), base.generation() + 1,
                Math.max(base.nextTableId(), nextTableId), flushedThrough, List.of(tables)), CTX);
    }

    private void writeSegment(long id, byte[] bytes) throws IOException {
        Files.write(layout.walDir().resolve(WalFileNames.segmentFileName(id)), bytes);
    }

    private List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String text(Engine engine, String key) throws StorageException {
        var result = engine.get(key, OpContext.withTimeout("test-get", 5_000));
        return result.isFound() ? new String(result.value(), StandardCharsets.US_ASCII) : null;
    }

    private static StorageException assertCategory(String category, Executable call) {
        StorageException e = assertThrows(StorageException.class, call);
        assertEquals(category, e.category(), e.getMessage());
        return e;
    }

    // ===== A brand-new directory =====

    @Test
    void newDirectoryBecomesAnEmptyDatabase() throws Exception {
        RecoveryResult result = recover();

        assertEquals(StartupState.NEW_DATABASE, result.foundState());
        assertEquals(Manifest.initial(), result.manifest());
        assertEquals(List.of(), result.tables());
        assertEquals(0, result.wal().lastUsedSequence());
        assertNull(result.wal().recoveredGeneration());
        assertEquals(1, result.wal().activeLog().segmentId());
        assertTrue(Files.exists(layout.nodeFile()));
        assertTrue(Files.exists(layout.manifestFile()));
        assertEquals(List.of(WalFileNames.segmentFileName(1)), names(layout.walDir()));
    }

    // ===== Durability through the engine (CP1-WAL-001 at engine level) =====

    @Test
    void acknowledgedMutationsSurviveRestart() throws Exception {
        Engine first = start();
        OpContext ctx = OpContext.withTimeout("test-put", 5_000);
        first.put("alpha", "A".getBytes(StandardCharsets.US_ASCII), ctx);
        first.put("beta", "B".getBytes(StandardCharsets.US_ASCII), ctx);
        first.delete("beta", ctx);
        first.put("empty", new byte[0], ctx);
        stop();

        Engine second = start();

        assertEquals("A", text(second, "alpha"));
        assertNull(text(second, "beta"));
        assertEquals("", text(second, "empty"));
        assertEquals(5, second.put("gamma", new byte[]{1}, ctx).sequence());
    }

    // ===== Tables and WAL together =====

    @Test
    void tablesAndWalSuffixFormOneState() throws Exception {
        recover();
        stop();
        SSTableMeta table = writeTable(1, put("alpha", 1, "A1"), put("only", 2, "O"));
        publishManifest(2, table);
        writeSegment(2, segment(2, put("alpha", 1, "A1"), put("only", 2, "O")));
        writeSegment(3, segment(3, put("alpha", 3, "A2"), put("beta", 4, "B")));

        Engine engine = start();

        assertEquals("A2", text(engine, "alpha"));
        assertEquals("O", text(engine, "only"));
        assertEquals("B", text(engine, "beta"));
        // Segment 1 was empty and segment 2 fully covered by flushedThroughSequence 2.
        assertEquals(List.of(WalFileNames.segmentFileName(3), WalFileNames.segmentFileName(4)),
                names(layout.walDir()));
        assertEquals(5, engine.put("delta", new byte[]{1}, OpContext.withTimeout("t", 5_000)).sequence());
    }

    @Test
    void maintenanceWorkerContinuesFromTheRecoveredState() throws Exception {
        recover();
        stop();
        SSTableMeta table = writeTable(3, put("alpha", 1, "A1"));
        publishManifest(1, table);
        Files.write(layout.sstDir().resolve(SSTableMeta.fileName(7)), new byte[]{1});
        writeSegment(2, segment(2, put("alpha", 1, "A1"), put("beta", 2, "B")));

        List<MaintenanceHost> hosts = new ArrayList<>();
        List<RecoveryResult> handedOver = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Engine engine = procedure(NODE).openEngine(new EngineConfig(MAX_BYTES, failpoints),
                (host, recovered) -> {
                    hosts.add(host);
                    handedOver.add(recovered);
                    return new FlushScheduler() {
                        @Override
                        public void flushNeeded() {
                            calls.add("flushNeeded");
                        }

                        @Override
                        public void checkAutoCompaction() {
                            calls.add("checkAutoCompaction");
                        }
                    };
                }, CTX);
        opened.add(engine);

        assertEquals(List.of(engine), hosts);
        RecoveryResult recovered = handedOver.get(0);
        assertEquals(1, recovered.manifest().flushedThroughSequence());
        assertEquals(List.of(3L), recovered.manifest().sstables().stream()
                .map(SSTableMeta::tableId).toList());
        assertEquals(1, recovered.tables().size());
        // Above the manifest and above orphan 7, which cleanup deleted: no ID is reused (CP1 §18.3).
        assertEquals(8, recovered.tableIds().peek());
        assertFalse(Files.exists(layout.sstDir().resolve(SSTableMeta.fileName(7))));
        // The recovered generation (beta) goes to the worker right after READY (plan §6.2).
        assertEquals(List.of("flushNeeded", "checkAutoCompaction"), calls);
        assertEquals("B", text(engine, "beta"));
    }

    @Test
    void temporaryAndOrphanFilesAreRemoved() throws Exception {
        recover();
        stop();
        Files.write(layout.sstDir().resolve(SSTableMeta.fileName(9)), new byte[]{1});
        Files.write(layout.tmpDir().resolve(WalFileNames.tempFileName(7)), new byte[]{1});
        Files.write(layout.tmpDir().resolve("MANIFEST-00000000000000000003.tmp"), new byte[]{1});

        RecoveryResult result = recover();

        assertEquals(List.of(), names(layout.sstDir()));
        assertEquals(List.of(), names(layout.tmpDir()));
        assertEquals(1, stats.snapshot().get("orphanFilesRemoved"));
        assertEquals(2, stats.snapshot().get("temporaryFilesRemoved"));
        // The leftover temp segment's ID 7 stays reserved (CP1 §16.1).
        assertEquals(8, result.wal().activeLog().segmentId());
    }

    // ===== Damaged directories stop startup (CP1 §21.4) =====

    // CP1-SST-006: validation runs before cleanup, so nothing is deleted on a bad source.
    @Test
    void corruptReferencedTableStopsBeforeCleanup() throws Exception {
        recover();
        stop();
        SSTableMeta table = writeTable(1, put("alpha", 1, "A1"), put("beta", 2, "B1"));
        publishManifest(2, table);
        Path file = layout.sstDir().resolve(table.file());
        byte[] bytes = Files.readAllBytes(file);
        bytes[SSTableWriter.HEADER_BYTES + 20] ^= 0x01;
        Files.write(file, bytes);
        Path leftover = Files.write(layout.tmpDir().resolve(WalFileNames.tempFileName(5)), new byte[]{1});
        List<String> walBefore = names(layout.walDir());

        StorageException e = assertThrows(StorageException.class, this::recover);

        assertTrue(Arrays.stream(SSTableValidator.Category.values())
                .anyMatch(c -> c.name().equals(e.category())), e.category());
        assertTrue(Files.exists(leftover));
        assertEquals(walBefore, names(layout.walDir()));
    }

    @Test
    void missingReferencedTableStopsStartup() throws Exception {
        recover();
        stop();
        SSTableMeta table = writeTable(1, put("alpha", 1, "A1"));
        publishManifest(1, table);
        Files.delete(layout.sstDir().resolve(table.file()));

        assertCategory(SSTableValidator.Category.TRUNCATED_REGION.name(), this::recover);
    }

    @Test
    void corruptWalStopsStartupWithoutNewSegment() throws Exception {
        recover();
        stop();
        byte[] bytes = segment(2, put("alpha", 1, "A"), put("beta", 2, "B"));
        bytes[bytes.length - 1] ^= 0x01;
        writeSegment(2, bytes);

        assertCategory(WalFormat.CATEGORY_CHECKSUM, this::recover);

        assertEquals(List.of(WalFileNames.segmentFileName(1), WalFileNames.segmentFileName(2)),
                names(layout.walDir()));
    }

    @Test
    void invalidManifestStopsStartup() throws Exception {
        recover();
        stop();
        Files.writeString(layout.manifestFile(), "{\"formatVersion\":1}");

        assertCategory(Manifest.CATEGORY, this::recover);
    }

    @Test
    void foreignNodeIdentityStopsStartupAndChangesNothing() throws Exception {
        recover();
        stop();
        Path leftover = Files.write(layout.tmpDir().resolve(WalFileNames.tempFileName(9)), new byte[]{1});

        assertCategory(NodeIdentity.CATEGORY, () -> procedure("storage-1").recover(MAX_BYTES, CTX));

        assertTrue(Files.exists(leftover));
    }

    // ===== Ownership =====

    @Test
    void secondOwnerIsRefused() throws Exception {
        recover();

        assertCategory(DirectoryLock.CATEGORY, () -> procedure(NODE).recover(MAX_BYTES, CTX));
    }

    // A FAILED node still owns its directory (CP1 §14.1).
    @Test
    void failedRecoveryKeepsTheLock() throws Exception {
        recover();
        stop();
        Files.writeString(layout.manifestFile(), "not json");

        RecoveryProcedure failed = procedure(NODE);
        assertThrows(StorageException.class, () -> failed.recover(MAX_BYTES, CTX));

        assertTrue(failed.ownsDirectory());
        assertCategory(DirectoryLock.CATEGORY, () -> procedure(NODE).recover(MAX_BYTES, CTX));
    }
}
