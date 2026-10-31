package rs.raf.dlsm.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @Test
    void defaultsMatchSpecPorts() {
        ServerConfig config = ServerConfig.fromEnv(Map.of());

        assertEquals("standalone", config.nodeId());
        assertEquals(8080, config.publicPort());
        assertEquals(8082, config.adminPort());
        assertEquals(1_048_576, config.memtableMaxBytes());
        assertEquals(1_048_576, config.engineConfig().memtableMaxBytes());
        assertEquals(Path.of("/var/lib/dlsm"), config.dataDir());
        assertEquals(Path.of("/var/lib/dlsm"), ServerConfig.onPorts(0, 0).dataDir());
    }

    @Test
    void readsDataDir(@TempDir Path dir) {
        ServerConfig config = ServerConfig.fromEnv(Map.of("DLSM_DATA_DIR", dir.toString()));

        assertEquals(dir, config.dataDir());
    }

    @Test
    void rejectsBadDataDirNamingTheVariable() {
        for (String value : new String[] {"", " ", " /var/lib/dlsm", "/var/lib/dlsm\n", "a\0b"}) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> ServerConfig.fromEnv(Map.of("DLSM_DATA_DIR", value)),
                    value);
            assertTrue(e.getMessage().contains("DLSM_DATA_DIR"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.onPorts(0, 0).withDataDir(Path.of("")));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.onPorts(0, 0).withDataDir(null));
    }

    @Test
    void withersKeepTheOtherSettings(@TempDir Path dir) {
        ServerConfig config = ServerConfig.fromEnv(Map.of(
                "NODE_ID", "storage-1", "DLSM_PUBLIC_PORT", "0", "DLSM_ADMIN_PORT", "0",
                "MEMTABLE_MAX_BYTES", "4096"));

        ServerConfig moved = config.withTestMode(true).withDataDir(dir);
        assertEquals(new ServerConfig("storage-1", 0, 0, 4096, true, dir), moved);
        assertEquals(dir, moved.withTestMode(false).dataDir());
    }

    @Test
    void testModeIsOffUnlessExactlyTrue() {
        assertFalse(ServerConfig.fromEnv(Map.of()).testMode());
        assertFalse(ServerConfig.fromEnv(Map.of("TEST_MODE", "false")).testMode());
        assertTrue(ServerConfig.fromEnv(Map.of("TEST_MODE", "true")).testMode());
        assertTrue(ServerConfig.onPorts(0, 0).withTestMode(true).testMode());
    }

    @Test
    void rejectsAmbiguousTestMode() {
        for (String value : new String[] {"", "TRUE", "yes", "1", "true "}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.fromEnv(Map.of("TEST_MODE", value)), value);
            assertTrue(e.getMessage().contains("TEST_MODE"));
        }
    }

    @Test
    void readsMemtableMaxBytes() {
        assertEquals(4096, ServerConfig.fromEnv(
                Map.of("MEMTABLE_MAX_BYTES", "4096")).memtableMaxBytes());
        assertEquals(1, ServerConfig.fromEnv(
                Map.of("MEMTABLE_MAX_BYTES", "1")).memtableMaxBytes());
    }

    @Test
    void rejectsBadMemtableMaxBytes() {
        for (String value : new String[] {"", "0", "-1", "1k", "1.5", "9".repeat(19)}) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> ServerConfig.fromEnv(Map.of("MEMTABLE_MAX_BYTES", value)),
                    value);
            assertTrue(e.getMessage().contains("MEMTABLE_MAX_BYTES"));
        }
    }

    @Test
    void readsOverrides() {
        ServerConfig config = ServerConfig.fromEnv(Map.of(
                "NODE_ID", "storage-1",
                "DLSM_PUBLIC_PORT", "18080",
                "DLSM_ADMIN_PORT", "0"));

        assertEquals("storage-1", config.nodeId());
        assertEquals(18080, config.publicPort());
        assertEquals(0, config.adminPort());
    }

    @Test
    void bothPortsMayBeEphemeral() {
        assertDoesNotThrow(() -> ServerConfig.fromEnv(Map.of(
                "DLSM_PUBLIC_PORT", "0", "DLSM_ADMIN_PORT", "0")));
    }

    @Test
    void rejectsBadPortsNamingTheVariable() {
        for (String value : new String[] {"", "-1", "65536", "80a", " 80", "999999"}) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> ServerConfig.fromEnv(Map.of("DLSM_ADMIN_PORT", value)),
                    value);
            assertTrue(e.getMessage().contains("DLSM_ADMIN_PORT"));
        }
    }

    @Test
    void rejectsSamePortTwice() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.fromEnv(Map.of(
                        "DLSM_PUBLIC_PORT", "9000", "DLSM_ADMIN_PORT", "9000")));
    }

    @Test
    void rejectsBadNodeId() {
        for (String id : new String[] {"", "a b", "a/b", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.fromEnv(Map.of("NODE_ID", id)), id);
        }
    }

    // ---------- bootstrap failpoints (PI §21.2, CP1 §26.3) ----------

    private static final String BOOTSTRAP = "DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64";
    private static final String MANIFEST_LOAD = FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD;

    private static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ServerConfig withBootstrap(String value) {
        return ServerConfig.fromEnv(Map.of("TEST_MODE", "true", BOOTSTRAP, value));
    }

    @Test
    void noBootstrapRulesUnlessSet() {
        assertEquals(List.of(), ServerConfig.fromEnv(Map.of()).bootstrapFailpoints());
        assertEquals(List.of(), ServerConfig.fromEnv(
                Map.of("TEST_MODE", "true")).bootstrapFailpoints());
        assertEquals(List.of(), ServerConfig.onPorts(0, 0).bootstrapFailpoints());
    }

    @Test
    void readsBootstrapRulesInTestMode() {
        // The example of PI §21.2, plus a second rule on another hook.
        ServerConfig config = withBootstrap(base64("""
                [{"token": "recovery-crash-1", "name": "%s", "action": "CRASH",
                  "afterHits": 0, "maxTriggers": 1, "filters": {}},
                 {"token": "replay-pause", "name": "%s", "action": "PAUSE",
                  "afterHits": 1, "maxTriggers": 2}]
                """.formatted(MANIFEST_LOAD,
                FailpointNames.RECOVERY_AFTER_WAL_REPLAY_BEFORE_READY)));

        List<FailpointRegistry.Rule> rules = config.bootstrapFailpoints();
        assertEquals(2, rules.size());
        assertEquals("recovery-crash-1", rules.get(0).getToken());
        assertEquals(MANIFEST_LOAD, rules.get(0).getName());
        assertEquals(FailpointRegistry.Action.CRASH, rules.get(0).getAction());
        assertEquals("replay-pause", rules.get(1).getToken());
        assertEquals(FailpointRegistry.Action.PAUSE, rules.get(1).getAction());
        assertEquals(FailpointRegistry.State.ARMED, rules.get(1).getState());
        assertThrows(UnsupportedOperationException.class, rules::clear);
    }

    @Test
    void anEmptyArrayArmsNothing() {
        assertEquals(List.of(), withBootstrap(base64("[]")).bootstrapFailpoints());
    }

    @Test
    void bootstrapRulesNeedTestMode() {
        String rule = base64("""
                [{"token": "t", "name": "%s", "action": "CRASH"}]
                """.formatted(MANIFEST_LOAD));
        for (Map<String, String> env : List.of(
                Map.of(BOOTSTRAP, rule),
                Map.of(BOOTSTRAP, rule, "TEST_MODE", "false"),
                // Checked before the value itself: outside test mode it is never parsed.
                Map.of(BOOTSTRAP, "not base64"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.fromEnv(env), env.toString());
            assertTrue(e.getMessage().contains(BOOTSTRAP), e.getMessage());
            assertTrue(e.getMessage().contains("TEST_MODE"), e.getMessage());
        }

        ServerConfig armed = withBootstrap(rule);
        assertThrows(IllegalArgumentException.class, () -> armed.withTestMode(false));
    }

    @Test
    void rejectsBadBootstrapValuesNamingTheVariable() {
        String rule = """
                {"token": "%s", "name": "%s", "action": "%s"}""";
        List<String> bad = List.of(
                "",                                                       // empty
                "%%%%",                                                   // not Base64
                base64("[]").replace("=", ""),                            // padding missing
                "W10=\n",                                                 // trailing newline
                base64("[{]"),                                            // not JSON
                base64("[] []"),                                          // trailing value
                base64("{}"),                                             // not an array
                base64("[1]"),                                            // item not an object
                base64("[" + rule.formatted("", MANIFEST_LOAD, "CRASH") + "]"),
                base64("[" + rule.formatted("a b", MANIFEST_LOAD, "CRASH") + "]"),
                base64("[" + rule.formatted("catalog", MANIFEST_LOAD, "CRASH") + "]"),
                base64("[{\"name\": \"" + MANIFEST_LOAD + "\", \"action\": \"CRASH\"}]"),
                base64("[" + rule.formatted("t", "recovery.unknown", "CRASH") + "]"),
                base64("[" + rule.formatted("t", MANIFEST_LOAD, "EXPLODE") + "]"),
                // IO_ERROR is not one of the eight mappings of CP1 §26.1 here.
                base64("[" + rule.formatted("t", MANIFEST_LOAD, "IO_ERROR") + "]"),
                base64("[" + rule.formatted("t", MANIFEST_LOAD, "CRASH") + ","
                        + rule.formatted("t", FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE,
                        "CRASH") + "]"),                                  // duplicate token
                base64("[" + rule.formatted("t1", MANIFEST_LOAD, "CRASH") + ","
                        + rule.formatted("t2", MANIFEST_LOAD, "PAUSE") + "]"), // same hook twice
                base64("[{\"token\": \"t\", \"name\": \"" + MANIFEST_LOAD
                        + "\", \"action\": \"CRASH\", \"afterHits\": -1}]"));
        for (String value : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> withBootstrap(value), value);
            assertTrue(e.getMessage().contains(BOOTSTRAP), e.getMessage());
        }
    }

    @Test
    void rejectsBootstrapJsonThatIsNotUtf8() {
        byte[] latin1 = ("[{\"token\": \"t\", \"name\": \"" + MANIFEST_LOAD
                + "\", \"action\": \"CRASH\", \"note\": \"é\"}]")
                .getBytes(StandardCharsets.ISO_8859_1);

        assertThrows(IllegalArgumentException.class,
                () -> withBootstrap(Base64.getEncoder().encodeToString(latin1)));
    }

    @Test
    void withersKeepTheBootstrapRules(@TempDir Path dir) {
        ServerConfig config = withBootstrap(base64("""
                [{"token": "t", "name": "%s", "action": "CRASH"}]
                """.formatted(MANIFEST_LOAD)));

        assertEquals(config.bootstrapFailpoints(),
                config.withDataDir(dir).withTestMode(true).bootstrapFailpoints());
        assertEquals(List.of(),
                config.withBootstrapFailpoints(List.of()).bootstrapFailpoints());
        assertEquals(List.of(),
                config.withBootstrapFailpoints(null).bootstrapFailpoints());
    }
}
