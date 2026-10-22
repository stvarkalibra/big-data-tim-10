package rs.raf.dlsm.recovery;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Fixed layout of one database directory (CP1 §14, FORMAT.md §2):
 *
 * <pre>
 * &lt;root&gt;/
 * ├── LOCK
 * ├── node.json
 * └── lsm/
 *     ├── MANIFEST.json
 *     ├── wal/
 *     ├── sst/
 *     └── tmp/
 * </pre>
 *
 * Every path comes from the configured root, never from a global or a
 * fixed location, so several engines can run in one process (plan §19).
 */
public record DataLayout(Path root) {

    public static final String LOCK_FILE = "LOCK";
    public static final String NODE_FILE = "node.json";
    public static final String LSM_DIR = "lsm";
    public static final String MANIFEST_FILE = "MANIFEST.json";
    public static final String WAL_DIR = "wal";
    public static final String SST_DIR = "sst";
    public static final String TMP_DIR = "tmp";

    /** Temporary name of {@code node.json} while it is first written (FORMAT.md §4.1). */
    public static final String NODE_TEMP_FILE = "node.json.tmp";

    public DataLayout {
        Objects.requireNonNull(root, "root");
    }

    public Path lockFile() {
        return root.resolve(LOCK_FILE);
    }

    public Path nodeFile() {
        return root.resolve(NODE_FILE);
    }

    public Path lsmDir() {
        return root.resolve(LSM_DIR);
    }

    public Path manifestFile() {
        return lsmDir().resolve(MANIFEST_FILE);
    }

    public Path walDir() {
        return lsmDir().resolve(WAL_DIR);
    }

    public Path sstDir() {
        return lsmDir().resolve(SST_DIR);
    }

    public Path tmpDir() {
        return lsmDir().resolve(TMP_DIR);
    }

    public Path nodeTempFile() {
        return tmpDir().resolve(NODE_TEMP_FILE);
    }

    /** The four directories under the root, parents first (CP1 §14.2 step 1). */
    public List<Path> requiredDirectories() {
        return List.of(lsmDir(), walDir(), sstDir(), tmpDir());
    }
}
