package rs.raf.dlsm.common;

import java.util.Objects;

/**
 * Carries a {@link StorageException} out of code that cannot throw checked
 * exceptions, such as {@link java.util.Iterator#next()} over an SSTable.
 * Whoever drives the iteration unwraps it with {@link #getCause()}, so a
 * corrupt block still surfaces as a storage error, never as absence (I7).
 */
public final class UncheckedStorageException extends RuntimeException {

    public UncheckedStorageException(StorageException cause) {
        super(Objects.requireNonNull(cause, "cause").getMessage(), cause);
    }

    @Override
    public synchronized StorageException getCause() {
        return (StorageException) super.getCause();
    }
}
