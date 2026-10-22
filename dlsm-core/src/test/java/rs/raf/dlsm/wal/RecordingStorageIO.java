package rs.raf.dlsm.wal;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.io.StorageIO;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test {@link StorageIO}: performs every call through
 * {@link DefaultStorageIO} (so failpoints behave as in production) and
 * records it as {@code "<call> <file name>"}, optionally failing one kind of
 * call with a plain {@link IOException} before it runs.
 *
 * <p>Windows cannot open a directory as a file channel, so there the real
 * directory force is skipped and only recorded; Linux runs it for real.
 */
public final class RecordingStorageIO implements StorageIO {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private final DefaultStorageIO delegate;
    private final List<String> calls = new ArrayList<>();
    private final List<Integer> writeSizes = new ArrayList<>();
    private String failingCall;

    public RecordingStorageIO(Failpoints failpoints) {
        this.delegate = new DefaultStorageIO(failpoints);
    }

    /** The next calls named {@code call} (e.g. {@code "force"}) throw before running. */
    public RecordingStorageIO failOn(String call) {
        this.failingCall = call;
        return this;
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    /** Byte count of each {@code writeFully} call, in order. */
    public List<Integer> writeSizes() {
        return List.copyOf(writeSizes);
    }

    public void clearCalls() {
        calls.clear();
        writeSizes.clear();
    }

    @Override
    public FileChannel openNew(Path p) throws IOException {
        record("openNew", p);
        return delegate.openNew(p);
    }

    @Override
    public void writeFully(FileChannel ch, ByteBuffer buf, String fpBefore, OpContext ctx)
            throws IOException {
        record("writeFully", null);
        writeSizes.add(buf.remaining());
        delegate.writeFully(ch, buf, fpBefore, ctx);
    }

    @Override
    public void force(FileChannel ch, String fpBefore, OpContext ctx) throws IOException {
        record("force", null);
        delegate.force(ch, fpBefore, ctx);
    }

    @Override
    public void atomicMoveNoReplace(Path from, Path to, String fpBefore, OpContext ctx)
            throws IOException {
        record("atomicMoveNoReplace", to);
        delegate.atomicMoveNoReplace(from, to, fpBefore, ctx);
    }

    @Override
    public void atomicReplace(Path from, Path to, String fpBefore, OpContext ctx)
            throws IOException {
        record("atomicReplace", to);
        delegate.atomicReplace(from, to, fpBefore, ctx);
    }

    @Override
    public void fsyncDirectory(Path dir, String fpBefore, OpContext ctx) throws IOException {
        record("fsyncDirectory", dir);
        if (WINDOWS) {
            return;
        }
        delegate.fsyncDirectory(dir, fpBefore, ctx);
    }

    @Override
    public void truncate(FileChannel ch, long size) throws IOException {
        record("truncate", null);
        delegate.truncate(ch, size);
    }

    @Override
    public void delete(Path p) throws IOException {
        record("delete", p);
        delegate.delete(p);
    }

    private void record(String call, Path path) throws IOException {
        calls.add(path == null ? call : call + " " + path.getFileName());
        if (call.equals(failingCall)) {
            throw new IOException("Test failure on " + call);
        }
    }
}
