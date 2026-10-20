package rs.raf.dlsm.io;

import rs.raf.dlsm.engine.OpContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public interface StorageIO {
    FileChannel openNew(Path p) throws IOException;
    void writeFully(FileChannel ch, ByteBuffer buf, String fpBefore, OpContext ctx) throws IOException;
    void force(FileChannel ch, String fpBefore, OpContext ctx) throws IOException;
    void atomicMoveNoReplace(Path from, Path to, String fpBefore, OpContext ctx) throws IOException;
    void atomicReplace(Path from, Path to, String fpBefore, OpContext ctx) throws IOException;
    void fsyncDirectory(Path dir, String fpBefore, OpContext ctx) throws IOException;
    void truncate(FileChannel ch, long size) throws IOException;
    void delete(Path p) throws IOException;
}
