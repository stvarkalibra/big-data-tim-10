package rs.raf.dlsm.server;

import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineStatus;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.maintenance.MaintenanceProgress;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.stats.StatusBuilder;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.WalCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Feeds the storage status routes of the standalone node (CP1 §24).
 *
 * <p>The routes ask separately for the snapshot and for the accepted and
 * durable sequences. One response must describe one moment, so
 * {@link #snapshot()} takes a single {@link Engine#status} and remembers it
 * for the current request thread; {@link #acceptedSequence()} and
 * {@link #durableSequence()} then read the same snapshot.
 *
 * <p>The manifest section is the published manifest that matches the
 * engine view in the same response, and maintenance is the running job of
 * the flush and compaction worker. The WAL segments are the WAL module's
 * catalog, read under the same engine read lock as the engine part.
 */
final class NodeStatus {

    /** Bounds the wait for the engine read lock; status has no request deadline. */
    private static final long STATUS_TIMEOUT_MS = 5000;
    /** Reads of the engine and the manifest tried before giving the latest pair. */
    private static final int CONSISTENT_READ_ATTEMPTS = 5;

    private final String nodeId;
    private final Engine engine;
    private final StorageStats stats;
    private final Supplier<Manifest> manifests;
    private final MaintenanceProgress maintenance;
    private final WalCatalog walCatalog;
    private final ThreadLocal<EngineStatus> taken = new ThreadLocal<>();

    /** The engine part and the WAL segments, read under one engine read lock. */
    private record EnginePart(EngineStatus status, List<WalCatalog.Segment> walSegments) {
    }

    /**
     * @param manifests   the currently published manifest
     * @param maintenance the progress of the flush and compaction worker
     * @param walCatalog  the WAL segments on disk, from recovery
     */
    NodeStatus(String nodeId, Engine engine, StorageStats stats,
               Supplier<Manifest> manifests, MaintenanceProgress maintenance,
               WalCatalog walCatalog) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.walCatalog = Objects.requireNonNull(walCatalog, "walCatalog");
    }

    /**
     * One internally consistent snapshot (CP1 §24). A flush or compaction
     * publishes the manifest and installs the matching view under the
     * engine write lock, while {@link Engine#status} reads under the read
     * lock; so when the manifest generation is the same before and after
     * the engine read, both describe the same moment.
     */
    StatusBuilder.Snapshot snapshot() {
        EnginePart part;
        Manifest manifest;
        int attempt = 0;
        do {
            Manifest before = manifests.get();
            part = enginePart();
            manifest = manifests.get();
            if (before.generation() == manifest.generation()) {
                break;
            }
            // A publication fell between the two reads: read both again.
        } while (++attempt < CONSISTENT_READ_ATTEMPTS);

        taken.set(part.status());
        return part.status().toSnapshot(nodeId, walSegments(part.status(), part.walSegments()),
                manifest(manifest), maintenance.snapshot(), stats.snapshot());
    }

    long acceptedSequence() {
        return current().lastAcceptedSequence();
    }

    long durableSequence() {
        return current().lastDurableSequence();
    }

    /** The snapshot taken earlier in this request, or a fresh one. */
    private EngineStatus current() {
        EngineStatus status = taken.get();
        return status != null ? status : engineStatus();
    }

    private EngineStatus engineStatus() {
        try {
            return engine.status(OpContext.withTimeout(null, STATUS_TIMEOUT_MS));
        } catch (StorageException e) {
            // The server unwraps this and answers with the original code.
            throw new UncheckedStorageException(e);
        }
    }

    /**
     * The engine appends WAL frames under its write lock, so under the read
     * lock the catalog's sizes and sequences match the MemTables.
     */
    private EnginePart enginePart() {
        OpContext ctx = OpContext.withTimeout(null, STATUS_TIMEOUT_MS);
        try {
            return engine.underEngineReadLock(ctx,
                    () -> new EnginePart(engine.status(ctx), walCatalog.snapshot()));
        } catch (StorageException e) {
            throw new UncheckedStorageException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected status failure", e);
        }
    }

    /**
     * The {@code walSegments} array (CP1 §24): every segment the WAL module
     * has on disk, ascending by ID. The active segment takes new mutations
     * and the frozen generation's segments protect it; any other segment
     * protects nothing any more, e.g. one whose deletion after a published
     * flush is pending or failed, so it is {@code COVERED_PENDING_DELETE}.
     */
    static List<StatusBuilder.WalSegment> walSegments(EngineStatus status,
                                                      List<WalCatalog.Segment> onDisk) {
        long active = status.activeMemtable().walSegmentId();
        EngineStatus.Memtable frozen = status.immutableMemtable();
        List<Long> protecting = frozen == null ? List.of() : frozen.walSegmentIds();

        List<StatusBuilder.WalSegment> segments = new ArrayList<>();
        for (WalCatalog.Segment segment : onDisk) {
            String role = segment.segmentId() == active ? "ACTIVE"
                    : protecting.contains(segment.segmentId()) ? "IMMUTABLE"
                    : "COVERED_PENDING_DELETE";
            segments.add(new StatusBuilder.WalSegment(segment.segmentId(), role,
                    segment.minSequence(), segment.maxSequence(), segment.sizeBytes()));
        }
        return segments;
    }

    private static StatusBuilder.Manifest manifest(Manifest manifest) {
        return new StatusBuilder.Manifest(manifest.generation(), manifest.nextTableId(),
                manifest.flushedThroughSequence());
    }
}
