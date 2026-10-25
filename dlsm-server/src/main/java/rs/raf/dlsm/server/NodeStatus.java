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
 * the flush and compaction worker. Only the WAL segments are still derived
 * from the engine's segment ids, with sizes unknown, until the WAL module
 * reports them.
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
    private final ThreadLocal<EngineStatus> taken = new ThreadLocal<>();

    /**
     * @param manifests   the currently published manifest
     * @param maintenance the progress of the flush and compaction worker
     */
    NodeStatus(String nodeId, Engine engine, StorageStats stats,
               Supplier<Manifest> manifests, MaintenanceProgress maintenance) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    /**
     * One internally consistent snapshot (CP1 §24). A flush or compaction
     * publishes the manifest and installs the matching view under the
     * engine write lock, while {@link Engine#status} reads under the read
     * lock; so when the manifest generation is the same before and after
     * the engine read, both describe the same moment.
     */
    StatusBuilder.Snapshot snapshot() {
        EngineStatus status;
        Manifest manifest;
        int attempt = 0;
        do {
            Manifest before = manifests.get();
            status = engineStatus();
            manifest = manifests.get();
            if (before.generation() == manifest.generation()) {
                break;
            }
            // A publication fell between the two reads: read both again.
        } while (++attempt < CONSISTENT_READ_ATTEMPTS);

        taken.set(status);
        return status.toSnapshot(nodeId, walSegments(status), manifest(manifest),
                maintenance.snapshot(), stats.snapshot());
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
     * Placeholder until the WAL module reports real segments: the active
     * segment and those protecting the frozen generation, sizes unknown.
     */
    private static List<StatusBuilder.WalSegment> walSegments(EngineStatus status) {
        List<StatusBuilder.WalSegment> segments = new ArrayList<>();

        EngineStatus.Memtable frozen = status.immutableMemtable();
        if (frozen != null) {
            boolean single = frozen.walSegmentIds().size() == 1;
            for (long id : frozen.walSegmentIds()) {
                segments.add(new StatusBuilder.WalSegment(id, "IMMUTABLE",
                        single ? frozen.minSequence() : null,
                        single ? frozen.maxSequence() : null, 0));
            }
        }

        EngineStatus.Memtable active = status.activeMemtable();
        segments.add(new StatusBuilder.WalSegment(active.walSegmentId(), "ACTIVE",
                active.minSequence(), active.maxSequence(), 0));
        return segments;
    }

    private static StatusBuilder.Manifest manifest(Manifest manifest) {
        return new StatusBuilder.Manifest(manifest.generation(), manifest.nextTableId(),
                manifest.flushedThroughSequence());
    }
}
