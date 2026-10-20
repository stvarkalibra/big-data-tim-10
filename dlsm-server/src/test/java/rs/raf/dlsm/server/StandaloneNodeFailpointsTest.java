package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.log.JsonLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Failpoints are wired into the node only in test mode (PI §16.6, §21). */
class StandaloneNodeFailpointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private StandaloneNode node;

    @AfterEach
    void stop() {
        if (node != null) {
            node.close();
        }
    }

    private void start(boolean testMode) throws Exception {
        JsonLog log = new JsonLog("standalone",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        node = StandaloneNode.start(ServerConfig.onPorts(0, 0).withTestMode(testMode), log);
    }

    private HttpResponse<String> put(String key) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + node.publicPort() + "/v1/kv/" + key))
                        .header("X-Request-Id", "fp-" + key)
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"QQ==\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> ready() throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + node.adminPort() + "/health/ready"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noRegistryOutsideTestMode() throws Exception {
        start(false);

        assertTrue(node.failpointRegistry().isEmpty());
        assertEquals(200, put("alpha").statusCode());
    }

    @Test
    void registryRuleReachesTheEngineInTestMode() throws Exception {
        start(true);
        FailpointRegistry registry = node.failpointRegistry().orElseThrow();
        assertEquals(200, put("alpha").statusCode());

        registry.addRule(new FailpointRegistry.Rule("t1",
                FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE,
                FailpointRegistry.Action.IO_ERROR, 0, 1, 0, Map.of()));

        HttpResponse<String> failed = put("beta");
        assertEquals(500, failed.statusCode());
        assertEquals("INTERNAL_ERROR", MAPPER.readTree(failed.body()).get("code").asText());

        HttpResponse<String> health = ready();
        assertEquals(503, health.statusCode());
        assertEquals("FAILED", MAPPER.readTree(health.body()).get("lifecycle").asText());
    }
}
