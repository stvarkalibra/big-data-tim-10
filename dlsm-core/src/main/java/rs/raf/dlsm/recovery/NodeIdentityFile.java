package rs.raf.dlsm.recovery;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.StorageIO;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Publishes {@code node.json} once, at first initialization (CP1 §14.2 step 2),
 * with the same discipline as other small durable metadata (FORMAT.md §4.1):
 *
 * <ol>
 *   <li>write the complete content to {@code lsm/tmp/node.json.tmp};</li>
 *   <li>force it;</li>
 *   <li>atomically move it to {@code node.json}, never replacing;</li>
 *   <li>force the root directory.</li>
 * </ol>
 *
 * An existing {@code node.json} is never overwritten or re-created.
 */
public final class NodeIdentityFile {

    private NodeIdentityFile() {
    }

    /**
     * Must run only after startup classification decided this is a new
     * database, so a leftover temporary file from a crashed earlier attempt
     * is no longer evidence and may be removed first.
     *
     * @throws java.nio.file.FileAlreadyExistsException if {@code node.json}
     *         already exists; it is left untouched
     */
    public static void publish(DataLayout layout, NodeIdentity identity,
                               StorageIO io, OpContext ctx) throws IOException {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(io, "io");

        io.delete(layout.nodeTempFile());

        try (FileChannel channel = io.openNew(layout.nodeTempFile())) {
            io.writeFully(channel, ByteBuffer.wrap(identity.toJson()), null, ctx);
            io.force(channel, null, ctx);
        }

        io.atomicMoveNoReplace(layout.nodeTempFile(), layout.nodeFile(), null, ctx);
        io.fsyncDirectory(layout.root(), null, ctx);
    }
}
