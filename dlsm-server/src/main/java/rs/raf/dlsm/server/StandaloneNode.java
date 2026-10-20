package rs.raf.dlsm.server;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.StorageEngine;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.server.http.HealthRoutes;
import rs.raf.dlsm.server.http.HttpServerHost;
import rs.raf.dlsm.server.http.KvRoutes;
import rs.raf.dlsm.server.http.Router;
import rs.raf.dlsm.server.http.StorageStatusRoutes;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One standalone process: the admin server on 8082, the storage engine and
 * the public API on 8080, started in that order. The admin server comes
 * first, so liveness answers while the engine is still opening or
 * recovering (PI §20.1); readiness follows the engine's lifecycle.
 */
public final class StandaloneNode implements AutoCloseable {

    private final HttpServerHost adminHost;
    private final HttpServerHost publicHost;
    /** One instance for the whole node: every module counts into it (CP1 §24). */
    private final StorageStats stats = new StorageStats();
    /** Present only with {@code TEST_MODE=true} (PI §16.6, §21). */
    private final FailpointRegistry failpointRegistry;
    private volatile StorageEngine engine;
    private volatile NodeStatus status;
    private volatile boolean stopping;

    private StandaloneNode(ServerConfig config, JsonLog log) throws IOException {
        // Failpoints exist only in test mode; otherwise every hit is a no-op.
        failpointRegistry = config.testMode() ? new FailpointRegistry(true) : null;
        Failpoints failpoints = failpointRegistry != null
                ? failpointRegistry : new NoOpFailpoints();

        Router admin = new Router();
        HealthRoutes.register(admin, config.nodeId(), this::lifecycle);
        // Registered before the admin server starts (the router must not
        // change while serving); answers 503 until the engine exists.
        StorageStatusRoutes.register(admin,
                () -> nodeStatus().snapshot(),
                () -> nodeStatus().acceptedSequence(),
                () -> nodeStatus().durableSequence(),
                stats);

        // Each server is started right after it binds: a JDK HttpServer that
        // was created but never started does not release its port on close.
        adminHost = HttpServerHost.admin(
                new InetSocketAddress(config.adminPort()), admin, log);
        adminHost.start();
        try {
            // TODO(recovery): open the durable engine over the data directory;
            // until then the node keeps data in memory only (milestone M1).
            Engine opened = Engine.openInMemory(
                    new EngineConfig(config.memtableMaxBytes(), failpoints));
            engine = opened;
            status = new NodeStatus(config.nodeId(), opened, stats);

            Router publicApi = new Router();
            KvRoutes.register(publicApi, engine);
            publicHost = HttpServerHost.publicApi(
                    new InetSocketAddress(config.publicPort()), publicApi, log);
            publicHost.start();
        } catch (IOException | RuntimeException e) {
            adminHost.close();
            if (engine != null) {
                engine.close();
            }
            throw e;
        }
    }

    public static StandaloneNode start(ServerConfig config, JsonLog log)
            throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(log, "log");

        StandaloneNode node = new StandaloneNode(config, log);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("lifecycleState", node.lifecycle().name());
        fields.put("message", "public port " + node.publicPort()
                + ", admin port " + node.adminPort());
        log.info("NODE_STARTED", fields);
        return node;
    }

    /** STARTING until the engine exists, then the engine's lifecycle. */
    public Lifecycle lifecycle() {
        if (stopping) {
            return Lifecycle.STOPPING;
        }
        StorageEngine current = engine;
        return current == null ? Lifecycle.STARTING : current.lifecycle();
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
            throw new UncheckedStorageException(new StorageException(
                    ErrorCode.NODE_NOT_READY, "Node is starting"));
        }
        return current;
    }

    public int publicPort() {
        return publicHost.port();
    }

    public int adminPort() {
        return adminHost.port();
    }

    @Override
    public void close() {
        stopping = true;
        publicHost.close();
        adminHost.close();
        engine.close();
    }
}
