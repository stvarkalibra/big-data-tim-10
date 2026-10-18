package rs.raf.dlsm.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.log.JsonLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerHostTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
    private HttpServerHost publicHost;
    private HttpServerHost adminHost;

    @BeforeEach
    void start() throws Exception {
        JsonLog log = new JsonLog("test-node",
                new PrintStream(logOutput, true, StandardCharsets.UTF_8),
                Clock.systemUTC());

        Router router = new Router()
                .add("GET", "/echo/{name}", (req, ctx) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", "OK");
                    body.put("requestId", ctx.requestId());
                    body.put("name", req.pathParam("name"));
                    body.put("q", req.queryParam("q").orElse(null));
                    body.put("remainingMs", ctx.remainingNanos() / 1_000_000);
                    return ApiResponse.ok(body);
                })
                .add("PUT", "/body", (req, ctx) -> ApiResponse.ok(
                        Map.of("bytes", req.body().length)))
                .add("GET", "/api-error", (req, ctx) -> {
                    throw new ApiException(ErrorCode.VALUE_TOO_LARGE,
                            "Value too large", PartitionRef.STANDALONE);
                })
                .add("GET", "/storage-error", (req, ctx) -> {
                    throw new StorageException(ErrorCode.STORAGE_IO,
                            "WAL_FRAME", "Storage failure");
                })
                .add("GET", "/bug", (req, ctx) -> {
                    throw new IllegalStateException("/secret/abs/path broke");
                })
                .add("GET", "/error", (req, ctx) -> {
                    throw new NoClassDefFoundError("com/example/Missing");
                })
                .add("GET", "/unchecked-storage", (req, ctx) -> {
                    throw new rs.raf.dlsm.common.UncheckedStorageException(
                            new StorageException(ErrorCode.DEADLINE_EXCEEDED,
                                    "Status deadline expired"));
                });

        InetSocketAddress any = new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0);
        publicHost = HttpServerHost.publicApi(any, router, log);
        adminHost = HttpServerHost.admin(any, router, log);
        publicHost.start();
        adminHost.start();
    }

    @AfterEach
    void stop() {
        publicHost.close();
        adminHost.close();
    }

    private HttpResponse<String> send(HttpServerHost host, String method,
                                      String path, String requestId,
                                      String body, String... headers)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + host.port() + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    @Test
    void successEchoesRequestIdAndSetsSharedHeaders() throws Exception {
        HttpResponse<String> r = send(publicHost, "GET", "/echo/alpha?q=x",
                "get-001", null);

        assertEquals(200, r.statusCode());
        assertEquals("get-001", r.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(r.headers().firstValue("Content-Type").orElseThrow()
                .startsWith("application/json"));

        JsonNode body = json(r);
        assertEquals("get-001", body.get("requestId").asText());
        assertEquals("alpha", body.get("name").asText());
        assertEquals("x", body.get("q").asText());
    }

    @Test
    void timeoutHeaderSetsDeadline() throws Exception {
        long remaining = json(send(publicHost, "GET", "/echo/a", "t-1", null,
                "X-Request-Timeout-Ms", "300")).get("remainingMs").asLong();
        long defaultRemaining = json(send(publicHost, "GET", "/echo/a", "t-2",
                null)).get("remainingMs").asLong();

        assertTrue(remaining > 0 && remaining <= 300, "remaining " + remaining);
        assertTrue(defaultRemaining > 300 && defaultRemaining <= 5000);
    }

    @Test
    void publicApiRequiresRequestId() throws Exception {
        HttpResponse<String> r = send(publicHost, "GET", "/echo/a", null, null);

        assertEquals(400, r.statusCode());
        assertTrue(r.headers().firstValue("X-Request-Id").isEmpty());
        JsonNode body = json(r);
        assertEquals("ERROR", body.get("status").asText());
        assertEquals("INVALID_REQUEST", body.get("code").asText());
        assertTrue(body.get("requestId").isNull());
        assertFalse(body.has("partitionId"));
    }

    @Test
    void adminRequestIdIsOptional() throws Exception {
        HttpResponse<String> r = send(adminHost, "GET", "/echo/a", null, null);

        assertEquals(200, r.statusCode());
        assertTrue(json(r).get("requestId").isNull());
    }

    @Test
    void invalidHeadersGiveInvalidRequest() throws Exception {
        assertEquals(400, send(publicHost, "GET", "/echo/a", "bad id!", null)
                .statusCode());

        HttpResponse<String> badTimeout = send(publicHost, "GET", "/echo/a",
                "t-3", null, "X-Request-Timeout-Ms", "50");
        assertEquals(400, badTimeout.statusCode());
        // A valid id is echoed even when another header is invalid.
        assertEquals("t-3", json(badTimeout).get("requestId").asText());
    }

    @Test
    void apiExceptionUsesEnvelopeAndItsPartition() throws Exception {
        HttpResponse<String> r = send(publicHost, "GET", "/api-error", "e-1", null);

        assertEquals(400, r.statusCode());
        JsonNode body = json(r);
        assertEquals("VALUE_TOO_LARGE", body.get("code").asText());
        assertEquals("e-1", body.get("requestId").asText());
        assertFalse(body.get("retryable").asBoolean());
        assertEquals(0, body.get("partitionId").asInt());
        assertEquals(0, body.get("epoch").asInt());
    }

    @Test
    void storageExceptionMapsToStatusWithPartitionOnPublicOnly() throws Exception {
        JsonNode publicBody = json(send(publicHost, "GET", "/storage-error", "s-1", null));
        HttpResponse<String> admin = send(adminHost, "GET", "/storage-error", null, null);

        assertEquals("STORAGE_IO", publicBody.get("code").asText());
        assertEquals(0, publicBody.get("partitionId").asInt());
        assertEquals(500, admin.statusCode());
        assertFalse(json(admin).has("partitionId"));
    }

    @Test
    void unexpectedExceptionHidesDetailsAndIsLogged() throws Exception {
        HttpResponse<String> r = send(publicHost, "GET", "/bug", "b-1", null);

        assertEquals(500, r.statusCode());
        assertEquals("INTERNAL_ERROR", json(r).get("code").asText());
        assertFalse(r.body().contains("secret"));
        assertFalse(r.body().contains("IllegalStateException"));

        String logs = logOutput.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("\"event\":\"HTTP_INTERNAL_ERROR\""));
        assertTrue(logs.contains("\"requestId\":\"b-1\""));
    }

    @Test
    void errorInHandlerStillAnswersAndClosesTheExchange() throws Exception {
        // Regression: an Error (not an Exception) used to leave the
        // exchange open, so the client waited forever.
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + publicHost.port() + "/error"))
                .header("X-Request-Id", "err-1")
                .timeout(java.time.Duration.ofSeconds(5))
                .build();
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(500, r.statusCode());
        assertEquals("INTERNAL_ERROR", json(r).get("code").asText());
        assertFalse(r.body().contains("Missing"));

        // The server keeps serving afterwards.
        assertEquals(200, send(publicHost, "GET", "/echo/a", "err-2", null).statusCode());
    }

    @Test
    void uncheckedStorageExceptionKeepsItsCode() throws Exception {
        HttpResponse<String> r = send(adminHost, "GET", "/unchecked-storage", null, null);

        assertEquals(504, r.statusCode());
        assertEquals("DEADLINE_EXCEEDED", json(r).get("code").asText());
        assertEquals("Status deadline expired", json(r).get("message").asText());
    }

    @Test
    void unknownRouteAndWrongMethod() throws Exception {
        HttpResponse<String> missing = send(publicHost, "GET", "/nope", "n-1", null);
        HttpResponse<String> wrong = send(publicHost, "DELETE", "/echo/a", "n-2", null);

        assertEquals(404, missing.statusCode());
        assertEquals("NOT_FOUND", json(missing).get("code").asText());
        assertEquals(405, wrong.statusCode());
        assertEquals("GET", wrong.headers().firstValue("Allow").orElseThrow());
        assertEquals("INVALID_REQUEST", json(wrong).get("code").asText());
    }

    @Test
    void bodyIsReadUpToLimit() throws Exception {
        String atLimit = "x".repeat(HttpServerHost.MAX_BODY_BYTES);
        HttpResponse<String> ok = send(publicHost, "PUT", "/body", "p-1", atLimit);
        HttpResponse<String> tooBig = send(publicHost, "PUT", "/body", "p-2",
                atLimit + "x");

        assertEquals(200, ok.statusCode());
        assertEquals(HttpServerHost.MAX_BODY_BYTES, json(ok).get("bytes").asInt());
        assertEquals(400, tooBig.statusCode());
        assertEquals("VALUE_TOO_LARGE", json(tooBig).get("code").asText());
    }
}
