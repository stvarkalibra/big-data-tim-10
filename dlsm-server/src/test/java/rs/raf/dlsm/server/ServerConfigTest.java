package rs.raf.dlsm.server;

import org.junit.jupiter.api.Test;

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
