
package rs.raf.dlsm.io;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.Failpoints;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class DefaultStorageIO implements StorageIO {

    private final Failpoints failpoints;

    public DefaultStorageIO(Failpoints failpoints) {
        this.failpoints = Objects.requireNonNull(
                failpoints, "failpoints");
    }

    @Override
    public FileChannel openNew(Path path) throws IOException {
        Objects.requireNonNull(path, "path");

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        return FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
    }

    @Override
    public void writeFully(
            FileChannel channel,
            ByteBuffer buffer,
            String failpoint,
            OpContext context) throws IOException {

        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(buffer, "buffer");

        hit(failpoint, context);

        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);

            if (written <= 0) {
                throw new IOException(
                        "Storage write made no progress");
            }
        }
    }

    @Override
    public void force(
            FileChannel channel,
            String failpoint,
            OpContext context) throws IOException {

        Objects.requireNonNull(channel, "channel");

        hit(failpoint, context);
        channel.force(true);
    }

    @Override
    public void atomicMoveNoReplace(
            Path source,
            Path target,
            String failpoint,
            OpContext context) throws IOException {

        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        hit(failpoint, context);

        Path parent = target.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Refuse a destination already present, including
        // a symbolic link.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(
                    target.toString());
        }

        // A single-writer precondition is required here.
        // ATOMIC_MOVE by itself does not guarantee strict
        // NOREPLACE under a concurrent destination-creation race.
        Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE
        );
    }

    @Override
    public void atomicReplace(
            Path source,
            Path target,
            String failpoint,
            OpContext context) throws IOException {

        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        hit(failpoint, context);

        Path parent = target.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    @Override
    public void fsyncDirectory(
            Path directory,
            String failpoint,
            OpContext context) throws IOException {

        Objects.requireNonNull(directory, "directory");

        hit(failpoint, context);

        if (!Files.isDirectory(
                directory,
                LinkOption.NOFOLLOW_LINKS)) {

            throw new IOException(
                    "Not a directory: " + directory);
        }

        try (FileChannel channel = FileChannel.open(
                directory,
                StandardOpenOption.READ)) {

            channel.force(true);

        } catch (UnsupportedOperationException e) {
            throw new IOException(
                    "Directory fsync is unsupported: "
                            + directory,
                    e
            );
        }

        // Do not suppress AccessDeniedException or IOException.
        // A failed fsync must prevent successful finalization.
    }

    @Override
    public void truncate(
            FileChannel channel,
            long size) throws IOException {

        Objects.requireNonNull(channel, "channel");

        if (size < 0) {
            throw new IllegalArgumentException(
                    "Negative truncate size");
        }

        channel.truncate(size);
    }

    @Override
    public void delete(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Files.deleteIfExists(path);
    }

    private void hit(
            String failpoint,
            OpContext context) throws IOException {

        if (failpoint != null) {
            failpoints.hit(failpoint, context);
        }
    }
}
