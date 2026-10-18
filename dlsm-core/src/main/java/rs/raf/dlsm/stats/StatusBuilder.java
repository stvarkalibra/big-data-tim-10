
package rs.raf.dlsm.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class StatusBuilder {

    private static final Set<String> REQUIRED_STATS = Set.of(
            "resetEpoch",
            "walAppendAttempts",
            "walAppendsCompleted",
            "walForceAttempts",
            "walForcesSucceeded",
            "walBytesWritten",
            "flushAttempts",
            "flushPublications",
            "compactionAttempts",
            "compactionPublications",
            "sstableDataBlocksRead",
            "sstableDataBytesRead",
            "bloomNegativeSkips",
            "bloomPositiveProbes",
            "rangeScanBlocksRead",
            "orphanFilesRemoved",
            "temporaryFilesRemoved",
            "walRecordsReplayed",
            "walTornTailsTruncated"
    );

    private static final Set<String> MODES = Set.of(
            "RECOVERING", "SERVING", "BACKPRESSURED", "FAILED"
    );

    private static final Set<String> OPERATIONS =
            Set.of("NONE", "FLUSH", "COMPACTION");

    private static final Set<String> PHASES = Set.of(
            "IDLE", "CAPTURE", "WRITE_OUTPUT", "SYNC_OUTPUT",
            "FINALIZE_OUTPUT", "PUBLISH_MANIFEST",
            "RETIRE_INPUTS", "CLEANUP"
    );

    private StatusBuilder() {
    }

    public record Memtable(
            long generation,
            Long walSegmentId,
            List<Long> walSegmentIds,
            long entryCount,
            long logicalBytes,
            Long minSequence,
            Long maxSequence,
            Boolean rotationRequired
    ) {
        public Memtable {
            if (generation < 0 || entryCount < 0 || logicalBytes < 0) {
                throw new IllegalArgumentException("Invalid memtable");
            }
            if ((minSequence == null) != (maxSequence == null)) {
                throw new IllegalArgumentException("Invalid sequence range");
            }
            if (minSequence != null &&
                    (minSequence <= 0 || maxSequence < minSequence)) {
                throw new IllegalArgumentException("Invalid sequence range");
            }
            if (walSegmentIds != null) {
                walSegmentIds = List.copyOf(walSegmentIds);
            }
        }
    }

    public record WalSegment(
            long segmentId,
            String role,
            Long minSequence,
            Long maxSequence,
            long sizeBytes
    ) {
        public WalSegment {
            if (segmentId < 0 || sizeBytes < 0 ||
                    !Set.of("ACTIVE", "IMMUTABLE",
                            "COVERED_PENDING_DELETE").contains(role)) {
                throw new IllegalArgumentException("Invalid WAL segment");
            }
            if ((minSequence == null) != (maxSequence == null)) {
                throw new IllegalArgumentException("Invalid WAL range");
            }
        }
    }

    public record Table(
            long tableId,
            String file,
            String minKey,
            String maxKey,
            long minSequence,
            long maxSequence,
            long entryCount,
            long sizeBytes
    ) {
        public Table {
            if (tableId <= 0 || file == null || file.isBlank()
                    || minKey == null || maxKey == null
                    || minKey.compareTo(maxKey) > 0
                    || minSequence <= 0 || maxSequence < minSequence
                    || entryCount <= 0 || sizeBytes <= 0) {
                throw new IllegalArgumentException("Invalid SSTable");
            }
        }
    }

    public record Manifest(
            long generation,
            long nextTableId,
            long flushedThroughSequence
    ) {
        public Manifest {
            if (generation < 0 || nextTableId <= 0 ||
                    flushedThroughSequence < 0) {
                throw new IllegalArgumentException("Invalid manifest");
            }
        }
    }

    public record Maintenance(
            String operation,
            String phase,
            Long targetSequence,
            List<Long> inputTableIds
    ) {
        public Maintenance {
            if (!OPERATIONS.contains(operation) ||
                    !PHASES.contains(phase) ||
                    inputTableIds == null) {
                throw new IllegalArgumentException("Invalid maintenance");
            }
            inputTableIds = List.copyOf(inputTableIds);
        }
    }

    public record FatalError(String code, String message) {
        public FatalError {
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
        }
    }

    public record Snapshot(
            String nodeId,
            String lifecycle,
            String engineMode,
            boolean writeBackpressure,
            long backpressuredWriters,
            FatalError lastFatalError,
            Long nextSequence,
            Memtable activeMemtable,
            Memtable immutableMemtable,
            List<WalSegment> walSegments,
            Manifest manifest,
            List<Table> sstables,
            Maintenance maintenance,
            Map<String, Long> stats
    ) {
        public Snapshot {
            if (nodeId == null || nodeId.isBlank()
                    || lifecycle == null || lifecycle.isBlank()
                    || !MODES.contains(engineMode)
                    || backpressuredWriters < 0
                    || nextSequence != null && nextSequence <= 0) {
                throw new IllegalArgumentException("Invalid engine state");
            }

            Objects.requireNonNull(activeMemtable);
            Objects.requireNonNull(walSegments);
            Objects.requireNonNull(manifest);
            Objects.requireNonNull(sstables);
            Objects.requireNonNull(maintenance);
            Objects.requireNonNull(stats);

            walSegments = List.copyOf(walSegments);
            sstables = List.copyOf(sstables);
            stats = Map.copyOf(stats);

            if (!stats.keySet().containsAll(REQUIRED_STATS)) {
                throw new IllegalArgumentException(
                        "Missing required statistics: " +
                                difference(REQUIRED_STATS, stats.keySet()));
            }

            for (var entry : stats.entrySet()) {
                if (entry.getValue() == null || entry.getValue() < 0) {
                    throw new IllegalArgumentException(
                            "Invalid counter: " + entry.getKey());
                }
            }

            Set<Long> ids = new LinkedHashSet<>();
            for (Table table : sstables) {
                if (!ids.add(table.tableId())) {
                    throw new IllegalArgumentException(
                            "Duplicate table ID");
                }
            }

            ids.clear();
            for (WalSegment wal : walSegments) {
                if (!ids.add(wal.segmentId())) {
                    throw new IllegalArgumentException(
                            "Duplicate WAL segment ID");
                }
            }
        }
    }

    public static Map<String, Object> build(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);

        Map<String, Object> result = map();
        result.put("protocolVersion", 1);
        result.put("nodeId", snapshot.nodeId());
        result.put("lifecycle", snapshot.lifecycle());
        result.put("engineMode", snapshot.engineMode());
        result.put("writeBackpressure", snapshot.writeBackpressure());
        result.put("backpressuredWriters",
                snapshot.backpressuredWriters());

        FatalError error = snapshot.lastFatalError();
        result.put("lastFatalError", error == null ? null :
                Map.of("code", error.code(),
                        "message", error.message()));

        result.put("nextSequence", snapshot.nextSequence());
        result.put("activeMemtable",
                memtable(snapshot.activeMemtable(), true));
        result.put("immutableMemtable",
                snapshot.immutableMemtable() == null ? null :
                        memtable(snapshot.immutableMemtable(), false));

        List<WalSegment> walSegments = new ArrayList<>(
                snapshot.walSegments());
        walSegments.sort(Comparator.comparingLong(
                WalSegment::segmentId));

        List<Map<String, Object>> walJson = new ArrayList<>();
        for (WalSegment wal : walSegments) {
            Map<String, Object> item = map();
            item.put("segmentId", wal.segmentId());
            item.put("role", wal.role());
            item.put("minSequence", wal.minSequence());
            item.put("maxSequence", wal.maxSequence());
            item.put("sizeBytes", wal.sizeBytes());
            walJson.add(item);
        }
        result.put("walSegments", walJson);

        List<Table> tables = new ArrayList<>(snapshot.sstables());
        tables.sort(Comparator.comparingLong(Table::tableId));

        Map<String, Object> manifest = map();
        manifest.put("generation", snapshot.manifest().generation());
        manifest.put("nextTableId", snapshot.manifest().nextTableId());
        manifest.put("flushedThroughSequence",
                snapshot.manifest().flushedThroughSequence());
        manifest.put("sstableCount", tables.size());
        result.put("manifest", manifest);

        List<Map<String, Object>> tableJson = new ArrayList<>();
        for (Table table : tables) {
            Map<String, Object> item = map();
            item.put("tableId", table.tableId());
            item.put("file", table.file());
            item.put("minKey", table.minKey());
            item.put("maxKey", table.maxKey());
            item.put("minSequence", table.minSequence());
            item.put("maxSequence", table.maxSequence());
            item.put("entryCount", table.entryCount());
            item.put("sizeBytes", table.sizeBytes());
            tableJson.add(item);
        }
        result.put("sstables", tableJson);

        Maintenance job = snapshot.maintenance();
        Map<String, Object> maintenance = map();
        maintenance.put("operation", job.operation());
        maintenance.put("phase", job.phase());
        maintenance.put("targetSequence", job.targetSequence());

        List<Long> inputIds = new ArrayList<>(job.inputTableIds());
        inputIds.sort(Long::compareTo);
        maintenance.put("inputTableIds", inputIds);
        result.put("maintenance", maintenance);

        Map<String, Object> counters = map();
        snapshot.stats().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry ->
                        counters.put(entry.getKey(), entry.getValue()));
        result.put("stats", counters);

        return result;
    }

    private static Map<String, Object> memtable(
            Memtable memtable, boolean active) {

        Map<String, Object> result = map();
        result.put("generation", memtable.generation());

        if (active) {
            result.put("walSegmentId", memtable.walSegmentId());
        }

        result.put("entryCount", memtable.entryCount());
        result.put("logicalBytes", memtable.logicalBytes());
        result.put("minSequence", memtable.minSequence());
        result.put("maxSequence", memtable.maxSequence());

        if (active) {
            result.put("rotationRequired", memtable.rotationRequired());
        } else {
            List<Long> segments = new ArrayList<>(
                    memtable.walSegmentIds() == null
                            ? List.of()
                            : memtable.walSegmentIds());
            segments.sort(Long::compareTo);
            result.put("walSegmentIds", segments);
        }

        return result;
    }

    private static Set<String> difference(
            Set<String> required, Set<String> actual) {
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        return missing;
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }
}
