package rs.raf.dlsm.wal;

import java.util.List;
import java.util.TreeMap;

/**
 * The WAL segments that exist on disk while the node runs, for the
 * {@code walSegments} array of the storage status (CP1 §24): each segment's
 * file size and the sequences of its complete frames.
 *
 * <p>Recovery registers the segments it keeps, {@link WalSegments} every
 * segment it creates, {@link WalSegment} every write and complete frame,
 * and {@link WalSegments} every segment it deletes. The catalog never reads
 * the disk itself, and it knows nothing about roles: which segment is
 * active or protects a frozen generation is the engine's view, combined by
 * whoever builds the status.
 *
 * <p>The engine appends under its write lock, so a reader under the engine
 * read lock sees sizes and sequences that match the MemTables.
 */
public final class WalCatalog {

    /**
     * One segment as it is on disk.
     *
     * @param sizeBytes   bytes in the file: the header, every complete frame
     *                    and the prefix of a failed append
     * @param minSequence first complete frame, or {@code null} without one
     * @param maxSequence last complete frame, or {@code null} without one
     */
    public record Segment(long segmentId, long sizeBytes, Long minSequence, Long maxSequence) {

        public Segment {
            if (segmentId < 1) {
                throw new IllegalArgumentException("Segment ID must be positive: " + segmentId);
            }
            if (sizeBytes < WalFormat.SEGMENT_HEADER_BYTES) {
                throw new IllegalArgumentException("Segment " + segmentId
                        + " is shorter than its header: " + sizeBytes);
            }
            if ((minSequence == null) != (maxSequence == null)) {
                throw new IllegalArgumentException("Segment " + segmentId
                        + " needs both sequences or neither");
            }
            if (minSequence != null && (minSequence < 1 || minSequence > maxSequence)) {
                throw new IllegalArgumentException("Segment " + segmentId
                        + " has invalid sequences " + minSequence + ".." + maxSequence);
            }
        }
    }

    private final TreeMap<Long, Segment> segments = new TreeMap<>();

    /**
     * Adds a segment that is on disk: a new header-only segment, or one that
     * recovery keeps, with its length after any torn tail was cut.
     *
     * @throws IllegalStateException if the ID is already registered; a
     *                               segment ID is never used twice (CP1 §16.1)
     */
    public synchronized void register(Segment segment) {
        if (segments.putIfAbsent(segment.segmentId(), segment) != null) {
            throw new IllegalStateException("WAL segment " + segment.segmentId()
                    + " is already registered");
        }
    }

    /** The segment's file accepted {@code bytes} more, complete frame or not. */
    synchronized void written(long segmentId, long bytes) {
        Segment segment = require(segmentId);
        segments.put(segmentId, new Segment(segmentId, segment.sizeBytes() + bytes,
                segment.minSequence(), segment.maxSequence()));
    }

    /** A complete frame with {@code sequence}; frames arrive in sequence order (I3). */
    synchronized void framed(long segmentId, long sequence) {
        Segment segment = require(segmentId);
        Long min = segment.minSequence() == null ? sequence : segment.minSequence();
        segments.put(segmentId, new Segment(segmentId, segment.sizeBytes(), min, sequence));
    }

    /** The segment's file was deleted; an unknown ID is ignored. */
    synchronized void removed(long segmentId) {
        segments.remove(segmentId);
    }

    /** Every segment on disk, ascending by ID (CP1 §24). */
    public synchronized List<Segment> snapshot() {
        return List.copyOf(segments.values());
    }

    private Segment require(long segmentId) {
        Segment segment = segments.get(segmentId);
        if (segment == null) {
            throw new IllegalStateException("WAL segment " + segmentId + " is not registered");
        }
        return segment;
    }
}
