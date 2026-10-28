package rs.raf.dlsm.fixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * Writes WAL segments byte by byte from {@code FORMAT.md} §3.2–§3.3 alone,
 * with no production code (plan §6.5, §14). A volume prepared with it checks
 * the production reader and recovery against the documented format, not
 * against themselves (CP1-WAL-007).
 *
 * <p>Every frame it writes is valid on its own. A log that breaks a rule
 * across frames, e.g. a sequence that does not increase, is built from such
 * frames; damaged bytes come from {@link CorruptionTools}.
 */
public final class WalFixtureWriter {

    /** FORMAT.md §3.2: {@code LSMWAL01}. */
    private static final byte[] MAGIC = "LSMWAL01".getBytes(StandardCharsets.US_ASCII);
    private static final int SEGMENT_HEADER_BYTES = 24;
    private static final int FILE_FORMAT_VERSION = 1;
    /** FORMAT.md §3.3: a frame is {@code 30 + k + v} bytes. */
    private static final int FRAME_OVERHEAD_BYTES = 30;
    private static final int RECORD_VERSION = 1;
    private static final byte KIND_PUT = 1;
    private static final byte KIND_DELETE = 2;
    /** FORMAT.md §1: 1..128 ASCII bytes from {@code [A-Za-z0-9._~-]}. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final int MAX_VALUE_BYTES = 262_144;

    private WalFixtureWriter() {
    }

    /**
     * One mutation as a WAL frame: a {@code PUT} with its value (empty is
     * allowed), or a {@code DELETE} with {@code value == null}.
     */
    public record Mutation(long sequence, String key, byte[] value) {

        public Mutation {
            Objects.requireNonNull(key, "key");
            if (sequence < 1 || !KEY.matcher(key).matches()
                    || (value != null && value.length > MAX_VALUE_BYTES)) {
                throw new IllegalArgumentException("Not a valid WAL frame: sequence " + sequence
                        + ", key " + key + ", " + (value == null ? "DELETE" : value.length + " value bytes"));
            }
        }

        public static Mutation put(long sequence, String key, byte[] value) {
            return new Mutation(sequence, key, Objects.requireNonNull(value, "value"));
        }

        public static Mutation delete(long sequence, String key) {
            return new Mutation(sequence, key, null);
        }
    }

    /** FORMAT.md §3.2: the 24-byte header of segment {@code segmentId}. */
    public static byte[] segmentHeader(long segmentId) {
        ByteBuffer header = ByteBuffer.allocate(SEGMENT_HEADER_BYTES);    // big-endian
        header.put(MAGIC);
        header.putShort((short) FILE_FORMAT_VERSION);
        header.putShort((short) 0);                                      // reserved
        header.putLong(segmentId);
        header.putInt(crc32c(header.array(), 0, 20));                    // over bytes 0..19
        return header.array();
    }

    /** FORMAT.md §3.3: one complete frame. */
    public static byte[] frame(Mutation mutation) {
        byte[] key = mutation.key().getBytes(StandardCharsets.US_ASCII);
        byte[] value = mutation.value() == null ? new byte[0] : mutation.value();
        int length = FRAME_OVERHEAD_BYTES + key.length + value.length;

        ByteBuffer frame = ByteBuffer.allocate(length);                  // big-endian
        frame.putInt(length);
        frame.putInt(~length);                                           // complement
        frame.putShort((short) RECORD_VERSION);
        frame.put(mutation.value() == null ? KIND_DELETE : KIND_PUT);
        frame.put((byte) 0);                                             // reserved
        frame.putLong(mutation.sequence());
        frame.putShort((short) key.length);
        frame.putInt(value.length);
        frame.put(key);
        frame.put(value);
        frame.putInt(crc32c(frame.array(), 8, length - 12));             // over bytes 8..25+k+v
        return frame.array();
    }

    /** The header of {@code segmentId} followed by one frame per mutation, in order. */
    public static byte[] segment(long segmentId, Mutation... mutations) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(segmentHeader(segmentId));
        for (Mutation mutation : mutations) {
            out.writeBytes(frame(mutation));
        }
        return out.toByteArray();
    }

    /**
     * Writes {@code wal-<20 digits>.log} in {@code walDir} and forces it
     * (CP1-WAL-007: every prepared file is forced). An existing file is never
     * replaced.
     *
     * @return the written file
     */
    public static Path writeSegment(Path walDir, long segmentId, Mutation... mutations)
            throws IOException {
        Path file = walDir.resolve(String.format("wal-%020d.log", segmentId));
        ByteBuffer bytes = ByteBuffer.wrap(segment(segmentId, mutations));
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        return file;
    }

    private static int crc32c(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }
}
