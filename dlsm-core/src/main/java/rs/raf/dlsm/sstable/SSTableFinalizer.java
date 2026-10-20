
package rs.raf.dlsm.sstable;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.io.StorageIO;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Makes a validated temporary SSTable durable at its final path.
 *
 * This does NOT publish the SSTable in the manifest.
 * Manifest publication is a separate maintenance operation.
 *
 * Required order:
 * 1. force temporary SSTable
 * 2. close its write channel
 * 3. atomic move without replacement
 * 4. fsync destination directory
 */
public final class SSTableFinalizer {

    private final StorageIO io;

    public SSTableFinalizer(StorageIO io) {
        this.io = Objects.requireNonNull(io, "io");
    }

    /**
     * The caller must finish writing and validate the temporary
     * SSTable before invoking this method.
     *
     * Ownership of the channel is transferred to this method:
     * it will be closed before the rename.
     *
     * If the move succeeds but directory fsync fails, the final
     * file may exist but is NOT considered durably finalized.
     * The caller must not publish it in the manifest.
     */
    public void finalizeTable(
            Path temporaryFile,
            Path finalFile,
            FileChannel writeChannel,
            OpContext context) throws IOException {

        Objects.requireNonNull(
                temporaryFile, "temporaryFile");
        Objects.requireNonNull(
                finalFile, "finalFile");
        Objects.requireNonNull(
                writeChannel, "writeChannel");
        Objects.requireNonNull(
                context, "context");

        Path source = temporaryFile.toAbsolutePath()
                .normalize();
        Path destination = finalFile.toAbsolutePath()
                .normalize();

        if (source.equals(destination)) {
            throw new IllegalArgumentException(
                    "Temporary and final paths must differ");
        }

        Path targetDirectory = destination.getParent();

        if (targetDirectory == null) {
            throw new IllegalArgumentException(
                    "Final SSTable must have a parent directory");
        }

        // The failpoint fires immediately before force.
        // A failed force must prevent rename.
        try {
            io.force(
                    writeChannel,
                    FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC,
                    context
            );
        } finally {
            writeChannel.close();
        }

        // No replacement: table IDs must never silently
        // overwrite an existing published SSTable.
        io.atomicMoveNoReplace(
                source,
                destination,
                FailpointNames.SSTABLE_AFTER_SYNC_BEFORE_RENAME,
                context
        );

        // The file is renamed but still not guaranteed durable
        // until the directory metadata has been synchronized.
        io.fsyncDirectory(
                targetDirectory,
                FailpointNames
                        .SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC,
                context
        );
    }
}
