package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.failpoint.FailpointRegistry.Rule;
import rs.raf.dlsm.server.http.ApiException;
import rs.raf.dlsm.server.http.Base64Values;
import rs.raf.dlsm.server.http.FailpointRoutes;
import rs.raf.dlsm.server.http.Json;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Process configuration read from environment variables.
 *
 * <ul>
 *   <li>{@code NODE_ID} — node identity, default {@code standalone};</li>
 *   <li>{@code DLSM_PUBLIC_PORT} — public API port, default {@code 8080};</li>
 *   <li>{@code DLSM_ADMIN_PORT} — admin/test port, default {@code 8082};</li>
 *   <li>{@code MEMTABLE_MAX_BYTES} — MemTable rotation threshold, default
 *       1 MiB (CP1 §10);</li>
 *   <li>{@code TEST_MODE} — {@code true} enables failpoints and other
 *       state-changing test controls (PI §16.6), default {@code false};</li>
 *   <li>{@code DLSM_DATA_DIR} — the node's durable data directory, default
 *       {@code /var/lib/dlsm} (CP1 §14);</li>
 *   <li>{@code DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64} — failpoint rules armed
 *       before recovery, test mode only (PI §21.2, CP1 §26.3).</li>
 * </ul>
 *
 * <p>The graded ports and data directory are fixed (PI §17.1, CP1 §14); the
 * overrides exist so the test harness can run several child processes side
 * by side, each over its own volume. Port 0 asks the OS for a free port.
 */
public record ServerConfig(String nodeId, int publicPort, int adminPort,
                           long memtableMaxBytes, boolean testMode, Path dataDir,
                           List<Rule> bootstrapFailpoints) {

    public static final String NODE_ID = "NODE_ID";
    public static final String PUBLIC_PORT = "DLSM_PUBLIC_PORT";
    public static final String ADMIN_PORT = "DLSM_ADMIN_PORT";
    public static final String MEMTABLE_MAX_BYTES = "MEMTABLE_MAX_BYTES";
    public static final String TEST_MODE = "TEST_MODE";
    public static final String DATA_DIR = "DLSM_DATA_DIR";
    public static final String BOOTSTRAP_FAILPOINTS_B64 = "DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64";

    public static final String DEFAULT_NODE_ID = "standalone";
    public static final int DEFAULT_PUBLIC_PORT = 8080;
    public static final int DEFAULT_ADMIN_PORT = 8082;
    public static final Path DEFAULT_DATA_DIR = Path.of("/var/lib/dlsm");

    public ServerConfig {
        if (!isValidNodeId(nodeId)) {
            throw new IllegalArgumentException("Invalid " + NODE_ID);
        }
        checkPort(PUBLIC_PORT, publicPort);
        checkPort(ADMIN_PORT, adminPort);
        if (publicPort != 0 && publicPort == adminPort) {
            throw new IllegalArgumentException(
                    PUBLIC_PORT + " and " + ADMIN_PORT + " must differ");
        }
        if (memtableMaxBytes < 1) {
            throw new IllegalArgumentException("Invalid " + MEMTABLE_MAX_BYTES);
        }
        if (dataDir == null || dataDir.toString().isEmpty()) {
            throw new IllegalArgumentException("Invalid " + DATA_DIR);
        }
        bootstrapFailpoints = bootstrapFailpoints == null
                ? List.of() : List.copyOf(bootstrapFailpoints);
        if (!testMode && !bootstrapFailpoints.isEmpty()) {
            throw bootstrapNeedsTestMode();
        }
    }

    /** Without bootstrap failpoints. */
    public ServerConfig(String nodeId, int publicPort, int adminPort,
                        long memtableMaxBytes, boolean testMode, Path dataDir) {
        this(nodeId, publicPort, adminPort, memtableMaxBytes, testMode, dataDir, List.of());
    }

    /** Outside test mode, over the default data directory. */
    public ServerConfig(String nodeId, int publicPort, int adminPort, long memtableMaxBytes) {
        this(nodeId, publicPort, adminPort, memtableMaxBytes, false, DEFAULT_DATA_DIR);
    }

    /** Defaults for everything except the ports, e.g. for tests on port 0. */
    public static ServerConfig onPorts(int publicPort, int adminPort) {
        return new ServerConfig(DEFAULT_NODE_ID, publicPort, adminPort,
                EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES);
    }

    /** @throws IllegalArgumentException naming the bad variable */
    public static ServerConfig fromEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        boolean testMode = flag(env, TEST_MODE);
        return new ServerConfig(
                env.getOrDefault(NODE_ID, DEFAULT_NODE_ID),
                (int) number(env, PUBLIC_PORT, DEFAULT_PUBLIC_PORT, 5),
                (int) number(env, ADMIN_PORT, DEFAULT_ADMIN_PORT, 5),
                number(env, MEMTABLE_MAX_BYTES,
                        EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES, 18),
                testMode,
                path(env, DATA_DIR, DEFAULT_DATA_DIR),
                bootstrapFailpoints(env.get(BOOTSTRAP_FAILPOINTS_B64), testMode));
    }

    /**
     * Standard Base64 of a UTF-8 JSON array of failpoint API rules, each with
     * its own {@code token} (PI §21.2). Any error stops startup here, before
     * recovery touches a file (CP1 §26.3).
     */
    private static List<Rule> bootstrapFailpoints(String text, boolean testMode) {
        if (text == null) {
            return List.of();
        }
        if (!testMode) {
            throw bootstrapNeedsTestMode();
        }
        byte[] json = Base64Values.decodeStrict(text);
        if (json == null) {
            throw invalidBootstrap("not standard Base64 with padding");
        }

        List<Rule> rules = new ArrayList<>();
        Set<String> tokens = new HashSet<>();
        Set<String> names = new HashSet<>();
        try {
            for (JsonNode item : Json.readArray(json)) {
                if (!item.isObject()) {
                    throw invalidBootstrap("every rule must be a JSON object");
                }
                JsonNode token = item.get("token");
                if (token == null || !token.isTextual()
                        || !FailpointRoutes.isValidToken(token.textValue())) {
                    throw invalidBootstrap("every rule needs a valid token");
                }
                if (!tokens.add(token.textValue())) {
                    throw invalidBootstrap("duplicate token " + token.textValue());
                }
                Rule rule = FailpointRoutes.parseRule(token.textValue(), (ObjectNode) item);
                // The registry keeps one rule per failpoint, so a second one
                // would silently replace the first.
                if (!names.add(rule.getName())) {
                    throw invalidBootstrap("two rules for " + rule.getName());
                }
                rules.add(rule);
            }
        } catch (ApiException e) {
            throw invalidBootstrap(e.getMessage());
        }
        return rules;
    }

    private static IllegalArgumentException bootstrapNeedsTestMode() {
        return new IllegalArgumentException(
                BOOTSTRAP_FAILPOINTS_B64 + " requires " + TEST_MODE + "=true");
    }

    private static IllegalArgumentException invalidBootstrap(String detail) {
        return new IllegalArgumentException(
                "Invalid " + BOOTSTRAP_FAILPOINTS_B64 + ": " + detail);
    }

    /** Same settings with test mode switched on or off. */
    public ServerConfig withTestMode(boolean enabled) {
        return new ServerConfig(nodeId, publicPort, adminPort, memtableMaxBytes, enabled,
                dataDir, bootstrapFailpoints);
    }

    /** Same settings over another data directory, e.g. a test's temporary one. */
    public ServerConfig withDataDir(Path dir) {
        return new ServerConfig(nodeId, publicPort, adminPort, memtableMaxBytes, testMode,
                dir, bootstrapFailpoints);
    }

    /** Same settings with these rules armed before recovery; needs test mode. */
    public ServerConfig withBootstrapFailpoints(List<Rule> rules) {
        return new ServerConfig(nodeId, publicPort, adminPort, memtableMaxBytes, testMode,
                dataDir, rules);
    }

    public EngineConfig engineConfig() {
        return new EngineConfig(memtableMaxBytes);
    }

    /** Exactly {@code true} or {@code false}; anything else is a configuration error. */
    private static boolean flag(Map<String, String> env, String name) {
        String text = env.get(name);
        if (text == null || text.equals("false")) {
            return false;
        }
        if (text.equals("true")) {
            return true;
        }
        throw new IllegalArgumentException("Invalid " + name + " (expected true or false)");
    }

    /**
     * Any path the OS accepts, taken as written; empty or with surrounding
     * whitespace is a configuration error rather than a silent other directory.
     */
    private static Path path(Map<String, String> env, String name, Path fallback) {
        String text = env.get(name);
        if (text == null) {
            return fallback;
        }
        if (text.isEmpty() || !text.equals(text.strip())) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    /** Plain decimal digits; the digit limit keeps parsing overflow-free. */
    private static long number(Map<String, String> env, String name,
                               long fallback, int maxDigits) {
        String text = env.get(name);
        if (text == null) {
            return fallback;
        }
        if (text.isEmpty() || text.length() > maxDigits
                || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return Long.parseLong(text);
    }

    private static void checkPort(String name, int port) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private static boolean isValidNodeId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
