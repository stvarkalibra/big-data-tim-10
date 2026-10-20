package rs.raf.dlsm.server;

import rs.raf.dlsm.engine.EngineConfig;

import java.util.Map;
import java.util.Objects;

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
 *       state-changing test controls (PI §16.6), default {@code false}.</li>
 * </ul>
 *
 * <p>The graded ports are fixed (PI §17.1); the port overrides exist so the
 * test harness can run several child processes side by side. Port 0 asks
 * the OS for a free port.
 */
public record ServerConfig(String nodeId, int publicPort, int adminPort,
                           long memtableMaxBytes, boolean testMode) {

    public static final String NODE_ID = "NODE_ID";
    public static final String PUBLIC_PORT = "DLSM_PUBLIC_PORT";
    public static final String ADMIN_PORT = "DLSM_ADMIN_PORT";
    public static final String MEMTABLE_MAX_BYTES = "MEMTABLE_MAX_BYTES";
    public static final String TEST_MODE = "TEST_MODE";

    public static final String DEFAULT_NODE_ID = "standalone";
    public static final int DEFAULT_PUBLIC_PORT = 8080;
    public static final int DEFAULT_ADMIN_PORT = 8082;

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
    }

    /** Outside test mode. */
    public ServerConfig(String nodeId, int publicPort, int adminPort, long memtableMaxBytes) {
        this(nodeId, publicPort, adminPort, memtableMaxBytes, false);
    }

    /** Defaults for everything except the ports, e.g. for tests on port 0. */
    public static ServerConfig onPorts(int publicPort, int adminPort) {
        return new ServerConfig(DEFAULT_NODE_ID, publicPort, adminPort,
                EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES);
    }

    /** @throws IllegalArgumentException naming the bad variable */
    public static ServerConfig fromEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new ServerConfig(
                env.getOrDefault(NODE_ID, DEFAULT_NODE_ID),
                (int) number(env, PUBLIC_PORT, DEFAULT_PUBLIC_PORT, 5),
                (int) number(env, ADMIN_PORT, DEFAULT_ADMIN_PORT, 5),
                number(env, MEMTABLE_MAX_BYTES,
                        EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES, 18),
                flag(env, TEST_MODE));
    }

    /** Same settings with test mode switched on or off. */
    public ServerConfig withTestMode(boolean enabled) {
        return new ServerConfig(nodeId, publicPort, adminPort, memtableMaxBytes, enabled);
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
