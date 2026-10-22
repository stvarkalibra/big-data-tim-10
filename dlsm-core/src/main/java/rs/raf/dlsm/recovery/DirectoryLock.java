package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Exclusive ownership of a database directory (CP1 §14.1, FORMAT.md §4.2).
 *
 * <p>The operating system lock on {@code LOCK} is held, together with its
 * channel, for the whole life of the process. The file itself is empty and
 * never read: its existence proves nothing, it is not deleted on shutdown,
 * and the OS releases the lock when the process ends, even after
 * {@code kill -9}.
 *
 * <p>Opening {@code LOCK} does not go through {@code StorageIO}: the file
 * carries no data and needs no durability, so there is nothing for a
 * failpoint to interrupt.
 */
public final class DirectoryLock implements AutoCloseable {

    /** Stable diagnostic category (plan §18.3). */
    public static final String CATEGORY = "DIRECTORY_LOCKED";

    private final FileChannel channel;
    private final FileLock lock;

    private DirectoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Takes the lock or fails at once; never waits for another owner.
     *
     * @throws StorageException {@code DIRECTORY_LOCKED} when another process
     *                          (or another engine in this process) owns the
     *                          directory, or the lock file cannot be opened
     */
    public static DirectoryLock acquire(Path lockFile) throws StorageException {
        Objects.requireNonNull(lockFile, "lockFile");

        FileChannel channel;
        try {
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw failure("Cannot open the directory lock file", e);
        }

        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw failure("Database directory is owned by another process", null);
            }
            return new DirectoryLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            closeAfterFailure(channel, e);
            throw failure("Database directory is already open in this process", e);
        } catch (IOException e) {
            closeAfterFailure(channel, e);
            throw failure("Taking the directory lock failed", e);
        } catch (StorageException e) {
            closeAfterFailure(channel, e);
            throw e;
        }
    }

    public boolean isHeld() {
        return lock.isValid();
    }

    /** Releases the lock; used by tests and an orderly shutdown only. */
    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }

    private static StorageException failure(String message, Throwable cause) {
        return new StorageException(ErrorCode.STORAGE_IO, CATEGORY, message, cause);
    }

    private static void closeAfterFailure(FileChannel channel, Exception failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
