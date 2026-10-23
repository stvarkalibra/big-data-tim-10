package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.engine.GetResult;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
    private final JsonLog log = new JsonLog("standalone",
            new PrintStream(logOutput, true, StandardCharsets.UTF_8),
            Clock.systemUTC());

    @TempDir
    Path dataDir;

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                        .header("X-Request-Id", "node-test")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private StandaloneNode start(ServerConfig config) throws Exception {
        return StandaloneNode.start(config.withDataDir(dataDir), log, WindowsSafeStorageIO::new);
    }

    private static String read(StandaloneNode node, String key) throws Exception {
        GetResult result = node.engine().get(key, OpContext.withTimeout("node-get", 5_000));
        return result.isFound() ? new String(result.value(), StandardCharsets.US_ASCII) : null;
    }

    @Test
    void startsBothServersAndReportsReady() throws Exception {
        try (StandaloneNode node = start(ServerConfig.onPorts(0, 0))) {

            assertEquals(Lifecycle.READY, node.lifecycle());
            assertNotEquals(node.publicPort(), node.adminPort());

            assertEquals(200, get(node.adminPort(), "/health/live").statusCode());
            HttpResponse<String> ready = get(node.adminPort(), "/health/ready");
            assertEquals(200, ready.statusCode());
            assertTrue(ready.body().contains("\"lifecycle\":\"READY\""));

            // Health lives only on the admin port.
            assertEquals(404, get(node.publicPort(), "/health/live").statusCode());

            String logs = logOutput.toString(StandardCharsets.UTF_8);
            assertTrue(logs.contains("\"event\":\"NODE_STARTED\""));
        }
    }

    @Test
    void closeReportsStopping() throws Exception {
        StandaloneNode node = start(ServerConfig.onPorts(0, 0));
        node.close();

        assertEquals(Lifecycle.STOPPING, node.lifecycle());
    }

    @Test
    void busyPublicPortFailsStartupAndReleasesAdminPortAndDirectory() throws Exception {
        try (ServerSocket busy = new ServerSocket(0)) {
            int adminPort;
            try (ServerSocket probe = new ServerSocket(0)) {
                adminPort = probe.getLocalPort();
            }

            assertThrows(java.io.IOException.class,
                    () -> start(ServerConfig.onPorts(busy.getLocalPort(), adminPort)));

            // The admin server that did bind must have been closed again.
            try (ServerSocket reuse = new ServerSocket(adminPort)) {
                assertEquals(adminPort, reuse.getLocalPort());
            }
        }
        // And the directory lock was released: the next start owns it.
        try (StandaloneNode node = start(ServerConfig.onPorts(0, 0))) {
            assertEquals(Lifecycle.READY, node.lifecycle());
        }
    }

    // ===== Durable data directory (CP1 §14, §21.1) =====

    @Test
    void firstStartInitializesTheDataDirectory() throws Exception {
        try (StandaloneNode node = start(ServerConfig.onPorts(0, 0))) {
            DataLayout layout = new DataLayout(dataDir);

            assertTrue(Files.isRegularFile(layout.lockFile()));
            assertTrue(Files.isRegularFile(layout.nodeFile()));
            assertTrue(Files.isRegularFile(layout.manifestFile()));
            assertTrue(Files.isRegularFile(layout.walDir().resolve(WalFileNames.segmentFileName(1))));
        }
    }

    @Test
    void acknowledgedWritesSurviveRestart() throws Exception {
        OpContext ctx = OpContext.withTimeout("node-put", 5_000);
        try (StandaloneNode first = start(ServerConfig.onPorts(0, 0))) {
            first.engine().put("alpha", "A".getBytes(StandardCharsets.US_ASCII), ctx);
            first.engine().put("beta", "B".getBytes(StandardCharsets.US_ASCII), ctx);
            first.engine().delete("beta", ctx);
        }

        try (StandaloneNode second = start(ServerConfig.onPorts(0, 0))) {
            assertEquals("A", read(second, "alpha"));
            assertNull(read(second, "beta"));
            assertEquals(4, second.engine().put("gamma", new byte[]{1}, ctx).sequence());
        }
    }

    // ===== Failed recovery keeps the node alive as FAILED (CP1 §21.4) =====

    @Test
    void corruptWalLeavesTheNodeAliveAsFailed() throws Exception {
        OpContext ctx = OpContext.withTimeout("node-put", 5_000);
        try (StandaloneNode first = start(ServerConfig.onPorts(0, 0))) {
            for (String key : new String[] {"alpha", "beta", "gamma"}) {
                first.engine().put(key, "V".getBytes(StandardCharsets.US_ASCII), ctx);
            }
        }
        // First key byte of the first frame: the 24-byte segment header, then
        // 26 frame bytes before the key (FORMAT.md §3.2, §3.3). The frame stays
        // complete and later frames follow, so this is corruption, not a torn tail.
        Path segment = new DataLayout(dataDir).walDir().resolve(WalFileNames.segmentFileName(1));
        byte[] bytes = Files.readAllBytes(segment);
        assertEquals('a', bytes[50]);
        bytes[50] = 'A';
        Files.write(segment, bytes);

        try (StandaloneNode node = start(ServerConfig.onPorts(0, 0))) {
            assertEquals(Lifecycle.FAILED, node.lifecycle());
            assertEquals("CHECKSUM", node.lastFatalError().orElseThrow().code());

            assertEquals(200, get(node.adminPort(), "/health/live").statusCode());
            HttpResponse<String> ready = get(node.adminPort(), "/health/ready");
            assertEquals(503, ready.statusCode());
            JsonNode body = MAPPER.readTree(ready.body());
            assertEquals("FAILED", body.get("lifecycle").asText());
            assertEquals("CHECKSUM", body.get("lastFatalError").get("code").asText());
            assertTrue(body.get("lastFatalError").get("message").asText().contains("offset 24"),
                    body.toString());
            assertFalse(ready.body().contains(dataDir.toString()), "no absolute paths");

            // Even keys written before the damaged frame are not served.
            HttpResponse<String> read = get(node.publicPort(), "/v1/kv/beta");
            assertEquals(503, read.statusCode());
            assertEquals("NODE_NOT_READY", MAPPER.readTree(read.body()).get("code").asText());
            assertEquals(503, get(node.adminPort(), "/admin/v1/storage/status").statusCode());
        }
        // Recovery neither truncated nor repaired the segment, nor made a new one.
        assertArrayEquals(bytes, Files.readAllBytes(segment));
        assertFalse(Files.exists(new DataLayout(dataDir).walDir()
                .resolve(WalFileNames.segmentFileName(2))));
        String logs = logOutput.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("\"event\":\"RECOVERY_FAILED\""));
    }

    @Test
    void secondNodeOverALockedDirectoryStaysFailed() throws Exception {
        try (StandaloneNode owner = start(ServerConfig.onPorts(0, 0));
             StandaloneNode second = start(ServerConfig.onPorts(0, 0))) {

            assertEquals(Lifecycle.FAILED, second.lifecycle());
            assertEquals("DIRECTORY_LOCKED", second.lastFatalError().orElseThrow().code());
            assertEquals(503, get(second.adminPort(), "/health/ready").statusCode());

            // The owner is untouched and keeps its lock.
            assertEquals(Lifecycle.READY, owner.lifecycle());
            assertTrue(owner.lastFatalError().isEmpty());
        }
    }

    /** Production storage I/O end to end; a directory force needs Linux (CI, Docker). */
    @Test
    @EnabledOnOs(OS.LINUX)
    void realStorageKeepsWritesAcrossRestart() throws Exception {
        OpContext ctx = OpContext.withTimeout("node-put", 5_000);
        try (StandaloneNode first = StandaloneNode.start(
                ServerConfig.onPorts(0, 0).withDataDir(dataDir), log)) {
            first.engine().put("alpha", "A".getBytes(StandardCharsets.US_ASCII), ctx);
        }

        try (StandaloneNode second = StandaloneNode.start(
                ServerConfig.onPorts(0, 0).withDataDir(dataDir), log)) {
            assertEquals("A", read(second, "alpha"));
        }
    }
}
