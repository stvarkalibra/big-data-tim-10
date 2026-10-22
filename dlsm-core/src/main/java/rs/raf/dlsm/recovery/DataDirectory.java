package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.StorageIO;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

/**
 * An opened database directory: owned through {@code LOCK}, classified, and
 * brought to a state normal recovery can start from (CP1 §14, §21.1 steps 1–3).
 *
 * <p>First initialization follows CP1 §14.2:
 * <ol>
 *   <li>take {@code LOCK}, create {@code lsm/}, {@code wal/}, {@code sst/} and
 *       {@code tmp/}, force the parent directories;</li>
 *   <li>publish {@code node.json} ({@link NodeIdentityFile});</li>
 *   <li>publish the empty manifest of generation 0 ({@link ManifestInitializer});</li>
 *   <li>create the first active WAL segment;</li>
 *   <li>pair it with an empty MemTable and become ready.</li>
 * </ol>
 *
 * This class runs steps 1–3 and returns; steps 4–5 are the same for a new
 * and an existing database (recovery step 12 creates the active segment), so
 * recovery performs them for both. A crash between any two steps leaves a
 * state that {@link StartupClassifier} resumes from.
 */
public final class DataDirectory implements AutoCloseable {

    private final DataLayout layout;
    private final DirectoryLock lock;
    private final StartupState foundState;

    private DataDirectory(DataLayout layout, DirectoryLock lock, StartupState foundState) {
        this.layout = layout;
        this.lock = lock;
        this.foundState = foundState;
    }

    /**
     * Takes ownership, classifies, and initializes when the directory is new
     * or an earlier initialization was interrupted. Never re-creates the
     * identity or a directory of an existing database.
     *
     * @throws StorageException {@code DIRECTORY_LOCKED},
     *                          {@code NODE_IDENTITY_MISMATCH},
     *                          {@code DIRECTORY_LAYOUT} or
     *                          {@code MANIFEST_INVALID}; nothing was changed
     * @throws IOException      if an initialization step fails; the next
     *                          start classifies the partial state again
     */
    public static DataDirectory open(DataLayout layout, String nodeId, StorageIO io,
                                     ManifestInitializer manifest, OpContext ctx)
            throws IOException, StorageException {

        Objects.requireNonNull(layout, "layout");
        NodeIdentity identity = NodeIdentity.of(nodeId);
        Objects.requireNonNull(io, "io");
        Objects.requireNonNull(manifest, "manifest");

        // The lock file lives in the root, so the root must exist first.
        Files.createDirectories(layout.root());
        DirectoryLock lock = DirectoryLock.acquire(layout.lockFile());

        try {
            StartupState state = StartupClassifier.classify(layout, nodeId);

            switch (state) {
                case NEW_DATABASE -> {
                    createDirectories(layout, io, ctx);                 // step 1
                    NodeIdentityFile.publish(layout, identity, io, ctx); // step 2
                    manifest.publishInitialManifest(ctx);                // step 3
                }
                case RESUME_AT_MANIFEST -> manifest.publishInitialManifest(ctx); // step 3
                case EXISTING -> {
                    // Nothing to initialize: normal recovery takes over.
                }
            }

            return new DataDirectory(layout, lock, state);
        } catch (IOException | StorageException | RuntimeException e) {
            try {
                lock.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Step 1: the four directories, then a force of each parent so their
     * entries are durable before {@code node.json} can exist.
     */
    private static void createDirectories(DataLayout layout, StorageIO io, OpContext ctx)
            throws IOException {

        for (var dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        io.fsyncDirectory(layout.lsmDir(), null, ctx);
        io.fsyncDirectory(layout.root(), null, ctx);
    }

    public DataLayout layout() {
        return layout;
    }

    /** The state found before any initialization step ran. */
    public StartupState foundState() {
        return foundState;
    }

    /** True while this process owns the directory. */
    public boolean isLocked() {
        return lock.isHeld();
    }

    /**
     * Releases {@code LOCK}. A running node never calls this: the lock is
     * held until the process ends. Used by tests and an orderly shutdown.
     */
    @Override
    public void close() throws IOException {
        lock.close();
    }
}
