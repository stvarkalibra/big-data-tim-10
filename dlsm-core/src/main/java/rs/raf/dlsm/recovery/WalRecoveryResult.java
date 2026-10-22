package rs.raf.dlsm.recovery;

import rs.raf.dlsm.engine.ImmutableMemTable;
import rs.raf.dlsm.wal.WalLogScan;
import rs.raf.dlsm.wal.WalSegment;
import rs.raf.dlsm.wal.WalSegments;

import java.util.Objects;

/**
 * What the WAL part of recovery hands on (CP1 §21.1 steps 10–12). Together
 * with the validated tables of the manifest it becomes the engine's
 * {@code RecoveredState}.
 *
 * @param lastUsedSequence    {@code max(flushedThroughSequence, greatest valid
 *                            WAL sequence)}; the next mutation gets this + 1
 * @param recoveredGeneration records above the high-water mark, frozen as
 *                            generation 1, or {@code null} if there are none
 * @param activeLog           the new, durable, empty active segment
 * @param walFactory          creates later segments on rotation
 * @param scan                the log as read, after any torn tail was cut
 */
public record WalRecoveryResult(
        long lastUsedSequence,
        ImmutableMemTable recoveredGeneration,
        WalSegment activeLog,
        WalSegments walFactory,
        WalLogScan scan) {

    public WalRecoveryResult {
        if (lastUsedSequence < 0) {
            throw new IllegalArgumentException("lastUsedSequence must not be negative");
        }
        Objects.requireNonNull(activeLog, "activeLog");
        Objects.requireNonNull(walFactory, "walFactory");
        Objects.requireNonNull(scan, "scan");
    }
}
