package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The engine's part of the storage status (CP1 §24), taken as one
 * snapshot under the engine read lock so the fields agree with each other.
 * WAL segment details, manifest, maintenance and counters come from their
 * own modules.
 *
 * @param nextSequence         next sequence to assign; null once exhausted
 * @param lastAcceptedSequence greatest sequence visible in the engine; 0 if none
 * @param lastDurableSequence  greatest sequence in a forced WAL record or a
 *                             published table; 0 if none
 * @param immutableMemtable    the frozen generation, or null
 * @param lastFatalError       why the engine is FAILED, or null
 * @param sstables             published tables, ascending by table id
 */
public record EngineStatus(
        Lifecycle lifecycle,
        EngineMode engineMode,
        boolean writeBackpressure,
        int backpressuredWriters,
        FatalError lastFatalError,
        Long nextSequence,
        long lastAcceptedSequence,
        long lastDurableSequence,
        Memtable activeMemtable,
        Memtable immutableMemtable,
        List<SSTableMeta> sstables) {

    public EngineStatus {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(engineMode, "engineMode");
        Objects.requireNonNull(activeMemtable, "activeMemtable");
        sstables = sstables.stream()
                .sorted(Comparator.comparingLong(SSTableMeta::tableId))
                .toList();
    }

    /**
     * One MemTable generation. {@code walSegmentId} and
     * {@code rotationRequired} are set only for the active generation,
     * {@code walSegmentIds} only for the frozen one. Sequences are null for
     * an empty generation.
     */
    public record Memtable(
            long generation,
            Long walSegmentId,
            List<Long> walSegmentIds,
            int entryCount,
            long logicalBytes,
            Long minSequence,
            Long maxSequence,
            Boolean rotationRequired) {

        public Memtable {
            walSegmentIds = walSegmentIds == null ? null : List.copyOf(walSegmentIds);
        }
    }

    /**
     * {@code lastFatalError} of the status: {@code code} is the stable
     * diagnostic category (e.g. {@code WAL_FRAME}) or, without one, the
     * error code name (plan §18.3).
     */
    public record FatalError(String code, String message) {

        public static FatalError of(StorageException error) {
            String code = error.category() != null ? error.category() : error.code().name();
            return new FatalError(code, error.getMessage());
        }
    }
}
