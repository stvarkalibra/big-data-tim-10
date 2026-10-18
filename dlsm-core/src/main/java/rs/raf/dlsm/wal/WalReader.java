package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.Bin;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
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
        return scanSegment(file, 0);
    }

    /**
     * Same as {@link #scanSegment(Path)}, and every sequence must also be
     * strictly greater than the one before it, starting from
     * {@code afterSequence}: the last sequence of the previous segment that
     * has records, or {@code 0} for the first (FORMAT.md §3.5).
     */
    public static WalScanResult scanSegment(Path file, long afterSequence)
            throws IOException {

        if (afterSequence < 0) {
            throw new IllegalArgumentException(
                    "afterSequence must not be negative: " + afterSequence);
        }

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
            long previousSequence = afterSequence;

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

                // Step 9: strictly increasing, checked after the CRC so a damaged
                // sequence is reported as CHECKSUM. Never repaired by sorting.
                if (record.sequence() <= previousSequence) {
                    return WalScanResult.corrupt(segmentId, entries, position, size,
                            new WalScanResult.Corruption(position,
                                    WalFormat.CATEGORY_SEQUENCE_ORDER,
                                    "Sequence " + record.sequence()
                                            + " not greater than previous "
                                            + previousSequence));
                }

                entries.add(new WalScanResult.Entry(position, total, record));
                previousSequence = record.sequence();
                position += total;
            }

            return WalScanResult.valid(segmentId, entries, size);
        }
    }

    /**
     * Scans every final segment in {@code walDir}: files whose name is
     * {@code wal-<id>.log} with a positive ID. Other files are left to the
     * caller's directory classification (CP1 §21.1 step 5).
     */
    public static WalLogScan scanLog(Path walDir) throws IOException {
        List<Path> files = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDir)) {
            for (Path file : stream) {
                long id = WalFileNames.parseSegmentId(file.getFileName().toString()).orElse(0);
                if (id > 0 && Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        }

        return scanSegments(files);
    }

    /**
     * Scans {@code segmentFiles} in increasing segment ID, never in the
     * given or listing order, and requires sequences to grow strictly across
     * segments (FORMAT.md §3.5). Stops after the first corrupt segment.
     *
     * <p>A torn tail is accepted only as the last frame of the whole log:
     * every later segment must hold just its header. Otherwise the torn frame
     * is {@code WAL_FRAME} corruption, because no crash can append after an
     * interrupted append (FORMAT.md §3.4, docs/ODLUKE.md).
     */
    public static WalLogScan scanSegments(Collection<Path> segmentFiles) throws IOException {
        List<Path> ordered = new ArrayList<>(segmentFiles);
        ordered.sort(Comparator.comparingLong(WalReader::segmentIdOf));

        List<WalScanResult> results = new ArrayList<>();
        long afterSequence = 0;
        int tornIndex = -1;

        for (Path file : ordered) {
            WalScanResult result = scanSegment(file, afterSequence);

            if (tornIndex >= 0 && !isHeaderOnly(result)) {
                results.set(tornIndex, tornTailNotAtEnd(results.get(tornIndex), result));
                results.subList(tornIndex + 1, results.size()).clear();
                break;
            }

            results.add(result);

            if (result.status() == WalScanResult.Status.CORRUPT) {
                break;
            }
            if (result.status() == WalScanResult.Status.TORN_TAIL) {
                tornIndex = results.size() - 1;
            }
            if (result.lastSequence().isPresent()) {
                afterSequence = result.lastSequence().getAsLong();
            }
        }

        return new WalLogScan(results);
    }

    private static boolean isHeaderOnly(WalScanResult result) {
        return result.status() == WalScanResult.Status.VALID && result.entries().isEmpty();
    }

    private static WalScanResult tornTailNotAtEnd(WalScanResult torn, WalScanResult later) {
        long offset = torn.tornFrameOffset();

        return WalScanResult.corrupt(torn.segmentId(), torn.entries(), offset, torn.fileSize(),
                new WalScanResult.Corruption(offset, WalFormat.CATEGORY_FRAME,
                        "Incomplete frame is not at the end of the log: later segment "
                                + later.segmentId() + " holds more than its header"));
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
