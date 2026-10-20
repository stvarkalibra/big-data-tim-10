
package rs.raf.dlsm.sstable;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.StorageIO;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;

/**
 * Production SSTable creation.
 *
 * Creates a new temporary SSTable, streams sorted records,
 * validates the completed file and durably finalizes it.
 *
 * Never publishes or edits a manifest.
 *
 * On failure, the caller must treat the output as unpublished
 * and preserve the WAL / source tables.
 */
public final class SSTableDiskWriter {

    private final StorageIO io;
    private final SSTableFinalizer finalizer;

    public SSTableDiskWriter(StorageIO io) {
        this.io = Objects.requireNonNull(io, "io");
        this.finalizer = new SSTableFinalizer(io);
    }

    /**
     * Writes and finalizes one SSTable.
     *
     * @param temporaryFile exclusive new temporary path
     * @param finalFile reserved final SSTable path
     * @param tableId positive ID reserved by table allocator
     * @param sortedUnique strictly key-ordered engine records
     * @param expectedEntries exact number of records
     * @param context operation context for failpoints
     *
     * @return metadata only after successful finalization
     */
    public SSTableMeta writeAndFinalize(
            Path temporaryFile,
            Path finalFile,
            long tableId,
            Iterator<InternalRecord> sortedUnique,
            long expectedEntries,
            OpContext context) throws IOException {

        Objects.requireNonNull(
                temporaryFile, "temporaryFile");
        Objects.requireNonNull(
                finalFile, "finalFile");
        Objects.requireNonNull(
                sortedUnique, "sortedUnique");
        Objects.requireNonNull(
                context, "context");

        if (tableId <= 0 || expectedEntries <= 0) {
            throw new IllegalArgumentException(
                    "Invalid SSTable tableId or entry count");
        }

        if (!finalFile.getFileName().toString().equals(
                SSTableMeta.fileName(tableId))) {
            throw new IllegalArgumentException(
                    "Final SSTable filename does not match ID");
        }

        FileChannel channel = io.openNew(temporaryFile);
        boolean finalized = false;

        try {
            // The OutputStream delegates every binary write to
            // the actual StorageIO abstraction.
            OutputStream output = new OutputStream() {

                @Override
                public void write(int value)
                        throws IOException {

                    byte[] single = {(byte) value};
                    write(single, 0, 1);
                }

                @Override
                public void write(
                        byte[] bytes,
                        int offset,
                        int length) throws IOException {

                    Objects.checkFromIndexSize(
                            offset, length, bytes.length);

                    if (length == 0) {
                        return;
                    }

                    io.writeFully(
                            channel,
                            ByteBuffer.wrap(
                                    bytes, offset, length),
                            null,
                            context
                    );
                }

                // Ownership stays with the writer/finalizer.
                // Closing this adapter must not close the channel.
                @Override
                public void close() {
                }
            };

            SSTableMeta metadata =
                    SSTableStreamEncoder.encode(
                            output,
                            tableId,
                            SSTableRecordAdapter.adapt(
                                    sortedUnique
                            ),
                            expectedEntries
                    );

            // Writer self-check, before force or rename.
            // A corrupt temporary file is never finalized.
            SSTableValidator.validateFully(
                    temporaryFile,
                    metadata
            );

            finalizer.finalizeTable(
                    temporaryFile,
                    finalFile,
                    channel,
                    context
            );

            finalized = true;
            return metadata;

        } finally {
            if (channel.isOpen()) {
                channel.close();
            }

            // A failure before successful finalization leaves
            // an unpublished temporary or orphan final file.
            // Cleanup policy belongs to recovery/maintenance.
            //
            // We deliberately do not delete anything here:
            // after a crash, recovery must be able to observe
            // and classify leftover files safely.
            if (!finalized) {
                // No manifest publication has occurred.
            }
        }
    }
}
