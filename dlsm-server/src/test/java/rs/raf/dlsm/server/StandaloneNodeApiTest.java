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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end over real HTTP: the public API on a running standalone node.
 * Follows CP1-API-001/002/003 until the scenario harness exists.
 */
class StandaloneNodeApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private StandaloneNode node;
    private int requestNumber;

    @BeforeEach
    void start() throws Exception {
        JsonLog log = new JsonLog("standalone",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        node = StandaloneNode.start(ServerConfig.onPorts(0, 0), log);
    }

    @AfterEach
    void stop() {
        node.close();
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws Exception {
        String requestId = "node-api-" + (++requestNumber);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + node.publicPort() + path))
                .header("X-Request-Id", requestId)
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(requestId, response.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return response;
    }

    private void put(String key, byte[] value) throws Exception {
        HttpResponse<String> r = send("PUT", "/v1/kv/" + key,
                "{\"value\":\"" + Base64.getEncoder().encodeToString(value) + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals("OK", body.get("status").asText());
        assertEquals(0, body.get("partitionId").asInt());
        assertEquals(0, body.get("epoch").asInt());
    }

    private void put(String key, String value) throws Exception {
        put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] get(String key) throws Exception {
        HttpResponse<String> r = send("GET", "/v1/kv/" + key, null);
        if (r.statusCode() == 404) {
            assertEquals("NOT_FOUND", MAPPER.readTree(r.body()).get("code").asText());
            return null;
        }
        assertEquals(200, r.statusCode(), r.body());
        return Base64.getDecoder().decode(MAPPER.readTree(r.body()).get("value").asText());
    }

    private List<String> scan(String start, String end, int limit) throws Exception {
        HttpResponse<String> r = send("GET",
                "/v1/scan?start=" + start + "&end=" + end + "&limit=" + limit, null);
        assertEquals(200, r.statusCode(), r.body());

        JsonNode body = MAPPER.readTree(r.body());
        List<String> pairs = new ArrayList<>();
        for (JsonNode item : body.get("items")) {
            pairs.add(item.get("key").asText() + "="
                    + new String(Base64.getDecoder().decode(item.get("value").asText()),
                    StandardCharsets.UTF_8));
        }
        assertEquals(pairs.size(), body.get("count").asInt());
        assertEquals("[{\"partitionId\":0,\"epoch\":0}]",
                body.get("observedPartitions").toString());
        return pairs;
    }

    @Test
    void cp1Api001WriteReadOverwriteAndEmptyValue() throws Exception {
        byte[] a = {0, (byte) 0xC3, (byte) 0x28, 7};   // not valid UTF-8 text
        put("alpha", a);
        assertArrayEquals(a, get("alpha"));

        put("alpha", "B");
        assertArrayEquals("B".getBytes(StandardCharsets.UTF_8), get("alpha"));

        put("alpha", new byte[0]);
        assertArrayEquals(new byte[0], get("alpha"));

        assertEquals(List.of("alpha="), scan("alpha", "alphb", 10));
    }

    @Test
    void cp1Api002DeleteExistingAndMissingKeys() throws Exception {
        put("alpha", "A");
        assertEquals(200, send("DELETE", "/v1/kv/alpha", null).statusCode());
        assertNull(get("alpha"));

        assertEquals(200, send("DELETE", "/v1/kv/ghost", null).statusCode());
        assertNull(get("ghost"));
    }

    @Test
    void cp1Api003BoundedOrderedScan() throws Exception {
        put("a", "A");
        put("alpha", "A1");
        put("beta", "B");
        put("delta", "D1");
        put("epsilon", new byte[0]);
        put("omega", "O");
        put("zulu", "Z");
        put("delta", "D2");
        assertEquals(200, send("DELETE", "/v1/kv/beta", null).statusCode());

        assertEquals(List.of("alpha=A1", "delta=D2", "epsilon="), scan("alpha", "omega", 100));
        assertEquals(List.of("alpha=A1", "delta=D2"), scan("alpha", "omega", 2));
        assertEquals(List.of("a=A"), scan("a", "alpha", 100));
        assertEquals(List.of("omega=O"), scan("omega", "zulu", 100));
    }

    @Test
    void invalidRequestsGet400Envelope() throws Exception {
        HttpResponse<String> badBase64 = send("PUT", "/v1/kv/alpha", "{\"value\":\"QQ\"}");
        HttpResponse<String> badKey = send("GET", "/v1/kv/a%20b", null);
        HttpResponse<String> badScan = send("GET", "/v1/scan?start=b&end=a&limit=1", null);

        for (HttpResponse<String> r : List.of(badBase64, badKey, badScan)) {
            assertEquals(400, r.statusCode(), r.body());
            JsonNode body = MAPPER.readTree(r.body());
            assertEquals("ERROR", body.get("status").asText());
            assertEquals("INVALID_REQUEST", body.get("code").asText());
        }
        assertEquals(0, MAPPER.readTree(badBase64.body()).get("partitionId").asInt());
        assertFalse(MAPPER.readTree(badKey.body()).has("partitionId"));

        // Nothing was stored by the rejected PUT.
        assertNull(get("alpha"));
    }
}
