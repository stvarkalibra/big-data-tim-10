package rs.raf.dlsm.server;

import java.util.Map;
import java.util.Objects;

/**
 * Process configuration read from environment variables.
 *
 * <ul>
 *   <li>{@code NODE_ID} — node identity, default {@code standalone};</li>
 *   <li>{@code DLSM_PUBLIC_PORT} — public API port, default {@code 8080};</li>
 *   <li>{@code DLSM_ADMIN_PORT} — admin/test port, default {@code 8082}.</li>
 * </ul>
 *
 * <p>The graded ports are fixed (PI §17.1); the port overrides exist so the
 * test harness can run several child processes side by side. Port 0 asks
 * the OS for a free port.
 */
public record ServerConfig(String nodeId, int publicPort, int adminPort) {

    public static final String NODE_ID = "NODE_ID";
    public static final String PUBLIC_PORT = "DLSM_PUBLIC_PORT";
    public static final String ADMIN_PORT = "DLSM_ADMIN_PORT";

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
    }

    /** @throws IllegalArgumentException naming the bad variable */
    public static ServerConfig fromEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new ServerConfig(
                env.getOrDefault(NODE_ID, DEFAULT_NODE_ID),
                port(env, PUBLIC_PORT, DEFAULT_PUBLIC_PORT),
                port(env, ADMIN_PORT, DEFAULT_ADMIN_PORT));
    }

    private static int port(Map<String, String> env, String name, int fallback) {
        String text = env.get(name);
        if (text == null) {
            return fallback;
        }
        if (text.isEmpty() || text.length() > 5
                || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return Integer.parseInt(text);
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
