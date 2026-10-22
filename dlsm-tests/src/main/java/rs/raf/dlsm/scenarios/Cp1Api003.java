package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.ApiClient;
import rs.raf.dlsm.harness.NodeController;
import rs.raf.dlsm.harness.Report;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * CP1-API-003 — bounded ordered scan (CP1 §29).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node on an empty data
 *       directory, ready.</li>
 *   <li><b>Operations:</b> {@code a=A}, {@code alpha=A1}, {@code beta=B},
 *       {@code delta=D1}, {@code epsilon=empty}, {@code omega=O},
 *       {@code zulu=Z}; overwrite {@code delta=D2}; delete {@code beta}.
 *       Then (a) {@code SCAN[alpha,omega) limit=100}, (b) the same range
 *       with {@code limit=2}, (c) {@code SCAN[a,alpha) limit=100},
 *       (d) {@code SCAN[omega,zulu) limit=100}.</li>
 *   <li><b>Failpoint or corruption:</b> none.</li>
 *   <li><b>Client outcome:</b> every mutation and scan succeeds with
 *       {@code 200}; there is no ambiguous outcome.</li>
 *   <li><b>Logical oracle:</b> (a) exactly {@code [(alpha,A1),(delta,D2),
 *       (epsilon,empty)]}; (b) its first two items; (c) exactly
 *       {@code [(a,A)]}; (d) exactly {@code [(omega,O)]}. Each list is
 *       strictly ordered, without duplicates, reports the exact
 *       {@code count} and only the synthetic partition {@code (0,0)}.</li>
 *   <li><b>Physical / lifecycle state:</b> the node stays {@code READY};
 *       nothing about files is asserted.</li>
 *   <li><b>Proves:</b> half-open bounds, unsigned-byte key order, version
 *       resolution, tombstone filtering, empty values kept, and
 *       {@code limit} applied after filtering. <b>Does not prove:</b>
 *       efficient positioning in a large file or snapshot safety under
 *       concurrent writes (SST-004, API-005).</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed in every mode.
 */
public final class Cp1Api003 {

    public static final String ID = "CP1-API-003";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MS = 5000;
    /** Ports of this scenario only, so it never collides with a running node or another scenario. */
    private static final int PUBLIC_PORT = 18084;
    private static final int ADMIN_PORT = 18085;

    private Cp1Api003() {
    }

    public static void run(long seed, Report report) throws Exception {
        try (NodeController node = NodeController.startStandalone(PUBLIC_PORT, ADMIN_PORT)) {
            node.awaitReady(20_000);
            ApiClient api = new ApiClient(PUBLIC_PORT);
            Steps steps = new Steps(api, seed);

            steps.put("a", "A");
            steps.put("alpha", "A1");
            steps.put("beta", "B");
            steps.put("delta", "D1");
            steps.put("epsilon", "");
            steps.put("omega", "O");
            steps.put("zulu", "Z");
            steps.put("delta", "D2");
            steps.delete("beta");

            steps.expectScan("alpha", "omega", 100,
                    List.of("alpha=A1", "delta=D2", "epsilon="));
            steps.expectScan("alpha", "omega", 2,
                    List.of("alpha=A1", "delta=D2"));
            steps.expectScan("a", "alpha", 100, List.of("a=A"));
            steps.expectScan("omega", "zulu", 100, List.of("omega=O"));
        }
        report.pass();
    }

    /** Numbered requests with deterministic ids: {@code <ID>-<seed>-<n>} (plan §14). */
    private static final class Steps {
        private final ApiClient api;
        private final long seed;
        private int operation;

        Steps(ApiClient api, long seed) {
            this.api = api;
            this.seed = seed;
        }

        private String nextRequestId() {
            return ID + "-" + seed + "-" + (++operation);
        }

        void put(String key, String value) throws Exception {
            String requestId = nextRequestId();
            expectStatus(200, api.put(key, value.getBytes(StandardCharsets.UTF_8),
                    requestId, TIMEOUT_MS), "PUT " + key, requestId);
        }

        void delete(String key) throws Exception {
            String requestId = nextRequestId();
            expectStatus(200, api.delete(key, requestId, TIMEOUT_MS), "DELETE " + key, requestId);
        }

        void expectScan(String start, String end, int limit, List<String> expected)
                throws Exception {
            String requestId = nextRequestId();
            String what = "SCAN[" + start + "," + end + ") limit=" + limit;
            HttpResponse<String> response = api.scan(start, end, limit, requestId, TIMEOUT_MS);
            expectStatus(200, response, what, requestId);

            JsonNode body = MAPPER.readTree(response.body());
            check(requestId.equals(body.path("requestId").asText()), what,
                    "requestId not echoed: " + body.path("requestId"));

            List<String> actual = new ArrayList<>();
            String previous = null;
            for (JsonNode item : body.path("items")) {
                String key = item.path("key").asText();
                check(previous == null || previous.compareTo(key) < 0, what,
                        "items not strictly ordered at " + key);
                previous = key;
                byte[] value = Base64.getDecoder().decode(item.path("value").asText());
                actual.add(key + "=" + new String(value, StandardCharsets.UTF_8));
            }
            check(expected.equals(actual), what, "expected " + expected + " but got " + actual);
            check(body.path("count").asInt(-1) == actual.size(), what,
                    "count " + body.path("count") + " does not match " + actual.size() + " items");
            check("[{\"partitionId\":0,\"epoch\":0}]".equals(
                            body.path("observedPartitions").toString()), what,
                    "observedPartitions " + body.path("observedPartitions"));
        }

        private static void expectStatus(int status, HttpResponse<String> response,
                                         String what, String requestId) {
            check(response.statusCode() == status, what,
                    "HTTP " + response.statusCode() + " (request " + requestId + "): "
                            + response.body());
        }

        private static void check(boolean condition, String what, String problem) {
            if (!condition) {
                throw new AssertionError(ID + " ORACLE " + what + ": " + problem);
            }
        }
    }
}
