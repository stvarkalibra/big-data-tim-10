package rs.raf.dlsm.server;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.log.JsonLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneNodeTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
    private final JsonLog log = new JsonLog("standalone",
            new PrintStream(logOutput, true, StandardCharsets.UTF_8),
            Clock.systemUTC());

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                        .header("X-Request-Id", "node-test")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void startsBothServersAndReportsReady() throws Exception {
        try (StandaloneNode node = StandaloneNode.start(
                new ServerConfig("standalone", 0, 0), log)) {

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
        StandaloneNode node = StandaloneNode.start(
                new ServerConfig("standalone", 0, 0), log);
        node.close();

        assertEquals(Lifecycle.STOPPING, node.lifecycle());
    }

    @Test
    void busyPublicPortFailsStartupAndReleasesAdminPort() throws Exception {
        try (ServerSocket busy = new ServerSocket(0)) {
            int adminPort;
            try (ServerSocket probe = new ServerSocket(0)) {
                adminPort = probe.getLocalPort();
            }

            assertThrows(java.io.IOException.class, () -> StandaloneNode.start(
                    new ServerConfig("standalone", busy.getLocalPort(), adminPort),
                    log));

            // The admin server that did bind must have been closed again.
            try (ServerSocket reuse = new ServerSocket(adminPort)) {
                assertEquals(adminPort, reuse.getLocalPort());
            }
        }
    }
}
