package rs.raf.dlsm.common;

/**
 * Shared lifecycle states reported by health and status endpoints
 * (PI §20.1). The enum name is the exact string on the wire.
 *
 * <p>In CP1 the engine reports only {@code RECOVERING}, {@code READY} and
 * {@code FAILED} (CP1 §22); its internal modes {@code SERVING} and
 * {@code BACKPRESSURED} both map to {@code READY}. {@code STARTING} covers
 * the process before the engine is opened.
 */
public enum Lifecycle {
    STARTING,
    RECOVERING,
    JOINING,
    READY,
    DEGRADED,
    FAILED,
    STOPPING
}
