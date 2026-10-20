package rs.raf.dlsm.fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class CorruptionTools {

    public static void truncateFile(Path file, long targetSize) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.truncate(targetSize);
            channel.force(true);
        }
    }

    public static void corruptByte(Path file, long offset, byte badByte) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.position(offset);
            ByteBuffer buf = ByteBuffer.allocate(1);
            buf.put(badByte);
            buf.flip();
            channel.write(buf);
            channel.force(true);
        }
    }
}
