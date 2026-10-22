package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Decides, before any file is created, cleaned or repaired, whether a
 * directory is a new database, an interrupted first initialization, an
 * existing database, or something that must not be touched
 * (CP1 §14.2, §21.2 steps 2–3, FORMAT.md §4.3).
 *
 * <p>The point is to tell "new empty database" from "existing database that
 * lost files": re-creating a missing identity or directory could turn data
 * loss into a healthy-looking empty node.
 *
 * <p>"Stable data" is {@code MANIFEST.json} or any entry in {@code lsm/wal/}
 * or {@code lsm/sst/}. Files in {@code lsm/tmp/} are never evidence. Must
 * run while the directory {@link DirectoryLock} is held; reads only.
 */
public final class StartupClassifier {

    /** Missing required directory next to stable metadata (FORMAT.md §4.3). */
    public static final String CATEGORY_DIRECTORY_LAYOUT = "DIRECTORY_LAYOUT";

    /** WAL or SSTable files without a manifest; the manifest is Ilija's module. */
    public static final String CATEGORY_MANIFEST_INVALID = "MANIFEST_INVALID";

    private StartupClassifier() {
    }

    /**
     * @param nodeId the configured {@code NODE_ID}
     * @throws StorageException {@code NODE_IDENTITY_MISMATCH},
     *                          {@code DIRECTORY_LAYOUT} or
     *                          {@code MANIFEST_INVALID} when startup must stop
     */
    public static StartupState classify(DataLayout layout, String nodeId)
            throws IOException, StorageException {

        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(nodeId, "nodeId");

        boolean manifest = isRegularFile(layout.manifestFile());
        boolean walOrSst = hasEntries(layout.walDir()) || hasEntries(layout.sstDir());

        if (!exists(layout.nodeFile())) {
            if (manifest || walOrSst) {
                throw failure(NodeIdentity.CATEGORY,
                        "node.json is missing but the directory holds stable data");
            }
            return StartupState.NEW_DATABASE;
        }

        // A present node.json must be a valid file naming this node.
        if (!isRegularFile(layout.nodeFile())) {
            throw failure(NodeIdentity.CATEGORY, "node.json is not a regular file");
        }
        NodeIdentity.readAndCheck(layout.nodeFile(), nodeId);

        for (Path dir : layout.requiredDirectories()) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(CATEGORY_DIRECTORY_LAYOUT, "Required directory "
                        + layout.root().relativize(dir).toString().replace('\\', '/')
                        + " is missing next to node.json");
            }
        }

        if (manifest) {
            return StartupState.EXISTING;
        }
        if (walOrSst) {
            throw failure(CATEGORY_MANIFEST_INVALID,
                    "MANIFEST.json is missing but WAL or SSTable files exist");
        }
        return StartupState.RESUME_AT_MANIFEST;
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** True when {@code dir} exists and has at least one entry. */
    private static boolean hasEntries(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return stream.iterator().hasNext();
        }
    }

    private static StorageException failure(String category, String message) {
        return new StorageException(ErrorCode.STORAGE_IO, category, message);
    }
}
