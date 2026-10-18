package rs.raf.dlsm.common;

/**
 * Stable error codes of the shared API (PI §18.7). The enum name is the
 * exact string sent to clients, so constants must never be renamed.
 *
 * <p>The mapping to HTTP status lives in the server module; the engine
 * itself knows nothing about HTTP (CP1 §2.1).
 */
public enum ErrorCode {
    /** Malformed key, Base64, header or scan bounds. */
    INVALID_REQUEST,
    /** Decoded value larger than {@link InternalRecord#MAX_VALUE_BYTES}. */
    VALUE_TOO_LARGE,
    /** Key absent or its newest version is a tombstone. */
    NOT_FOUND,
    /** Storage I/O failure or detected corruption. */
    STORAGE_IO,
    /** Bug or broken invariant inside the node. */
    INTERNAL_ERROR,
    /** Recovery still running, or the engine is FAILED. */
    NODE_NOT_READY,
    /** Request deadline expired, e.g. while waiting on backpressure. */
    DEADLINE_EXCEEDED
}
