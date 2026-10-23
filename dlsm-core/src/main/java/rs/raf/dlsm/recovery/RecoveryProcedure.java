package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableRecoveryVerifier;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.stats.StorageStats;

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
