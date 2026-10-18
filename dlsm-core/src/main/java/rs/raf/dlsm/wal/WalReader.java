package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.Bin;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /**
     * Reads the whole segment and classifies it as {@code VALID},
     * {@code TORN_TAIL} or {@code CORRUPT}, following the steps of
     * FORMAT.md §3.4 for every frame. Stops at the first failed step and
     * never interprets bytes after it.
     *
     * @throws IllegalArgumentException when the file name is not {@code wal-<id>.log}
     */
    public static WalScanResult scanSegment(Path file) throws IOException {
        long segmentId = segmentIdOf(file);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();

            try {
                WalCodec.validateSegmentHeader(
                        readUpTo(channel, 0, WalFormat.SEGMENT_HEADER_BYTES), segmentId);
            } catch (StorageException e) {
                return WalScanResult.corrupt(segmentId, List.of(), 0, size,
                        corruptionAt(0, e));
            }

            List<WalScanResult.Entry> entries = new ArrayList<>();
            long position = WalFormat.SEGMENT_HEADER_BYTES;

            while (position < size) {
                // Step 1: EOF cuts the guarded frame header.
                if (size - position < WalFormat.FRAME_GUARD_BYTES) {
                    return WalScanResult.tornTail(segmentId, entries, position, size);
                }

                // Steps 2-3: complement and bounds, before EOF is consulted.
                int total;
                try {
                    total = WalCodec.checkFrameGuard(
                            readExactly(channel, position, WalFormat.FRAME_GUARD_BYTES), 0);
                } catch (StorageException e) {
                    return WalScanResult.corrupt(segmentId, entries, position, size,
                            corruptionAt(position, e));
                }

                // Step 4: a valid guarded header declares a frame past EOF.
                if (!Bin.fitsWithin(position, total, size)) {
                    return WalScanResult.tornTail(segmentId, entries, position, size);
                }

                // Steps 5-8 on the complete frame; allocation is now bounded.
                InternalRecord record;
                try {
                    record = WalCodec.decodeFrame(readExactly(channel, position, total));
                } catch (StorageException e) {
                    return WalScanResult.corrupt(segmentId, entries, position, size,
                            corruptionAt(position, e));
                }

                entries.add(new WalScanResult.Entry(position, total, record));
                position += total;
            }

            return WalScanResult.valid(segmentId, entries, size);
        }
    }

    private static WalScanResult.Corruption corruptionAt(long offset, StorageException e) {
        return new WalScanResult.Corruption(offset, e.category(), e.getMessage());
    }

    /**
     * Like {@link #readUpTo} but the bytes must exist: the scan checked them
     * against the size it observed, so a short read means the file changed
     * underneath it, which is an I/O problem and not a classification.
     */
    private static byte[] readExactly(FileChannel channel, long position, int length)
            throws IOException {

        byte[] bytes = readUpTo(channel, position, length);
        if (bytes.length != length) {
            throw new IOException("WAL segment shrank during scan at offset " + position);
        }
        return bytes;
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
