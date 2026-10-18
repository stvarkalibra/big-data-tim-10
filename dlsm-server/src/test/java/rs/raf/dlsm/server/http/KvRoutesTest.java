package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.OpContext;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KvRoutesTest {

    private Engine engine;
    private Router router;

    @BeforeEach
    void setUp() {
        engine = Engine.openInMemory(EngineConfig.defaults());
        router = new Router();
        KvRoutes.register(router, engine);
    }

    private ApiResponse call(String method, String path, String query, String body)
            throws Exception {
        Router.Found found = assertInstanceOf(Router.Found.class,
                router.match(method, path), method + " " + path);
        return found.handler().handle(
                ApiRequest.of(method, path, found.pathParams(), query,
                        body == null ? null : body.getBytes()),
                OpContext.withTimeout("req-1", 5000));
    }

    private ApiResponse put(String key, String base64) throws Exception {
        return call("PUT", "/v1/kv/" + key, null, "{\"value\":\"" + base64 + "\"}");
    }

    private ApiException rejected(String method, String path, String query, String body) {
        return assertThrows(ApiException.class, () -> call(method, path, query, body));
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes());
    }

    // ---------- PUT / GET / DELETE ----------

    @Test
    void putReturnsSharedSuccessBody() throws Exception {
        ApiResponse r = put("alpha", "SGVsbG8=");

        assertEquals(200, r.status());
        assertEquals(Map.of("status", "OK", "requestId", "req-1",
                "partitionId", 0, "epoch", 0L), r.body());
        assertEquals(List.of("status", "requestId", "partitionId", "epoch"),
                List.copyOf(r.body().keySet()));
    }

    @Test
    void getReturnsKeyAndBase64Value() throws Exception {
        put("alpha", "SGVsbG8=");
        ApiResponse r = call("GET", "/v1/kv/alpha", null, null);

        assertEquals(200, r.status());
        assertEquals("OK", r.body().get("status"));
        assertEquals("req-1", r.body().get("requestId"));
        assertEquals("alpha", r.body().get("key"));
        assertEquals("SGVsbG8=", r.body().get("value"));
        assertEquals(0, r.body().get("partitionId"));
        assertEquals(0L, r.body().get("epoch"));
    }

    @Test
    void emptyValueIs200WithEmptyString() throws Exception {
        put("alpha", "");
        ApiResponse r = call("GET", "/v1/kv/alpha", null, null);

        assertEquals(200, r.status());
        assertEquals("", r.body().get("value"));
    }

    @Test
    void binaryValueRoundTrips() throws Exception {
        String binary = Base64.getEncoder().encodeToString(new byte[] {0, -1, 7, 13});
        put("bin", binary);

        assertEquals(binary, call("GET", "/v1/kv/bin", null, null).body().get("value"));
    }

    @Test
    void missingKeyIs404WithPartitionContext() throws Exception {
        ApiResponse r = call("GET", "/v1/kv/ghost", null, null);

        assertEquals(404, r.status());
        assertEquals("ERROR", r.body().get("status"));
        assertEquals("NOT_FOUND", r.body().get("code"));
        assertEquals("req-1", r.body().get("requestId"));
        assertEquals(0, r.body().get("partitionId"));
        assertEquals(0L, r.body().get("epoch"));
    }

    @Test
    void deleteHidesKeyAndSucceedsForUnknownKey() throws Exception {
        put("alpha", "QQ==");

        ApiResponse deleted = call("DELETE", "/v1/kv/alpha", null, null);
        assertEquals(200, deleted.status());
        assertEquals("OK", deleted.body().get("status"));
        assertEquals(404, call("GET", "/v1/kv/alpha", null, null).status());

        assertEquals(200, call("DELETE", "/v1/kv/ghost", null, null).status());
    }

    // ---------- validation ----------

    @Test
    void invalidKeyIsRejectedWithoutPartition() {
        for (String method : List.of("PUT", "GET", "DELETE")) {
            for (String key : List.of("a%20b", "a+b", "k".repeat(129), "%41")) {
                ApiException e = rejected(method, "/v1/kv/" + key, null,
                        "{\"value\":\"QQ==\"}");
                assertEquals(ErrorCode.INVALID_REQUEST, e.code(), method + " " + key);
                assertNull(e.partition());
            }
        }
    }

    @Test
    void longestValidKeyIsAccepted() throws Exception {
        assertEquals(200, put("k".repeat(128), "QQ==").status());
    }

    @Test
    void malformedBodiesAreRejectedWithPartition() {
        for (String body : new String[] {
                null, "", "not json", "[]", "{}", "{\"value\":null}",
                "{\"value\":5}", "{\"value\":[\"QQ==\"]}", "{\"value\":\"QQ\"}",
                "{\"value\":\"QR==\"}", "{\"value\":\"-_8=\"}",
                "{\"value\":\"QQ==\",\"value\":\"QQ==\"}"}) {
            ApiException e = rejected("PUT", "/v1/kv/alpha", null, body);
            assertEquals(ErrorCode.INVALID_REQUEST, e.code(), String.valueOf(body));
            assertEquals(PartitionRef.STANDALONE, e.partition(), String.valueOf(body));
        }
    }

    @Test
    void unknownBodyFieldsAreIgnored() throws Exception {
        assertEquals(200, call("PUT", "/v1/kv/alpha", null,
                "{\"value\":\"QQ==\",\"future\":{\"x\":1}}").status());
    }

    @Test
    void valueSizeLimit() throws Exception {
        String max = Base64.getEncoder().encodeToString(
                new byte[InternalRecord.MAX_VALUE_BYTES]);
        assertEquals(200, put("big", max).status());

        String tooBig = Base64.getEncoder().encodeToString(
                new byte[InternalRecord.MAX_VALUE_BYTES + 1]);
        ApiException e = rejected("PUT", "/v1/kv/big", null,
                "{\"value\":\"" + tooBig + "\"}");
        assertEquals(ErrorCode.VALUE_TOO_LARGE, e.code());
        assertEquals(PartitionRef.STANDALONE, e.partition());
    }

    @Test
    void rejectedPutsDoNotChangeState() throws Exception {
        put("alpha", "QQ==");
        rejected("PUT", "/v1/kv/alpha", null, "{\"value\":\"QQ\"}");

        assertEquals("QQ==", call("GET", "/v1/kv/alpha", null, null).body().get("value"));
    }

    // ---------- SCAN ----------

    @Test
    void scanFollowsCp1Api003() throws Exception {
        put("a", b64("A"));
        put("alpha", b64("A1"));
        put("beta", b64("B"));
        put("delta", b64("D1"));
        put("epsilon", "");
        put("omega", b64("O"));
        put("zulu", b64("Z"));
        put("delta", b64("D2"));
        call("DELETE", "/v1/kv/beta", null, null);

        assertScan("start=alpha&end=omega&limit=100",
                List.of(Map.of("key", "alpha", "value", b64("A1")),
                        Map.of("key", "delta", "value", b64("D2")),
                        Map.of("key", "epsilon", "value", "")));
        assertScan("start=alpha&end=omega&limit=2",
                List.of(Map.of("key", "alpha", "value", b64("A1")),
                        Map.of("key", "delta", "value", b64("D2"))));
        assertScan("start=a&end=alpha&limit=100",
                List.of(Map.of("key", "a", "value", b64("A"))));
        assertScan("start=omega&end=zulu&limit=100",
                List.of(Map.of("key", "omega", "value", b64("O"))));
    }

    private void assertScan(String query, List<Map<String, Object>> expected)
            throws Exception {
        ApiResponse r = call("GET", "/v1/scan", query, null);

        assertEquals(200, r.status(), query);
        assertEquals("OK", r.body().get("status"));
        assertEquals("req-1", r.body().get("requestId"));
        assertEquals(expected, r.body().get("items"), query);
        assertEquals(expected.size(), r.body().get("count"));
        assertEquals(List.of(Map.of("partitionId", 0, "epoch", 0L)),
                r.body().get("observedPartitions"));
    }

    @Test
    void scanWithNoMatchesIsEmpty() throws Exception {
        assertScan("start=a&end=b&limit=1", List.of());
    }

    @Test
    void invalidScanParametersAreRejected() {
        for (String query : new String[] {
                null, "end=b&limit=1", "start=a&limit=1", "start=a&end=b",
                "start=a&end=b&limit=0", "start=a&end=b&limit=1001",
                "start=a&end=b&limit=abc", "start=a&end=b&limit=-1",
                "start=a&end=b&limit=%2B5", "start=a&end=b&limit=",
                "start=b&end=a&limit=1", "start=a&end=a&limit=1",
                "start=a%20b&end=z&limit=1", "start=a&end=b&limit=1&limit=2"}) {
            ApiException e = rejected("GET", "/v1/scan", query, null);
            assertEquals(ErrorCode.INVALID_REQUEST, e.code(), String.valueOf(query));
            assertNull(e.partition());
        }
    }

    @Test
    void scanLimitBoundsAreInclusive() throws Exception {
        assertEquals(200, call("GET", "/v1/scan", "start=a&end=b&limit=1", null).status());
        assertEquals(200, call("GET", "/v1/scan", "start=a&end=b&limit=1000", null).status());
    }

    // ---------- engine errors ----------

    @Test
    void engineErrorsPropagateUnchanged() {
        // The handler must pass engine errors on for the server to turn into
        // the envelope; an expired deadline is the simplest one to trigger.
        Router.Found found = assertInstanceOf(Router.Found.class,
                router.match("GET", "/v1/kv/alpha"));

        StorageException e = assertThrows(StorageException.class,
                () -> found.handler().handle(
                        ApiRequest.of("GET", "/v1/kv/alpha", found.pathParams(), null, null),
                        new OpContext("late", System.nanoTime() - 1)));
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.code());
    }
}
