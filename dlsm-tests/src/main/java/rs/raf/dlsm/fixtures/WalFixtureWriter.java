package rs.raf.dlsm.fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

public class WalFixtureWriter {

    public static void writeValidRecord(Path walFile, long sequence, byte kind, String key, byte[] value) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int k = keyBytes.length;
        int v = value != null ? value.length : 0;
        int payloadLen = 1 + 8 + 4 + k + 4 + v; // kind(1) + sequence(8) + k(4) + key + v(4) + value
        int totalFrameLength = 4 + 4 + payloadLen; // totalLength(4) + crc(4) + payload

        ByteBuffer buf = ByteBuffer.allocate(4 + totalFrameLength);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putInt(totalFrameLength);

        int crcPos = buf.position();
        buf.putInt(0); // privremeni CRC, računa se naknadno

        buf.put(kind);
        buf.putLong(sequence);
        buf.putInt(k);
        buf.put(keyBytes);
        buf.putInt(v);
        if (v > 0) {
            buf.put(value);
        }

        // Izračunavanje CRC32C preko payload-a
        CRC32C crc32c = new CRC32C();
        buf.flip();
        buf.position(crcPos + 4);
        byte[] crcData = new byte[buf.remaining()];
        buf.get(crcData);
        crc32c.update(crcData);
        int checksum = (int) crc32c.getValue();

        buf.putInt(crcPos, checksum);
        buf.position(buf.limit());
        buf.flip();

        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
    }
}
