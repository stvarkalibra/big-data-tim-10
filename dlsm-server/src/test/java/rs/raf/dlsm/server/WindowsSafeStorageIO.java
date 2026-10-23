package rs.raf.dlsm.server;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.io.StorageIO;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Test {@link StorageIO} for nodes in this module: every call goes through
 * {@link DefaultStorageIO}, so failpoints behave as in production.
 *
 * <p>Windows cannot open a directory as a file channel, so there only the
 * real directory force is skipped; its failpoint still fires and a path that
 * is not a directory still fails. Linux runs it for real. (Same as
 * {@code wal.RecordingStorageIO} in {@code dlsm-core}, whose test classes
 * this module cannot see.)
 */
final class WindowsSafeStorageIO implements StorageIO {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private final DefaultStorageIO delegate;
    private final Failpoints failpoints;

    WindowsSafeStorageIO(Failpoints failpoints) {
        this.delegate = new DefaultStorageIO(failpoints);
        this.failpoints = failpoints;
    }

    @Override
    public FileChannel openNew(Path p) throws IOException {
        return delegate.openNew(p);
    }

    @Override
    public void writeFully(FileChannel ch, ByteBuffer buf, String fpBefore, OpContext ctx)
            throws IOException {
        delegate.writeFully(ch, buf, fpBefore, ctx);
    }

    @Override
    public void force(FileChannel ch, String fpBefore, OpContext ctx) throws IOException {
        delegate.force(ch, fpBefore, ctx);
    }

    @Override
    public void atomicMoveNoReplace(Path from, Path to, String fpBefore, OpContext ctx)
            throws IOException {
        delegate.atomicMoveNoReplace(from, to, fpBefore, ctx);
    }

    @Override
    public void atomicReplace(Path from, Path to, String fpBefore, OpContext ctx)
            throws IOException {
        delegate.atomicReplace(from, to, fpBefore, ctx);
    }

    @Override
    public void fsyncDirectory(Path dir, String fpBefore, OpContext ctx) throws IOException {
        if (WINDOWS) {
            if (fpBefore != null) {
                failpoints.hit(fpBefore, ctx);
            }
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Not a directory: " + dir);
            }
            return;
        }
        delegate.fsyncDirectory(dir, fpBefore, ctx);
    }

    @Override
    public void truncate(FileChannel ch, long size) throws IOException {
        delegate.truncate(ch, size);
    }

    @Override
    public void delete(Path p) throws IOException {
        delegate.delete(p);
    }
}
