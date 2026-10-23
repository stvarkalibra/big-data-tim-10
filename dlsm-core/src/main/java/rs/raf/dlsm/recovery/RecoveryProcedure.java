package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.FlushScheduler;
import rs.raf.dlsm.engine.MaintenanceHost;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.TableSource;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableRecoveryVerifier;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.WalIdAllocator;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Startup recovery, CP1 §21.1 steps 1–15: prove the directory state from
 * the manifest, the tables it references and the WAL suffix it does not
 * cover, then open the engine. Nothing becomes {@code READY} until every
 * step succeeded; any failure leaves the node {@code FAILED} (CP1 §21.4).
 *
 * <p>The directory stays locked for the life of the process, also after a
 * failure: a {@code FAILED} node still owns its directory (CP1 §14.1).
 */
public final class RecoveryProcedure implements AutoCloseable {

    private final DataLayout layout;
    private final String nodeId;
    private final StorageIO io;
    private final Failpoints failpoints;
    private final StorageStats stats;
    private final JsonLog log;
    private final ManifestManager manifests;

    private DataDirectory directory;

    /**
     * Creates the flush and compaction worker once the engine exists. The
     * engine is the worker's {@link MaintenanceHost}; recovery hands over
     * what the worker continues from, such as the manifest it loaded and the
     * table ID allocator built before cleanup (CP1 §18.3).
     */
    @FunctionalInterface
    public interface MaintenanceFactory {
        FlushScheduler create(MaintenanceHost host, RecoveryResult recovered);
    }

    public RecoveryProcedure(DataLayout layout, String nodeId, StorageIO io,
                             Failpoints failpoints, StorageStats stats, JsonLog log) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.io = Objects.requireNonNull(io, "io");
        this.failpoints = Objects.requireNonNull(failpoints, "failpoints");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.log = Objects.requireNonNull(log, "log");
        this.manifests = new ManifestManager(layout, io);
    }

    /**
     * Runs all of CP1 §21.1: steps 1–14 through {@link #recover}, then step
     * 15, {@code Engine.open} over the recovered state. The returned engine
     * is {@code READY}; a recovered generation is handed to the maintenance
     * worker right after (plan §6.2).
     *
     * @param maintenance creates the flush and compaction worker from the
     *                    engine and the recovered state
     * @throws StorageException with a stable category when recovery must stop;
     *                          nothing became ready and the directory stays locked
     */
    public Engine openEngine(EngineConfig config, MaintenanceFactory maintenance,
                             OpContext ctx) throws IOException, StorageException {

        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(maintenance, "maintenance");

        RecoveryResult result = recover(config.memtableMaxBytes(), ctx);
        try {
            return Engine.open(config, result.toRecoveredState(),
                    host -> maintenance.create(host, result));                     // 15
        } catch (RuntimeException e) {
            result.wal().activeLog().close();
            throw e;
        }
    }

    /**
     * Runs CP1 §21.1 steps 1–14 in order. The engine opens over the result
     * (step 15); until then nothing is {@code READY}.
     *
     * <ol>
     *   <li>steps 1–3: lock, identity and directories, manifest
     *       ({@code recovery.after-manifest-load});</li>
     *   <li>step 4: every referenced table is validated;</li>
     *   <li>WAL and table ID allocators are built from disk, before cleanup;</li>
     *   <li>steps 5–6: temporary and orphan files are removed;</li>
     *   <li>steps 7–13: the WAL part ({@link WalRecovery});</li>
     *   <li>step 14: a reader for every referenced table.</li>
     * </ol>
     *
     * @param memtableMaxBytes {@code MEMTABLE_MAX_BYTES} for the recovered generation
     * @throws StorageException with a stable category when the directory, the
     *                          manifest, a table or the WAL is invalid (CP1 §21.4)
     * @throws IOException      when a recovery step cannot be made durable
     */
    public RecoveryResult recover(long memtableMaxBytes, OpContext ctx)
            throws IOException, StorageException {

        Manifest manifest = openDirectoryAndLoadManifest(ctx);                        // 1–3
        validateTables(manifest);                                                       // 4

        // Before cleanup, so IDs of deleted leftovers are never reused (CP1 §16.1, §18.3).
        WalIdAllocator walIds = WalIdAllocator.fromDisk(layout.walDir(), layout.tmpDir());
        TableIdAllocator tableIds = TableIdAllocator.fromDisk(manifest, layout.sstDir(), layout.tmpDir());

        removeTemporaryAndOrphanFiles(manifest, ctx);                                   // 5–6

        WalRecoveryResult wal = new WalRecovery(layout, io, failpoints, stats, log)
                .run(manifest.flushedThroughSequence(), walIds, memtableMaxBytes, ctx);  // 7–13

        List<TableSource> tables;
        try {
            tables = openTables(manifest);                                              // 14
        } catch (StorageException | RuntimeException e) {
            wal.activeLog().close();
            throw e;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("manifestGeneration", manifest.generation());
        fields.put("sequence", wal.lastUsedSequence());
        fields.put("walSegmentId", wal.activeLog().segmentId());
        fields.put("message", "Recovered " + tables.size() + " tables and "
                + (wal.recoveredGeneration() == null ? 0 : wal.recoveredGeneration().entryCount())
                + " WAL keys; next sequence " + (wal.lastUsedSequence() + 1));
        log.info("RECOVERY_COMPLETE", fields);

        return new RecoveryResult(directory.foundState(), manifest, tables, tableIds, wal);
    }

    /**
     * Step 14: an in-memory index and Bloom filter for every referenced
     * table, in manifest order. Opening validates the table again, which
     * also guards against a change between step 4 and now.
     */
    List<TableSource> openTables(Manifest manifest) throws StorageException {
        List<TableSource> tables = new ArrayList<>();
        for (SSTableMeta meta : manifest.sstables()) {
            tables.add(DiskSSTableTableSource.open(layout.sstDir().resolve(meta.file()), meta, stats));
        }
        return tables;
    }

    /**
     * Steps 1–3: take the directory lock, validate or finish first
     * initialization of {@code node.json} and the directories, then load and
     * validate the authoritative manifest. {@code recovery.after-manifest-load}
     * fires right after: the manifest is read, nothing is cleaned or replayed.
     *
     * @throws StorageException {@code DIRECTORY_LOCKED}, {@code NODE_IDENTITY_MISMATCH},
     *                          {@code DIRECTORY_LAYOUT} or {@code MANIFEST_INVALID}
     */
    Manifest openDirectoryAndLoadManifest(OpContext ctx) throws IOException, StorageException {
        directory = DataDirectory.open(layout, nodeId, io, manifests, ctx);    // 1–2 (+3 if new)
        Manifest manifest = manifests.loadAndValidate();                       // 3

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("manifestGeneration", manifest.generation());
        fields.put("message", "Loaded manifest (found " + directory.foundState()
                + "): " + manifest.sstables().size() + " tables, flushedThroughSequence "
                + manifest.flushedThroughSequence());
        log.info("RECOVERY_MANIFEST_LOADED", fields);

        failpoints.hit(FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD, ctx);
        return manifest;
    }

    /**
     * Step 4: streams through every table the manifest references and checks
     * identity, every block and checksum, key order, index, Bloom filter,
     * footer and agreement with the manifest (Kimi's
     * {@code SSTableRecoveryVerifier}). Runs before any cleanup, so a bad
     * authoritative source never decides what gets deleted. Corruption is
     * never treated as absence (I7, CP1-SST-006).
     *
     * @throws StorageException with the SSTable category, e.g. {@code DATA_BLOCK}
     */
    void validateTables(Manifest manifest) throws StorageException {
        try {
            SSTableRecoveryVerifier.verifyAll(layout.sstDir(), manifest.sstables());
        } catch (SSTableValidator.ValidationException e) {
            String category = e.category().name();

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("manifestGeneration", manifest.generation());
            fields.put("corruptionCategory", category);
            fields.put("message", e.getMessage());
            log.error("RECOVERY_SSTABLE_INVALID", fields);

            throw new StorageException(ErrorCode.STORAGE_IO, category,
                    "Referenced SSTable is invalid: " + e.getMessage(), e);
        }
    }

    /** What steps 5–6 deleted, by file name. */
    record Cleanup(List<String> temporaryRemoved, List<String> orphansRemoved) {
        Cleanup {
            temporaryRemoved = List.copyOf(temporaryRemoved);
            orphansRemoved = List.copyOf(orphansRemoved);
        }
    }

    /**
     * Steps 5–6, after the manifest and its tables are proven valid:
     * <ul>
     *   <li>every file in {@code lsm/tmp/} is temporary: it is never the
     *       source of published state, so it is deleted;</li>
     *   <li>a final table name {@code sst-<id>.sst} in {@code lsm/sst/} that
     *       the manifest does not reference is an orphan, even with valid
     *       checksums, and is deleted;</li>
     *   <li>other names in {@code lsm/sst/} are left alone and logged: recovery
     *       never deletes a file it cannot classify;</li>
     *   <li>{@code lsm/wal/} is not touched here: a segment's coverage is known
     *       only after it is scanned (steps 7–13).</li>
     * </ul>
     * Each deleted directory entry is then made durable with a force of its
     * directory. The WAL and table ID allocators must be built before this
     * runs, so IDs of deleted leftovers stay reserved (CP1 §16.1, §18.3).
     * Repeating it after a crash midway deletes the rest, with the same result.
     */
    Cleanup removeTemporaryAndOrphanFiles(Manifest manifest, OpContext ctx) throws IOException {
        Set<String> referenced = new HashSet<>();
        for (SSTableMeta table : manifest.sstables()) {
            referenced.add(table.file());
        }

        List<String> temporary = new ArrayList<>();
        for (Path file : regularFiles(layout.tmpDir())) {
            io.delete(file);
            stats.increment("temporaryFilesRemoved");
            temporary.add(file.getFileName().toString());
        }

        List<String> orphans = new ArrayList<>();
        for (Path file : regularFiles(layout.sstDir())) {
            String name = file.getFileName().toString();
            if (referenced.contains(name)) {
                continue;
            }
            if (TableIdAllocator.parseTableId(name).isPresent()) {
                io.delete(file);
                stats.increment("orphanFilesRemoved");
                orphans.add(name);
            } else {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("message", "Unrecognized file left in lsm/sst: " + name);
                log.warn("RECOVERY_UNKNOWN_FILE", fields);
            }
        }

        if (!temporary.isEmpty()) {
            io.fsyncDirectory(layout.tmpDir(), null, ctx);
        }
        if (!orphans.isEmpty()) {
            io.fsyncDirectory(layout.sstDir(), null, ctx);
        }
        if (!temporary.isEmpty() || !orphans.isEmpty()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("manifestGeneration", manifest.generation());
            fields.put("message", "Removed temporary files " + temporary + " and orphan tables " + orphans);
            log.info("RECOVERY_CLEANUP", fields);
        }

        return new Cleanup(temporary, orphans);
    }

    /** Regular files directly in {@code dir}, sorted by name for a stable order. */
    private static List<Path> regularFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(file);
                }
            }
        }
        files.sort(null);
        return files;
    }

    /** The manifest module, loaded by recovery; maintenance publishes through it. */
    public ManifestManager manifests() {
        return manifests;
    }

    /** True once step 1 ran and while this process owns the directory. */
    public boolean ownsDirectory() {
        return directory != null && directory.isLocked();
    }

    /**
     * Releases the directory lock. A running or {@code FAILED} node keeps it
     * until the process ends; only tests and an orderly shutdown call this.
     */
    @Override
    public void close() throws IOException {
        if (directory != null) {
            directory.close();
        }
    }
}
