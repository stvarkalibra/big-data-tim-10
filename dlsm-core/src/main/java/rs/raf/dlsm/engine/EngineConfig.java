package rs.raf.dlsm.engine;

/**
 * Engine configuration (plan §5.1). Grows as the engine gains a data
 * directory, WAL, failpoints and statistics.
 *
 * @param memtableMaxBytes {@code MEMTABLE_MAX_BYTES}; reaching it requests
 *                         rotation of the active MemTable (CP1 §10)
 */
public record EngineConfig(long memtableMaxBytes) {

    /** Default {@code MEMTABLE_MAX_BYTES}: 1 MiB (CP1 §10). */
    public static final long DEFAULT_MEMTABLE_MAX_BYTES = 1L << 20;

    public EngineConfig {
        if (memtableMaxBytes < 1) {
            throw new IllegalArgumentException("memtableMaxBytes must be positive");
        }
    }

    public static EngineConfig defaults() {
        return new EngineConfig(DEFAULT_MEMTABLE_MAX_BYTES);
    }
}
