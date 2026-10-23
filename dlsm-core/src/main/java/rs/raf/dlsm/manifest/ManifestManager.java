package rs.raf.dlsm.manifest;

import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.recovery.ManifestInitializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class ManifestManager implements ManifestInitializer {

    private final DataLayout layout;
    private final StorageIO io;
    private volatile Manifest current;

    public ManifestManager(DataLayout layout, StorageIO io) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.io = Objects.requireNonNull(io, "io");
    }

    public Manifest current() {
        Manifest manifest = current;
        if (manifest == null) {
            throw new IllegalStateException("No manifest is loaded or published");
        }
        return manifest;
    }

    public synchronized Manifest loadAndValidate() throws IOException, StorageException {
        Path file = layout.manifestFile();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw Manifest.invalid("MANIFEST.json is not a regular file", null);
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw Manifest.invalid("MANIFEST.json is missing", e);
        }

        Manifest manifest = Manifest.parse(bytes);
        current = manifest;
        return manifest;
    }

    @Override
    public synchronized void publishInitialManifest(OpContext ctx) throws IOException {
        if (current != null) {
            throw new IllegalStateException("A manifest is already loaded");
        }
        Manifest initial = Manifest.initial();
        write(initial, ctx);
        current = initial;
    }

    public synchronized Manifest publish(UnaryOperator<Manifest> change, OpContext ctx)
            throws IOException, StorageException {
        Objects.requireNonNull(change, "change");
        Manifest base = current();
        Manifest planned = Objects.requireNonNull(change.apply(base), "change result");
        Manifest next = new Manifest(planned.formatVersion(), base.generation() + 1,
                planned.nextTableId(), planned.flushedThroughSequence(), planned.sstables());
        publish(next, ctx);
        return next;
    }

    public synchronized void publish(Manifest next, OpContext ctx) throws IOException, StorageException {
        Objects.requireNonNull(next, "next");
        Manifest base = current();

        if (next.generation() != base.generation() + 1) {
            throw new IllegalArgumentException("Next manifest generation must be "
                    + (base.generation() + 1) + ", got " + next.generation());
        }
        if (next.nextTableId() < base.nextTableId()) {
            throw new IllegalArgumentException("nextTableId must not decrease: "
                    + base.nextTableId() + " -> " + next.nextTableId());
        }
        if (next.flushedThroughSequence() < base.flushedThroughSequence()) {
            throw new IllegalArgumentException("flushedThroughSequence must not decrease: "
                    + base.flushedThroughSequence() + " -> " + next.flushedThroughSequence());
        }
        ManifestValidator.validate(next);

        write(next, ctx);
        current = next;
    }

    static String tempFileName(long generation) {
        return "MANIFEST-%020d.tmp".formatted(generation);
    }

    private void write(Manifest next, OpContext ctx) throws IOException {
        Path temp = layout.tmpDir().resolve(tempFileName(next.generation()));

        try {
            io.delete(temp);
            try (FileChannel channel = io.openNew(temp)) {
                io.writeFully(channel, ByteBuffer.wrap(next.toJson()), null, ctx);
                io.force(channel, null, ctx);
            }
            io.atomicReplace(temp, layout.manifestFile(),
                    FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME, ctx);
        } catch (IOException e) {
            try {
                io.delete(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new ManifestPublishException(next.generation(), false, e);
        }

        try {
            io.fsyncDirectory(layout.lsmDir(),
                    FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, ctx);
        } catch (IOException e) {
            throw new ManifestPublishException(next.generation(), true, e);
        }
    }
}
