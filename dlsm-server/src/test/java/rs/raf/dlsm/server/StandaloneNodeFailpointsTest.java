package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Failpoints are wired into the node only in test mode (PI §16.6, §21). */
class StandaloneNodeFailpointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private StandaloneNode node;

    @TempDir
    Path dataDir;

    @AfterEach
    void stop() {
        if (node != null) {
            node.close();
        }
    }

    private void start(boolean testMode) throws Exception {
        start(ServerConfig.onPorts(0, 0).withTestMode(testMode).withDataDir(dataDir));
    }

    private void start(ServerConfig config) throws Exception {
        JsonLog log = new JsonLog("standalone",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        node = StandaloneNode.start(config, log, WindowsSafeStorageIO::new);
    }

    private HttpResponse<String> put(String key) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + node.publicPort() + "/v1/kv/" + key))
                        .header("X-Request-Id", "fp-" + key)
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"QQ==\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> admin(String method, String path, String body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + node.adminPort() + path))
                        .method(method, body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String failpointState(String token) throws Exception {
        HttpResponse<String> response = admin("GET", "/admin/v1/failpoints/" + token, null);
        assertEquals(200, response.statusCode());
        return MAPPER.readTree(response.body()).get("state").asText();
    }

    /** Bounded polling of the visible failpoint state (PI §21.1). */
    private void awaitState(String token, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!failpointState(token).equals(expected)) {
            assertTrue(System.nanoTime() - deadline < 0, token + " never became " + expected);
            Thread.onSpinWait();
        }
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
        // The control API does not exist outside test mode.
        assertEquals(404, admin("GET", "/admin/v1/failpoints/catalog", null).statusCode());
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

    @Test
    void catalogIsServedOnTheAdminPort() throws Exception {
        start(true);

        HttpResponse<String> catalog = admin("GET", "/admin/v1/failpoints/catalog", null);
        assertEquals(200, catalog.statusCode());
        assertEquals(21, MAPPER.readTree(catalog.body()).get("failpoints").size());
    }

    @Test
    void pausedPutContinuesAfterReleaseOverHttp() throws Exception {
        start(true);
        HttpResponse<String> armed = admin("PUT", "/admin/v1/failpoints/pause-1", """
                {"name": "%s", "action": "PAUSE", "filters": {"requestId": "fp-gamma"}}
                """.formatted(FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE));
        assertEquals(200, armed.statusCode());
        assertEquals("ARMED", failpointState("pause-1"));

        CompletableFuture<HttpResponse<String>> paused =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return put("gamma");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        awaitState("pause-1", "TRIGGERED");
        // The paused PUT holds the mutation lock, but the admin port still answers.
        assertFalse(paused.isDone());
        assertEquals(200, admin("GET", "/health/live", null).statusCode());

        HttpResponse<String> released =
                admin("POST", "/admin/v1/failpoints/pause-1/release", null);
        assertTrue(MAPPER.readTree(released.body()).get("released").asBoolean());
        assertEquals(200, paused.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals("RELEASED", failpointState("pause-1"));

        assertEquals(200, admin("DELETE", "/admin/v1/failpoints", null).statusCode());
        assertEquals("UNKNOWN", failpointState("pause-1"));
    }

    @Test
    void returnErrorAfterApplyAnswersWithItsCategoryAndKeepsServing() throws Exception {
        start(true);
        admin("PUT", "/admin/v1/failpoints/ret-1", """
                {"name": "%s", "action": "RETURN_ERROR", "errorCode": "TEST_FAULT"}
                """.formatted(FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE));

        HttpResponse<String> failed = put("delta");
        assertEquals(500, failed.statusCode());
        assertEquals("INTERNAL_ERROR", MAPPER.readTree(failed.body()).get("code").asText());

        // Durable and applied before the hook: the engine keeps serving.
        assertEquals(200, ready().statusCode());
        assertEquals(200, put("epsilon").statusCode());
    }

    @Test
    void bootstrapRuleFiresInRecoveryAndIsNotKept() throws Exception {
        FailpointRegistry.Rule rule = new FailpointRegistry.Rule("boot-1",
                FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD,
                FailpointRegistry.Action.RETURN_ERROR, 0, 1, 0, Map.of());
        start(ServerConfig.onPorts(0, 0).withTestMode(true).withDataDir(dataDir)
                .withBootstrapFailpoints(List.of(rule)));

        // The hook fired before any HTTP client could have armed it.
        HttpResponse<String> health = ready();
        assertEquals(503, health.statusCode());
        assertEquals("FAILED", MAPPER.readTree(health.body()).get("lifecycle").asText());
        // The process stays alive, so the rule shows like an API rule (CP1 §26.3).
        assertEquals("TRIGGERED", failpointState("boot-1"));

        // Test configuration, not database state: without it the next start recovers.
        node.close();
        start(true);
        assertEquals(200, ready().statusCode());
        assertEquals("UNKNOWN", failpointState("boot-1"));
    }
}
