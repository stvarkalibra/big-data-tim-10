package rs.raf.dlsm.server;

import rs.raf.dlsm.log.JsonLog;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Process entry point.
 *
 * <ul>
 *   <li>No arguments: run the standalone node. Configuration errors exit
 *       with code 2 and a server that cannot bind with code 1, before any
 *       request is served. A failed recovery does not exit: the node stays
 *       up as {@code FAILED} so its diagnostic can be read (CP1 §21.4).
 *       There is no shutdown hook: durability never depends on a clean
 *       shutdown (CP1 §3).</li>
 *   <li>{@code inspect ...}: offline inspection for {@code dbctl}
 *       (CP1 §25); see {@link InspectCommand}. Nothing else is started.</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            if (args[0].equals("inspect")) {
                List<String> rest = List.of(args).subList(1, args.length);
                System.exit(InspectCommand.run(rest, System.out, System.err));
                return;
            }
            System.err.println("Unknown command: " + args[0]);
            System.err.println("usage: Main            (run the standalone node)");
            System.err.println("       Main inspect wal [--data-dir PATH]");
            System.exit(InspectCommand.EXIT_USAGE);
            return;
        }

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
