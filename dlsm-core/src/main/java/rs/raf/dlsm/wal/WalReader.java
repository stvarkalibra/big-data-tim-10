package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Reads WAL segment files and classifies them per FORMAT.md §3.4.
 *
 * <p>Read-only: never truncates, repairs or writes. Reads do not go through
 * {@code StorageIO}, which covers only mutating calls (plan §5.1). A real
 * {@link IOException} is propagated, never swallowed or turned into a
 * classification.
 */
public final class WalReader {

    private WalReader() {
    }

    /**
     * Validates the header of {@code file} against the segment ID in its
     * file name (FORMAT.md §3.2) and returns that ID.
     *
     * @throws StorageException {@code WAL_HEADER} when the header is short or invalid
     * @throws IllegalArgumentException when the file name is not {@code wal-<id>.log}
     */
    public static long readSegmentHeader(Path file) throws IOException, StorageException {
        long expectedId = segmentIdOf(file);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            byte[] header = readUpTo(channel, 0, WalFormat.SEGMENT_HEADER_BYTES);
            WalCodec.validateSegmentHeader(header, expectedId);
        }

        return expectedId;
    }

    static long segmentIdOf(Path file) {
        String name = file.getFileName().toString();

        return WalFileNames.parseSegmentId(name)
                .stream()
                .filter(id -> id > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Not a WAL segment file name: " + name));
    }

    /**
     * Reads up to {@code length} bytes at {@code position}; the result is
     * shorter only when EOF comes first.
     */
    static byte[] readUpTo(FileChannel channel, long position, int length)
            throws IOException {

        ByteBuffer buffer = ByteBuffer.allocate(length);

        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                break;
            }
        }

        return buffer.hasRemaining()
                ? Arrays.copyOf(buffer.array(), buffer.position())
                : buffer.array();
    }
}
