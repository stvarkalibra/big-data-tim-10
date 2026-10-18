package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.InternalRecord;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Outcome of reading one WAL segment (FORMAT.md §3.4).
 *
 * <ul>
 *   <li>{@code VALID}: header and every frame are valid and the last frame
 *       ends exactly at EOF; {@code validLength == fileSize}.</li>
 *   <li>{@code TORN_TAIL}: every frame up to {@code validLength} is valid and
 *       the bytes after it are a strict prefix of one frame. Recovery may
 *       truncate the file to {@code validLength}.</li>
 *   <li>{@code CORRUPT}: the header or a complete frame failed a check;
 *       {@link #corruption()} gives the offset and category. Nothing after
 *       that offset was interpreted.</li>
 * </ul>
 *
 * @param segmentId   ID from the file name the segment was scanned under
 * @param entries     valid frames before the stop point, in file order
 * @param validLength end offset of the last valid frame, or of the header
 *                    when there is none; {@code 0} if the header is corrupt
 * @param fileSize    physical file size observed by the scan
 */
public record WalScanResult(
        long segmentId,
        Status status,
        List<Entry> entries,
        long validLength,
        long fileSize,
        Corruption corruption) {

    public enum Status { VALID, TORN_TAIL, CORRUPT }

    /** One valid frame: where it starts, how long it is and what it holds. */
    public record Entry(long offset, int length, InternalRecord record) {
        public Entry {
            Objects.requireNonNull(record, "record");
            if (offset < WalFormat.SEGMENT_HEADER_BYTES
                    || length < WalFormat.MIN_FRAME_BYTES
                    || length > WalFormat.MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("Invalid WAL entry position");
            }
        }
    }

    /** First failed check: file offset of the header or frame, and why. */
    public record Corruption(long offset, String category, String message) {
        public Corruption {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(message, "message");
            if (offset < 0) {
                throw new IllegalArgumentException("Negative corruption offset");
            }
        }
    }

    public WalScanResult {
        Objects.requireNonNull(status, "status");
        entries = List.copyOf(entries);

        if (segmentId <= 0 || validLength < 0 || validLength > fileSize) {
            throw new IllegalArgumentException("Invalid WAL scan bounds");
        }

        boolean consistent = switch (status) {
            case VALID -> corruption == null && validLength == fileSize;
            case TORN_TAIL -> corruption == null && validLength < fileSize;
            case CORRUPT -> corruption != null;
        };
        if (!consistent) {
            throw new IllegalArgumentException(
                    "Inconsistent WAL scan result for status " + status);
        }
    }

    public static WalScanResult valid(long segmentId, List<Entry> entries, long fileSize) {
        return new WalScanResult(segmentId, Status.VALID, entries, fileSize, fileSize, null);
    }

    public static WalScanResult tornTail(
            long segmentId, List<Entry> entries, long tornFrameOffset, long fileSize) {
        return new WalScanResult(segmentId, Status.TORN_TAIL, entries,
                tornFrameOffset, fileSize, null);
    }

    public static WalScanResult corrupt(long segmentId, List<Entry> entries,
            long validLength, long fileSize, Corruption corruption) {
        return new WalScanResult(segmentId, Status.CORRUPT, entries,
                validLength, fileSize, corruption);
    }

    /** Offset where the incomplete final frame starts; only for {@code TORN_TAIL}. */
    public long tornFrameOffset() {
        if (status != Status.TORN_TAIL) {
            throw new IllegalStateException("Not a torn tail: " + status);
        }
        return validLength;
    }

    public List<InternalRecord> records() {
        return entries.stream().map(Entry::record).toList();
    }

    /** Sequence of the last valid frame, empty when the segment has none. */
    public OptionalLong lastSequence() {
        return entries.isEmpty()
                ? OptionalLong.empty()
                : OptionalLong.of(entries.getLast().record().sequence());
    }
}
