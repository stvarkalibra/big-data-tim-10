package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.InternalRecord;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Outcome of reading every WAL segment in increasing ID order
 * (FORMAT.md §3.5). Scanning stops after the first {@code CORRUPT}
 * segment, so later segments are never interpreted.
 *
 * @param segments scanned segments in increasing ID order
 */
public record WalLogScan(List<WalScanResult> segments) {

    public WalLogScan {
        segments = List.copyOf(segments);

        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i - 1).segmentId() >= segments.get(i).segmentId()) {
                throw new IllegalArgumentException("Segments must be in increasing ID order");
            }
        }
    }

    /**
     * {@code CORRUPT} if any segment is corrupt, else {@code TORN_TAIL} if
     * one has a torn tail, else {@code VALID} (also for an empty log).
     */
    public WalScanResult.Status status() {
        if (corruptSegment().isPresent()) {
            return WalScanResult.Status.CORRUPT;
        }
        if (tornSegment().isPresent()) {
            return WalScanResult.Status.TORN_TAIL;
        }
        return WalScanResult.Status.VALID;
    }

    public Optional<WalScanResult> corruptSegment() {
        return segments.stream()
                .filter(s -> s.status() == WalScanResult.Status.CORRUPT)
                .findFirst();
    }

    public Optional<WalScanResult> tornSegment() {
        return segments.stream()
                .filter(s -> s.status() == WalScanResult.Status.TORN_TAIL)
                .findFirst();
    }

    /** Valid records of all scanned segments, in log order. */
    public List<InternalRecord> records() {
        return segments.stream()
                .flatMap(s -> s.records().stream())
                .toList();
    }

    /** Greatest valid sequence in the log, empty when there are no records. */
    public OptionalLong lastSequence() {
        OptionalLong last = OptionalLong.empty();
        for (WalScanResult segment : segments) {
            if (segment.lastSequence().isPresent()) {
                last = segment.lastSequence();
            }
        }
        return last;
    }

    public List<Long> segmentIds() {
        return segments.stream().map(WalScanResult::segmentId).toList();
    }
}
