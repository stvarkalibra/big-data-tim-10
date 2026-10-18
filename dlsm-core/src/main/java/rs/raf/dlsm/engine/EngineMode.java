package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.Lifecycle;

/**
 * Internal engine mode (CP1 §22), reported as {@code engineMode} in status.
 * {@code SERVING} and {@code BACKPRESSURED} are both lifecycle
 * {@code READY}; there is no way back from {@code FAILED} without restart.
 */
public enum EngineMode {
    RECOVERING,
    SERVING,
    BACKPRESSURED,
    FAILED;

    public Lifecycle lifecycle() {
        return switch (this) {
            case RECOVERING -> Lifecycle.RECOVERING;
            case SERVING, BACKPRESSURED -> Lifecycle.READY;
            case FAILED -> Lifecycle.FAILED;
        };
    }
}
