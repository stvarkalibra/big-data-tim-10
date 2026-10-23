package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.log.JsonLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneNodeMaintenanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private StandaloneNode node;

    @BeforeEach
    void start() throws Exception {
        JsonLog log = new JsonLog("standalone",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        node = StandaloneNode.start(new ServerConfig("standalone", 0, 0, 1 << 20), log);
    }

    @AfterEach
    void stop() {
        node.close();
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("X-Request-Id", "maintenance-1")
                        .POST(body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void flushIsNotReadyWhileNodeHasNoMaintenanceWorker() throws Exception {
        HttpResponse<String> response = post(node.adminPort(), "/admin/v1/storage/flush", null);

        assertEquals(503, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("ERROR", body.get("status").asText());
        assertEquals("NODE_NOT_READY", body.get("code").asText());
        assertEquals("maintenance-1", body.get("requestId").asText());
    }

    @Test
    void flushRejectsInvalidBody() throws Exception {
        HttpResponse<String> response = post(node.adminPort(), "/admin/v1/storage/flush", "{\"x\":1}");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals("INVALID_REQUEST", MAPPER.readTree(response.body()).get("code").asText());
    }

    @Test
    void flushIsOnlyOnTheAdminPort() throws Exception {
        assertEquals(404, post(node.publicPort(), "/admin/v1/storage/flush", null).statusCode());
    }
}
