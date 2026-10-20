package rs.raf.dlsm.io;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.Failpoints;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

public class DefaultStorageIO implements StorageIO {
    private final Failpoints failpoints;

    public DefaultStorageIO(Failpoints failpoints) {
        this.failpoints = failpoints;
    }

    @Override
    public FileChannel openNew(Path p) throws IOException {
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        return FileChannel.open(
                p,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ
        );
    }

    @Override
    public void writeFully(FileChannel ch, ByteBuffer buf, String fpBefore, OpContext ctx) throws IOException {
        if (fpBefore != null) {
            failpoints.hit(fpBefore, ctx);
        }
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    @Override
    public void force(FileChannel ch, String fpBefore, OpContext ctx) throws IOException {
        if (fpBefore != null) {
            failpoints.hit(fpBefore, ctx);
        }
        ch.force(true);
    }

    @Override
    public void atomicMoveNoReplace(Path from, Path to, String fpBefore, OpContext ctx) throws IOException {
        if (fpBefore != null) {
            failpoints.hit(fpBefore, ctx);
        }
        if (to.getParent() != null) {
            Files.createDirectories(to.getParent());
        }
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void atomicReplace(Path from, Path to, String fpBefore, OpContext ctx) throws IOException {
        if (fpBefore != null) {
            failpoints.hit(fpBefore, ctx);
        }
        if (to.getParent() != null) {
            Files.createDirectories(to.getParent());
        }
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void fsyncDirectory(Path dir, String fpBefore, OpContext ctx) throws IOException {
        if (fpBefore != null) {
            failpoints.hit(fpBefore, ctx);
        }
        Path targetDir = Files.isDirectory(dir) ? dir : dir.getParent();
        if (targetDir != null && Files.exists(targetDir)) {
            try (FileChannel channel = FileChannel.open(targetDir, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (UnsupportedOperationException e) {
                // Na nekim operativnim sistemima (npr. Windows za direktorijume) ovo može baciti izuzetak,
                // ali na Linuxu / Docker okruženju radi savršeno i obavezno je po specifikaciji.
            }
        }
    }

    @Override
    public void truncate(FileChannel ch, long size) throws IOException {
        ch.truncate(size);
    }

    @Override
    public void delete(Path p) throws IOException {
        Files.deleteIfExists(p);
    }
}
