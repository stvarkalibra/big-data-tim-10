package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.ApiClient;
import rs.raf.dlsm.harness.Report;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;

/**
 * CP1-API-001 — put, get, overwrite and the empty value (CP1 §29.1).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node over an empty data
 *       directory, default {@code MEMTABLE_MAX_BYTES}, ready; counters
 *       reset.</li>
 *   <li><b>Operations:</b> {@code PUT(alpha,A)} → {@code GET} →
 *       {@code PUT(alpha,B)} → {@code GET} → {@code PUT(alpha,E)} →
 *       {@code GET} → {@code SCAN[alpha,alphb) limit=10}, each with its own
 *       request id. {@code A} holds non-text bytes, {@code B} other bytes,
 *       {@code E} zero bytes; {@code A} and {@code B} come from the seed.</li>
 *   <li><b>Failpoint or corruption:</b> none.</li>
 *   <li><b>Client outcome:</b> every call succeeds with {@code 200}; there
 *       is no ambiguous outcome.</li>
 *   <li><b>Logical oracle:</b> the three {@code GET}s return exactly
 *       {@code A}, {@code B} and {@code E} (the empty value is {@code 200},
 *       never {@code 404}); the scan returns exactly {@code [(alpha,E)]}.
 *       Every response echoes its request id and the standalone partition
 *       {@code (0,0)}.</li>
 *   <li><b>Physical / status oracle:</b> each {@code PUT} adds exactly one
 *       completed WAL append and one successful WAL force; afterwards
 *       {@code nextSequence = 4} and
 *       {@code lastAcceptedSequence = lastDurableSequence = 3}; the active
 *       MemTable keeps one record for {@code alpha}, the winner with
 *       sequence 3.</li>
 *   <li><b>Proves:</b> exact byte round-trips, the highest sequence wins,
 *       an empty value is a stored {@code PUT} and not a tombstone, and no
 *       {@code PUT} is acknowledged before its WAL force (I1–I3).
 *       <b>Does not prove:</b> survival of a crash (the WAL scenarios) or
 *       reads through SSTables.</li>
 * </ol>
 */
public final class Cp1Api001 {

    public static final String ID = "CP1-API-001";

    private static final int PUBLIC_PORT = 18010;
    private static final int ADMIN_PORT = 18011;

    private static final String KEY = "alpha";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Api001() {
    }

    public static void run(long seed, Report report) throws Exception {
        byte[][] values = values(seed);
        byte[] a = values[0];
        byte[] b = values[1];
        byte[] e = new byte[0];

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            ApiClient api = new ApiClient(PUBLIC_PORT);

            long resetEpoch = node.resetStats();
            JsonNode baseline = node.storageStatus().path("stats");

            putAndExpectOneForce(node, api, a, 1);
            expectValue(node, api, a, "first GET");
            putAndExpectOneForce(node, api, b, 2);
            expectValue(node, api, b, "GET after overwrite");
            putAndExpectOneForce(node, api, e, 3);
            expectValue(node, api, e, "GET of the empty value");

            expectOnlyItem(node, api, e);

            // Three mutations: three sequences, appends and forces (CP1 §24).
            JsonNode status = node.storageStatus();
            JsonNode stats = status.path("stats");
            node.check(stats.path("resetEpoch").asLong() == resetEpoch, "counters",
                    "counters were reset by someone else: " + stats.path("resetEpoch"));
            expectDelta(node, baseline, stats, "walAppendsCompleted", 3);
            expectDelta(node, baseline, stats, "walForcesSucceeded", 3);
            node.check(status.path("nextSequence").asLong() == 4, "status",
                    "nextSequence " + status.path("nextSequence") + ", expected 4");

            JsonNode active = status.path("activeMemtable");
            node.check(active.path("entryCount").asLong() == 1, "active MemTable",
                    "entryCount " + active.path("entryCount")
                            + ", expected 1: only the winning version of alpha");
            node.check(active.path("maxSequence").asLong() == 3, "active MemTable",
                    "maxSequence " + active.path("maxSequence") + ", expected 3");

            JsonNode partition = nodeStatus(node).path("partitions").path(0);
            node.check(partition.path("lastAcceptedSequence").asLong() == 3, "node status",
                    "lastAcceptedSequence " + partition.path("lastAcceptedSequence")
                            + ", expected 3");
            node.check(partition.path("lastDurableSequence").asLong() == 3, "node status",
                    "lastDurableSequence " + partition.path("lastDurableSequence")
                            + ", expected 3");
        }
        report.pass();
    }

    /**
     * {@code A}: eight seeded bytes, the first a NUL so it is never text;
     * {@code B}: other bytes and a different length.
     */
    static byte[][] values(long seed) {
        Random random = new Random(seed);
        byte[] a = new byte[8];
        random.nextBytes(a);
        a[0] = 0x00;
        a[1] = (byte) 0xFF;

        byte[] b = new byte[11];
        random.nextBytes(b);
        b[0] = (byte) 0x80;
        return new byte[][] {a, b};
    }

    /** A successful {@code PUT} is acknowledged only after its own WAL force (I1). */
    private static void putAndExpectOneForce(WalScenarioNode node, ApiClient api,
                                             byte[] value, int number) throws Exception {
        long forcesBefore = node.stat("walForcesSucceeded");
        String requestId = node.nextRequestId();
        HttpResponse<String> response =
                api.put(KEY, value, requestId, WalScenarioNode.TIMEOUT_MS);

        node.check(response.statusCode() == 200, "PUT #" + number,
                "HTTP " + response.statusCode() + ": " + response.body());
        expectEnvelope(node, MAPPER.readTree(response.body()), requestId, "PUT #" + number);
        node.check(node.stat("walForcesSucceeded") == forcesBefore + 1, "PUT #" + number,
                "acknowledged without exactly one successful WAL force");
    }

    /** {@code 200} with exactly {@code expected}, compared as bytes, never as text. */
    private static void expectValue(WalScenarioNode node, ApiClient api,
                                    byte[] expected, String what) throws Exception {
        String requestId = node.nextRequestId();
        HttpResponse<String> response = api.get(KEY, requestId, WalScenarioNode.TIMEOUT_MS);

        node.check(response.statusCode() == 200, what,
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        expectEnvelope(node, body, requestId, what);
        node.check(KEY.equals(body.path("key").asText()), what, "key " + body.path("key"));

        byte[] actual = Base64.getDecoder().decode(body.path("value").asText());
        node.check(Arrays.equals(expected, actual), what,
                "value " + hex(actual) + ", expected " + hex(expected));
    }

    /** {@code SCAN[alpha,alphb)} with room for more: exactly one item, the newest value. */
    private static void expectOnlyItem(WalScenarioNode node, ApiClient api,
                                       byte[] expected) throws Exception {
        String requestId = node.nextRequestId();
        HttpResponse<String> response =
                api.scan(KEY, "alphb", 10, requestId, WalScenarioNode.TIMEOUT_MS);

        node.check(response.statusCode() == 200, "SCAN",
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        node.check(requestId.equals(body.path("requestId").asText()), "SCAN",
                "requestId " + body.path("requestId") + ", expected " + requestId);

        JsonNode items = body.path("items");
        node.check(items.size() == 1 && body.path("count").asInt() == 1, "SCAN",
                "expected exactly one item, got " + items);
        node.check(KEY.equals(items.path(0).path("key").asText()), "SCAN",
                "key " + items.path(0).path("key"));
        byte[] actual = Base64.getDecoder().decode(items.path(0).path("value").asText());
        node.check(Arrays.equals(expected, actual), "SCAN",
                "value " + hex(actual) + ", expected " + hex(expected));
    }

    /** Shared response fields of the standalone public API (CP1 §29, PI §18.2). */
    private static void expectEnvelope(WalScenarioNode node, JsonNode body,
                                       String requestId, String what) {
        node.check(requestId.equals(body.path("requestId").asText()), what,
                "requestId " + body.path("requestId") + ", expected " + requestId);
        node.check(body.path("partitionId").asInt(-1) == 0
                        && body.path("epoch").asInt(-1) == 0, what,
                "partition " + body.path("partitionId") + "/" + body.path("epoch")
                        + ", expected 0/0");
    }

    private static void expectDelta(WalScenarioNode node, JsonNode before, JsonNode after,
                                    String counter, long expected) {
        long delta = after.path(counter).asLong() - before.path(counter).asLong();
        node.check(delta == expected, "counters",
                counter + " grew by " + delta + ", expected " + expected);
    }

    /** {@code GET /admin/v1/status}: the partition's accepted and durable sequence (PI §20.2). */
    private static JsonNode nodeStatus(WalScenarioNode node) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + ADMIN_PORT
                                + "/admin/v1/status"))
                        .header("X-Request-Id", node.nextRequestId())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        node.check(response.statusCode() == 200, "node status",
                "HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static String hex(byte[] bytes) {
        return bytes.length == 0 ? "<empty>" : HexFormat.of().formatHex(bytes);
    }
}
