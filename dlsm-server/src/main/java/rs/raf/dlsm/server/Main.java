package rs.raf.dlsm.server;

import rs.raf.dlsm.log.JsonLog;

import java.io.IOException;
import java.util.Map;

/**
 * Process entry point. Configuration errors exit with code 2 and startup
 * failures with code 1, before any request is served. There is no
 * shutdown hook: durability never depends on a clean shutdown (CP1 §3).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        ServerConfig config;
        try {
            config = ServerConfig.fromEnv(System.getenv());
        } catch (IllegalArgumentException e) {
            new JsonLog("unknown").error("CONFIG_INVALID",
                    Map.of("message", e.getMessage()));
            System.exit(2);
            return;
        }

        JsonLog log = new JsonLog(config.nodeId());
        try {
            StandaloneNode.start(config, log);
        } catch (IOException e) {
            log.error("NODE_START_FAILED", Map.of("message", String.valueOf(e)));
            System.exit(1);
        }
        // The HTTP server threads keep the JVM running.
    }
}
