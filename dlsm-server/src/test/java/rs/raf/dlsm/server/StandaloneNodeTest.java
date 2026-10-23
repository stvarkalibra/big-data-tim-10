package rs.raf.dlsm.server;

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
