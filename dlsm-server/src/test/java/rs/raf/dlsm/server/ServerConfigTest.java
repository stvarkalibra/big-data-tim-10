package rs.raf.dlsm.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
}
