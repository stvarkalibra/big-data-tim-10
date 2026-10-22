package rs.raf.dlsm.recovery;

/**
 * What a database directory is at startup (CP1 §14.2, FORMAT.md §4.3).
 * States that cannot be continued safely are not values here: they fail
 * startup with a {@code StorageException} instead.
 */
public enum StartupState {

    /**
     * No {@code node.json} and no stable data: a proven new, empty database.
     * Initialization starts at step 1; directories may already exist.
     */
    NEW_DATABASE,

    /**
     * A valid matching {@code node.json}, all directories, and neither a
     * manifest nor any WAL or SSTable file: initialization crashed after
     * step 2 and continues at step 3 (empty manifest of generation 0).
     */
    RESUME_AT_MANIFEST,

    /**
     * A valid matching {@code node.json}, all directories and a manifest:
     * the normal recovery of CP1 §21.1 runs. An empty manifest without an
     * active WAL is handled there too, by creating a new segment (step 12).
     */
    EXISTING
}
