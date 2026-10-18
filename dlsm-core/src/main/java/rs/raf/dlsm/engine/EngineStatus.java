package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.stats.StatusBuilder;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
     * Combines this engine part with the other modules' parts into the
     * status builder's snapshot for {@code GET /admin/v1/storage/status};
     * {@code StandaloneStatusBuilder.build(snapshot, lastAcceptedSequence(),
     * lastDurableSequence())} then gives {@code GET /admin/v1/status}.
     *
     * <p>For a consistent response, read the other parts and call
     * {@code Engine.status} inside one {@code Engine.underEngineReadLock}.
     *
     * @param walSegments segment details from the WAL module
     * @param manifest    the current manifest from the maintenance module
     * @param maintenance the current maintenance job, or idle
     * @param stats       {@code StorageStats.snapshot()}
     */
    public StatusBuilder.Snapshot toSnapshot(
            String nodeId,
            List<StatusBuilder.WalSegment> walSegments,
            StatusBuilder.Manifest manifest,
            StatusBuilder.Maintenance maintenance,
            Map<String, Long> stats) {

        List<StatusBuilder.Table> tables = sstables.stream()
                .map(t -> new StatusBuilder.Table(t.tableId(), t.file(), t.minKey(),
                        t.maxKey(), t.minSequence(), t.maxSequence(),
                        t.entryCount(), t.sizeBytes()))
                .toList();

        return new StatusBuilder.Snapshot(
                nodeId,
                lifecycle.name(),
                engineMode.name(),
                writeBackpressure,
                backpressuredWriters,
                lastFatalError == null ? null
                        : new StatusBuilder.FatalError(lastFatalError.code(),
                                lastFatalError.message()),
                nextSequence,
                activeMemtable.toStatus(),
                immutableMemtable == null ? null : immutableMemtable.toStatus(),
                walSegments,
                manifest,
                tables,
                maintenance,
                stats);
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

        StatusBuilder.Memtable toStatus() {
            return new StatusBuilder.Memtable(generation, walSegmentId, walSegmentIds,
                    entryCount, logicalBytes, minSequence, maxSequence, rotationRequired);
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
