package rs.raf.dlsm.engine;

import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.failpoint.NoOpFailpoints;

import java.util.Objects;

/**
 * Engine configuration (plan §5.1). Grows as the engine gains a data
 * directory, WAL and statistics.
 *
 * @param memtableMaxBytes {@code MEMTABLE_MAX_BYTES}; reaching it requests
 *                         rotation of the active MemTable (CP1 §10)
 * @param failpoints       deterministic fault injection (CP1 §26); a no-op
 *                         outside test mode
 */
public record EngineConfig(long memtableMaxBytes, Failpoints failpoints) {

    /** Default {@code MEMTABLE_MAX_BYTES}: 1 MiB (CP1 §10). */
    public static final long DEFAULT_MEMTABLE_MAX_BYTES = 1L << 20;

    public EngineConfig {
        if (memtableMaxBytes < 1) {
            throw new IllegalArgumentException("memtableMaxBytes must be positive");
        }
        Objects.requireNonNull(failpoints, "failpoints");
    }

    /** Without failpoints. */
    public EngineConfig(long memtableMaxBytes) {
        this(memtableMaxBytes, new NoOpFailpoints());
    }

    public static EngineConfig defaults() {
        return new EngineConfig(DEFAULT_MEMTABLE_MAX_BYTES);
    }
}
