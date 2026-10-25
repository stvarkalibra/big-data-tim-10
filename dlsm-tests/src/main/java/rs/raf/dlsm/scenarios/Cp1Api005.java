package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.harness.AdminClient;
import rs.raf.dlsm.harness.ApiClient;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.Report;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * CP1-API-005 — a scan concurrent with mutations (CP1 §29.1, §28.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node with {@code alpha=A}
 *       and {@code gamma=G}; {@code scan.after-snapshot.before-merge} armed
 *       with {@code PAUSE}, filtered to the request id of the scan.</li>
 *   <li><b>Operations:</b> {@code SCAN[alpha,omega)}; once the failpoint is
 *       {@code TRIGGERED}, and only then, {@code PUT(beta,B)} and
 *       {@code DELETE(gamma)} from two other clients; release the scan; wait
 *       for the scan and both mutations within a bounded time; the same scan
 *       again; {@code SIGKILL}, restart over the volume, the scan again.</li>
 *   <li><b>Failpoint:</b> {@code PAUSE} after the scan took its snapshot and
 *       before it merges the sources: the view is fixed, the result not yet
 *       produced, when the mutations are invoked.</li>
 *   <li><b>Client outcome:</b> every call answers {@code 200}. While the scan
 *       is paused the mutations may wait behind its read lock or complete
 *       against a separate snapshot; both are valid and neither is asserted.</li>
 *   <li><b>Logical oracle:</b> the paused scan is exactly
 *       {@code [(alpha,A),(gamma,G)]}; the later scan and the scan after the
 *       restart are exactly {@code [(alpha,A),(beta,B)]}.</li>
 *   <li><b>Physical / lifecycle state:</b> the node stays {@code READY}; no
 *       deadlock: everything finishes within the bound.</li>
 *   <li><b>Proves:</b> a scan returns one protected snapshot, never a mix of
 *       states that did not exist at the same time, and the mutations it
 *       raced with are neither lost nor duplicated across a restart.
 *       <b>Does not prove:</b> snapshot safety against compaction deleting a
 *       captured table (CMP scenarios).</li>
 * </ol>
 */
public final class Cp1Api005 {

    public static final String ID = "CP1-API-005";

    private static final int PUBLIC_PORT = 18050;
    private static final int ADMIN_PORT = 18051;

    private static final String START = "alpha";
    private static final String END = "omega";
    private static final int LIMIT = 100;
    /** Longer than the whole scenario: the test releases the pause itself. */
    private static final int PAUSE_TIMEOUT_MS = 30_000;
    /** The request deadline the server allows at most (PI §17.3). */
    private static final long REQUEST_TIMEOUT_MS = 10_000;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Api005() {
    }

    public static void run(long seed, Report report) throws Exception {
        byte[][] values = values(seed);
        byte[] a = values[0];
        byte[] g = values[1];
        byte[] b = values[2];
        String scanRequest = ID + "-" + seed + "-scan";
        String putRequest = ID + "-" + seed + "-put";
        String deleteRequest = ID + "-" + seed + "-delete";
        String token = "api005-" + seed;

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            AdminClient admin = new AdminClient(ADMIN_PORT);
            ApiClient scanClient = new ApiClient(PUBLIC_PORT);
            ApiClient putClient = new ApiClient(PUBLIC_PORT);
            ApiClient deleteClient = new ApiClient(PUBLIC_PORT);

            node.put("alpha", a);
            node.put("gamma", g);
            node.armFailpoint(token, """
                    {"name": "%s", "action": "PAUSE", "afterHits": 0, "maxTriggers": 1,
                     "filters": {"requestId": "%s"}, "pauseTimeoutMs": %d}
                    """.formatted(FailpointNames.SCAN_AFTER_SNAPSHOT_BEFORE_MERGE,
                    scanRequest, PAUSE_TIMEOUT_MS));

            ExecutorService clients = Executors.newFixedThreadPool(3);
            try {
                Future<HttpResponse<String>> scan = clients.submit(() -> scanClient.scan(
                        START, END, LIMIT, scanRequest, REQUEST_TIMEOUT_MS));
                // The snapshot is taken; the merge has not started (CP1 §28.2).
                awaitState(admin, token, "TRIGGERED");

                // Only now are the mutations invoked, from other clients.
                Future<HttpResponse<String>> put = clients.submit(() ->
                        putClient.put("beta", b, putRequest, REQUEST_TIMEOUT_MS));
                Future<HttpResponse<String>> delete = clients.submit(() ->
                        deleteClient.delete("gamma", deleteRequest, REQUEST_TIMEOUT_MS));

                HttpResponse<String> released = admin.releaseFailpoint(token);
                node.check(released.statusCode() == 200
                                && MAPPER.readTree(released.body()).path("released").asBoolean(),
                        "release", "HTTP " + released.statusCode() + ": " + released.body());

                expectScan(node, scan.get(WAIT.toMillis(), TimeUnit.MILLISECONDS), scanRequest,
                        List.of(entry("alpha", a), entry("gamma", g)), "the paused scan");
                expectOk(node, put.get(WAIT.toMillis(), TimeUnit.MILLISECONDS), putRequest,
                        "PUT beta");
                expectOk(node, delete.get(WAIT.toMillis(), TimeUnit.MILLISECONDS), deleteRequest,
                        "DELETE gamma");
            } finally {
                clients.shutdownNow();
            }

            List<String> after = List.of(entry("alpha", a), entry("beta", b));
            expectScan(node, scanAgain(node, scanClient), null, after, "the later scan");

            node.kill();
            node.startReady();
            expectScan(node, scanAgain(node, scanClient), null, after, "the scan after the restart");
        }
        report.pass();
    }

    /** {@code A}, {@code G} and {@code B}: seeded bytes, pairwise different. */
    static byte[][] values(long seed) {
        Random random = new Random(seed);
        byte[][] values = new byte[3][];
        for (int i = 0; i < values.length; i++) {
            values[i] = new byte[4 + i];
            random.nextBytes(values[i]);
        }
        return values;
    }

    private static HttpResponse<String> scanAgain(WalScenarioNode node, ApiClient client)
            throws Exception {
        return client.scan(START, END, LIMIT, node.nextRequestId(), REQUEST_TIMEOUT_MS);
    }

    /** Bounded polling of the visible failpoint state (PI §21.1); never a sleep as proof. */
    private static void awaitState(AdminClient admin, String token, String expected) {
        Await.until(() -> {
            try {
                return expected.equals(MAPPER.readTree(admin.getFailpoint(token).body())
                        .path("state").asText());
            } catch (Exception e) {
                return false;
            }
        }, WAIT, ID + ": failpoint " + token + " to become " + expected);
    }

    /** {@code 200} with exactly {@code expected} as {@code key=hex(value)}, in order. */
    private static void expectScan(WalScenarioNode node, HttpResponse<String> response,
                                   String requestId, List<String> expected, String what)
            throws Exception {
        node.check(response.statusCode() == 200, what,
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        if (requestId != null) {
            node.check(requestId.equals(body.path("requestId").asText()), what,
                    "requestId " + body.path("requestId") + ", expected " + requestId);
        }

        List<String> actual = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            actual.add(entry(item.path("key").asText(),
                    Base64.getDecoder().decode(item.path("value").asText())));
        }
        node.check(actual.equals(expected) && body.path("count").asInt() == expected.size(),
                what, "got " + actual + ", expected exactly " + expected);
    }

    private static void expectOk(WalScenarioNode node, HttpResponse<String> response,
                                 String requestId, String what) throws Exception {
        node.check(response.statusCode() == 200, what,
                "HTTP " + response.statusCode() + ": " + response.body());
        node.check(requestId.equals(MAPPER.readTree(response.body()).path("requestId").asText()),
                what, "wrong requestId: " + response.body());
    }

    private static String entry(String key, byte[] value) {
        return key + "=" + HexFormat.of().formatHex(value);
    }
}
