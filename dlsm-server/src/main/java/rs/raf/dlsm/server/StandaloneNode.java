package rs.raf.dlsm.server;

import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.server.http.HealthRoutes;
import rs.raf.dlsm.server.http.HttpServerHost;
import rs.raf.dlsm.server.http.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One standalone process: the admin server on 8082 and the public API on
 * 8080. The admin server starts first, so liveness answers while the node
 * is still starting or recovering (PI §20.1).
 */
public final class StandaloneNode implements AutoCloseable {

    private final AtomicReference<Lifecycle> lifecycle =
            new AtomicReference<>(Lifecycle.STARTING);
    private final HttpServerHost adminHost;
    private final HttpServerHost publicHost;

    private StandaloneNode(ServerConfig config, JsonLog log) throws IOException {
        Router admin = new Router();
        HealthRoutes.register(admin, config.nodeId(), lifecycle::get);

        Router publicApi = new Router();

        // Each server is started right after it binds: a JDK HttpServer that
        // was created but never started does not release its port on close.
        adminHost = HttpServerHost.admin(
                new InetSocketAddress(config.adminPort()), admin, log);
        adminHost.start();
        try {
            publicHost = HttpServerHost.publicApi(
                    new InetSocketAddress(config.publicPort()), publicApi, log);
            publicHost.start();
        } catch (IOException | RuntimeException e) {
            adminHost.close();
            throw e;
        }
    }

    public static StandaloneNode start(ServerConfig config, JsonLog log)
            throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(log, "log");

        StandaloneNode node = new StandaloneNode(config, log);

        // TODO(engine): open the StorageEngine here and report RECOVERING
        // until recovery finishes; until then the node has no state to recover.
        node.lifecycle.set(Lifecycle.READY);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("lifecycleState", node.lifecycle().name());
        fields.put("message", "public port " + node.publicPort()
                + ", admin port " + node.adminPort());
        log.info("NODE_STARTED", fields);
        return node;
    }

    public Lifecycle lifecycle() {
        return lifecycle.get();
    }

    public int publicPort() {
        return publicHost.port();
    }

    public int adminPort() {
        return adminHost.port();
    }

    @Override
    public void close() {
        lifecycle.set(Lifecycle.STOPPING);
        publicHost.close();
        adminHost.close();
    }
}
