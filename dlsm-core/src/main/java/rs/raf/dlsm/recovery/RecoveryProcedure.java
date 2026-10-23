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
import rs.raf.dlsm.sstable.SSTableRecoveryVerifier;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
