package rs.raf.dlsm.engine;

import java.util.Objects;

/**
 * Result of GET: the exact value bytes, or not found when the key never
 * existed or its newest version is a tombstone (CP1 §11). An empty value is
 * found, not absent.
 */
public final class GetResult {

    private static final GetResult NOT_FOUND = new GetResult(null);

    private final byte[] value;

    private GetResult(byte[] value) {
        this.value = value;
    }

    public static GetResult found(byte[] value) {
        return new GetResult(Objects.requireNonNull(value, "value").clone());
    }

    public static GetResult notFound() {
        return NOT_FOUND;
    }

    public boolean isFound() {
        return value != null;
    }

    /** @throws IllegalStateException when not found */
    public byte[] value() {
        if (value == null) {
            throw new IllegalStateException("Key not found");
        }
        return value.clone();
    }

    @Override
    public String toString() {
        return value == null
                ? "GetResult[notFound]"
                : "GetResult[found, valueLength=" + value.length + "]";
    }
}
