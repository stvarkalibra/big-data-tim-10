package rs.raf.dlsm.common;

import java.util.Arrays;
import java.util.Objects;

/**
 * One version of a key, as stored in the WAL, MemTable and SSTables.
 *
 * <p>{@code value} is {@code null} only for a {@link Kind#DELETE} tombstone;
 * an empty PUT holds {@code byte[0]} and is a real, present value (PI §18).
 * Ordering between versions is decided only by {@code sequence} (I3).
 * The value array is copied on the way in and on the way out, so a record
 * can be shared between threads and sources without anyone mutating it.
 */
public record InternalRecord(String key, long sequence, Kind kind, byte[] value) {

    public static final int MAX_VALUE_BYTES = 262_144;

    public InternalRecord {
        Keys.requireValid(key);

        if (sequence <= 0) {
            throw new IllegalArgumentException("Sequence must be positive");
        }

        Objects.requireNonNull(kind, "kind");

        if (kind == Kind.DELETE && value != null) {
            throw new IllegalArgumentException(
                    "DELETE must not carry a value");
        }

        if (kind == Kind.PUT && value == null) {
            throw new IllegalArgumentException("PUT requires a value");
        }

        if (value != null && value.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Value too large");
        }

        value = value == null ? null : value.clone();
    }

    public static InternalRecord put(String key, long sequence, byte[] value) {
        return new InternalRecord(key, sequence, Kind.PUT, value);
    }

    public static InternalRecord delete(String key, long sequence) {
        return new InternalRecord(key, sequence, Kind.DELETE, null);
    }

    @Override
    public byte[] value() {
        return value == null ? null : value.clone();
    }

    public boolean isTombstone() {
        return kind == Kind.DELETE;
    }

    /** Value length without copying; 0 for a tombstone. */
    public int valueLength() {
        return value == null ? 0 : value.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InternalRecord other
                && sequence == other.sequence
                && kind == other.kind
                && key.equals(other.key)
                && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + Long.hashCode(sequence);
        result = 31 * result + kind.hashCode();
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }

    /** Never prints value bytes, so records are safe to log (CP1 §27). */
    @Override
    public String toString() {
        return "InternalRecord[key=" + key
                + ", sequence=" + sequence
                + ", kind=" + kind
                + ", valueLength=" + (value == null ? "null" : value.length)
                + "]";
    }
}
