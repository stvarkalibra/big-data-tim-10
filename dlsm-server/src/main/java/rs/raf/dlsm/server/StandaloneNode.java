package rs.raf.dlsm.server;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.EngineStatus;
import rs.raf.dlsm.engine.FlushScheduler;
import rs.raf.dlsm.engine.MaintenanceHost;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.StorageEngine;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.maintenance.CompactionJob;
import rs.raf.dlsm.maintenance.FlushJob;
import rs.raf.dlsm.maintenance.MaintenanceProgress;
import rs.raf.dlsm.maintenance.MaintenanceWorker;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.recovery.RecoveryProcedure;
import rs.raf.dlsm.recovery.RecoveryResult;
import rs.raf.dlsm.server.http.FailpointRoutes;
import rs.raf.dlsm.server.http.HealthRoutes;
import rs.raf.dlsm.server.http.HttpServerHost;
import rs.raf.dlsm.server.http.KvRoutes;
import rs.raf.dlsm.server.http.MaintenanceRoutes;
import rs.raf.dlsm.server.http.Router;
import rs.raf.dlsm.server.http.StorageStatusRoutes;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * One standalone process: the admin server on 8082, the storage engine and
 * the public API on 8080, started in that order. The admin server comes
 * first, so liveness answers while the engine is still opening or
 * recovering (PI §20.1); readiness follows the engine's lifecycle.
 *
 * <p>The engine opens through startup recovery over {@code DLSM_DATA_DIR}
 * (CP1 §21.1), with the flush worker as its maintenance. The node holds the
 * directory lock until it closes. When recovery fails the process stays up
 * as {@code FAILED} (CP1 §21.4); only a server that cannot bind fails
 * {@link #start}.
 */
public final class StandaloneNode implements AutoCloseable {

    private final HttpServerHost adminHost;
    private final HttpServerHost publicHost;
    /** One instance for the whole node: every module counts into it (CP1 §24). */
    private final StorageStats stats = new StorageStats();
    /** Present only with {@code TEST_MODE=true} (PI §16.6, §21). */
    private final FailpointRegistry failpointRegistry;
    private final DataLayout layout;
    private final StorageIO io;
    private final RecoveryProcedure recovery;
    private volatile StorageEngine engine;
    private volatile MaintenanceWorker maintenance;
    /** Shared by the flush and compaction jobs; the status reports it (CP1 §24). */
    private final MaintenanceProgress progress = new MaintenanceProgress();
    private volatile NodeStatus status;
    private volatile boolean recovering;
    /** Why startup recovery failed; the engine is then a {@link FailedStartupEngine}. */
    private volatile StorageException startupFailure;
    private volatile boolean stopping;

    private StandaloneNode(ServerConfig config, JsonLog log,
                           Function<Failpoints, StorageIO> storage) throws IOException {
        // Failpoints exist only in test mode; otherwise every hit is a no-op.
        failpointRegistry = config.testMode() ? new FailpointRegistry(true) : null;
        if (failpointRegistry != null && config.bootstrapFailpoints() != null) {
            for (FailpointRegistry.Rule rule : config.bootstrapFailpoints()) {
                failpointRegistry.addRule(rule);
            }
        }
        Failpoints failpoints = failpointRegistry != null
                ? failpointRegistry : new NoOpFailpoints();
        layout = new DataLayout(config.dataDir());
        io = storage.apply(failpoints);
        recovery = new RecoveryProcedure(layout, config.nodeId(), io, failpoints, stats, log);

        Router admin = new Router();
        HealthRoutes.register(admin, config.nodeId(), this::lifecycle, this::lastFatalError);
        // Registered before the admin server starts (the router must not
        // change while serving); answers 503 until the engine exists.
        StorageStatusRoutes.register(admin,
                () -> nodeStatus().snapshot(),
                () -> nodeStatus().acceptedSequence(),
                () -> nodeStatus().durableSequence(),
                stats);
        MaintenanceRoutes.register(admin, () -> maintenance);
        // The failpoint control API exists only in test mode (PI §21.1).
        if (failpointRegistry != null) {
            FailpointRoutes.register(admin, failpointRegistry);
        }

        // Each server is started right after it binds: a JDK HttpServer that
        // was created but never started does not release its port on close.
        adminHost = HttpServerHost.admin(
                new InetSocketAddress(config.adminPort()), admin, log);
        adminHost.start();
        try {
            StorageEngine served = recoverEngine(new EngineConfig(config.memtableMaxBytes(), failpoints),
                    config.nodeId(), failpoints, log);
            engine = served;

            Router publicApi = new Router();
            KvRoutes.register(publicApi, served);
            publicHost = HttpServerHost.publicApi(
                    new InetSocketAddress(config.publicPort()), publicApi, log);
            publicHost.start();
        } catch (IOException | RuntimeException e) {
            adminHost.close();
            if (engine != null) {
                engine.close();
            }
            try {
                recovery.close();
            } catch (IOException unlockFailure) {
                e.addSuppressed(unlockFailure);
            }
            throw e;
        }
    }

    /**
     * CP1 §21.1 steps 1–15 over the data directory. The maintenance worker
     * continues from what recovery proved: the loaded manifest and the
     * table ID allocator built before cleanup (CP1 §18.3).
     *
     * <p>When recovery cannot prove the directory state (CP1 §21.4), the
     * node stays alive as {@code FAILED}: liveness answers, readiness is
     * {@code 503} with {@code lastFatalError}, public requests get
     * {@code NODE_NOT_READY}, and the directory stays locked (CP1 §14.1).
     * Only a restart tries again (CP1 §22).
     */
    private StorageEngine recoverEngine(EngineConfig engineConfig, String nodeId,
                                        Failpoints failpoints, JsonLog log) {
        recovering = true;
        try {
            Engine opened = recovery.openEngine(engineConfig,
                    (host, recovered) -> startMaintenance(host, recovered, failpoints, log),
                    OpContext.background());
            status = new NodeStatus(nodeId, opened, stats,
                    () -> recovery.manifests().current(), progress, recovery.walCatalog());
            return opened;
        } catch (StorageException e) {
            return failStartup(e, log);
        } catch (IOException e) {
            // A recovery step could not be made durable, e.g. the new active
            // WAL. The message may hold an absolute path, so only the type
            // reaches responses (PI §17.4); the log gets the whole message.
            return failStartup(new StorageException(ErrorCode.STORAGE_IO, null,
                    "Recovery I/O failed: " + e.getClass().getSimpleName(), e), log);
        } catch (RuntimeException e) {
            return failStartup(new StorageException(ErrorCode.INTERNAL_ERROR, null,
                    "Recovery failed unexpectedly: " + e.getClass().getSimpleName(), e), log);
        }
    }

    private StorageEngine failStartup(StorageException error, JsonLog log) {
        startupFailure = error;
        EngineStatus.FatalError fatal = EngineStatus.FatalError.of(error);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("lifecycleState", Lifecycle.FAILED.name());
        fields.put("errorCode", fatal.code());
        fields.put("message", error.getCause() != null
                ? fatal.message() + " (" + error.getCause() + ")"
                : fatal.message());
        log.error("RECOVERY_FAILED", fields);
        return new FailedStartupEngine();
    }

    private FlushScheduler startMaintenance(MaintenanceHost host, RecoveryResult recovered,
                                            Failpoints failpoints, JsonLog log) {
        // Covered WAL segments are deleted through the WAL module, so its catalog follows.
        FlushJob flush = new FlushJob(layout, io, failpoints, stats, recovery.manifests(),
                recovered.tableIds(), host, progress, recovered.wal().walFactory()::retire);
        CompactionJob compaction = new CompactionJob(layout, io, failpoints, stats, recovery.manifests(),
                recovered.tableIds(), host, progress);
        MaintenanceWorker worker = new MaintenanceWorker(host, recovery.manifests(), flush, compaction, log);
        maintenance = worker;
        return worker;
    }

    public static StandaloneNode start(ServerConfig config, JsonLog log)
            throws IOException {
        return start(config, log, DefaultStorageIO::new);
    }

    /**
     * With another {@link StorageIO}: tests on Windows, where a directory
     * cannot be forced, replace only that call.
     */
    static StandaloneNode start(ServerConfig config, JsonLog log,
                                Function<Failpoints, StorageIO> storage) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(storage, "storage");

        StandaloneNode node = new StandaloneNode(config, log, storage);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("lifecycleState", node.lifecycle().name());
        fields.put("message", "public port " + node.publicPort()
                + ", admin port " + node.adminPort());
        log.info("NODE_STARTED", fields);
        return node;
    }

    /**
     * STARTING, then RECOVERING while startup recovery runs (CP1 §22), then
     * the engine's lifecycle: READY, or FAILED for good.
     */
    public Lifecycle lifecycle() {
        if (stopping) {
            return Lifecycle.STOPPING;
        }
        StorageEngine current = engine;
        if (current != null) {
            return current.lifecycle();
        }
        return recovering ? Lifecycle.RECOVERING : Lifecycle.STARTING;
    }

    /**
     * Why the node is FAILED: the startup recovery failure, or the engine's
     * first fatal error while serving (CP1 §22). Empty while healthy.
     */
    public Optional<EngineStatus.FatalError> lastFatalError() {
        StorageException failure = startupFailure;
        if (failure == null && engine instanceof Engine running) {
            failure = running.lastFatalError().orElse(null);
        }
        return Optional.ofNullable(failure).map(EngineStatus.FatalError::of);
    }

    public StorageEngine engine() {
        return engine;
    }

    /** The node's shared counters. */
    public StorageStats stats() {
        return stats;
    }

    /**
     * The failpoint registry that the failpoint admin routes manage; empty
     * outside test mode, where failpoints cannot be armed at all.
     */
    public Optional<FailpointRegistry> failpointRegistry() {
        return Optional.ofNullable(failpointRegistry);
    }

    private NodeStatus nodeStatus() {
        NodeStatus current = status;
        if (current == null) {
            // No engine snapshot exists before recovery ends or after it failed;
            // the failure itself is reported by /health/ready.
            throw new UncheckedStorageException(new StorageException(ErrorCode.NODE_NOT_READY,
                    startupFailure != null ? "Node failed during startup recovery" : "Node is starting"));
        }
        return current;
    }

    public int publicPort() {
        return publicHost.port();
    }

    public int adminPort() {
        return adminHost.port();
    }

    /**
     * Stops serving and releases the directory lock. Durability never
     * depends on this (CP1 §3); it lets tests restart over the same directory.
     */
    @Override
    public void close() {
        stopping = true;
        // Paused operations must not keep the engine busy while it closes.
        if (failpointRegistry != null) {
            failpointRegistry.clear();
        }
        publicHost.close();
        adminHost.close();
        engine.close();
        try {
            recovery.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not release the data directory lock", e);
        }
    }
}
