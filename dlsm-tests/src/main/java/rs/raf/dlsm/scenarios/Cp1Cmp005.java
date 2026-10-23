package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.harness.AdminClient;
import rs.raf.dlsm.harness.ApiClient;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.NodeController;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.harness.VolumeTools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * CP1-CMP-005 — a flush racing with the input selection of a compaction
 * (CP1 §29.5).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node with two published
 *       SSTables (the old model: {@code alpha} overwritten across them,
 *       {@code beta}, {@code gamma}) and {@code newcomer=N} only in the active
 *       MemTable; {@code compaction.after-selection.before-output} armed with
 *       one {@code PAUSE}.</li>
 *   <li><b>Operations:</b> a manual compaction; once {@code TRIGGERED}, its
 *       selected inputs are read from the status; a manual flush is sent
 *       without waiting for it; the compaction is released; both answers
 *       are awaited (a {@code 409 MAINTENANCE_CONFLICT} would be retried);
 *       maintenance settles; the final manifest and all keys are read;
 *       {@code SIGKILL}, restart, read again.</li>
 *   <li><b>Failpoint:</b> {@code PAUSE} after the compaction chose its
 *       inputs and before it writes its output.</li>
 *   <li><b>Client outcome:</b> both maintenance calls succeed with
 *       {@code PUBLISHED}; whether the flush waits for the compaction or not
 *       is the team's policy and is not asserted.</li>
 *   <li><b>Logical oracle:</b> before and after the restart the state is
 *       exactly the old model plus {@code newcomer=N}, by GET and by SCAN.</li>
 *   <li><b>Physical / status oracle:</b> the compaction replaces exactly its
 *       selected inputs; the final manifest references the compaction output
 *       and the flushed table, and neither input; no immutable MemTable is
 *       left and maintenance is idle.</li>
 *   <li><b>Proves:</b> a publisher replaces only the inputs it selected and
 *       keeps a table published meanwhile, so a flush never loses its data
 *       to a stale manifest (CP1 §20). <b>Does not prove:</b> crashes inside
 *       compaction (CMP-003, CMP-004).</li>
 * </ol>
 */
public final class Cp1Cmp005 {

    public static final String ID = "CP1-CMP-005";

    private static final int PUBLIC_PORT = 18350;
    private static final int ADMIN_PORT = 18351;

    private static final long READY_TIMEOUT_MS = 20_000;
    private static final long REQUEST_TIMEOUT_MS = 5_000;
    /** The longest deadline the server allows (PI §17.3), for a flush queued behind the pause. */
    private static final long MAINTENANCE_TIMEOUT_MS = 10_000;
    /** Longer than the whole scenario: the test releases the pause itself. */
    private static final int PAUSE_TIMEOUT_MS = 60_000;
    private static final int MAX_CONFLICT_RETRIES = 5;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Cp1Cmp005() {
    }

    public static void run(long seed, Report report) throws Exception {
        Map<String, byte[]> model = new LinkedHashMap<>();
        byte[][] values = values(seed);
        String token = "cmp005-" + seed;
        int[] operation = {0};

        Path dataDir = Files.createTempDirectory("cp1-cmp-005-" + seed + "-");
        NodeController node = new NodeController(dataDir, PUBLIC_PORT, ADMIN_PORT);
        AdminClient admin = new AdminClient(ADMIN_PORT);
        ExecutorService calls = Executors.newFixedThreadPool(2);

        try {
            node.start(true);
            node.awaitReady(READY_TIMEOUT_MS);
            ApiClient api = new ApiClient(PUBLIC_PORT);

            // Two published tables: the old model, alpha overwritten across them.
            put(api, model, "alpha", values[0], seed, operation);
            put(api, model, "beta", values[1], seed, operation);
            long first = onlyPublished(maintain("flush", seed, operation));
            put(api, model, "alpha", values[2], seed, operation);
            put(api, model, "gamma", values[3], seed, operation);
            long second = onlyPublished(maintain("flush", seed, operation));

            // The newcomer stays in the active MemTable, not flushed yet.
            put(api, model, "newcomer", values[4], seed, operation);

            HttpResponse<String> armed = admin.setFailpoint(token, """
                    {"name": "%s", "action": "PAUSE", "afterHits": 0, "maxTriggers": 1,
                     "pauseTimeoutMs": %d}
                    """.formatted(FailpointNames.COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT,
                    PAUSE_TIMEOUT_MS));
            check(armed.statusCode() == 200, "arm failpoint", armed.body());

            Future<JsonNode> compaction = calls.submit(() -> maintain("compact", seed, operation));
            Await.until(() -> "TRIGGERED".equals(failpointState(admin, token)), WAIT,
                    ID + ": the compaction never finished selecting its inputs");

            // The inputs it chose, as the status shows them during the pause.
            JsonNode running = status(seed, operation).path("maintenance");
            check("COMPACTION".equals(running.path("operation").asText()), "status during the pause",
                    "maintenance is " + running);
            TreeSet<Long> selected = ids(running.path("inputTableIds"));
            check(selected.equals(new TreeSet<>(List.of(first, second))), "selected inputs",
                    "expected tables " + first + " and " + second + ", got " + selected);

            // The flush is sent now; its completion is not required before the release.
            Future<JsonNode> flush = calls.submit(() -> maintain("flush", seed, operation));

            HttpResponse<String> released = admin.releaseFailpoint(token);
            check(released.statusCode() == 200
                            && MAPPER.readTree(released.body()).path("released").asBoolean(),
                    "release", released.body());

            JsonNode compacted = compaction.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            check("PUBLISHED".equals(compacted.path("result").asText()), "compaction", compacted.toString());
            check(ids(compacted.path("inputTableIds")).equals(selected), "compaction",
                    "replaced " + compacted.path("inputTableIds") + ", selected " + selected);
            long output = compacted.path("outputTableId").asLong();
            long flushed = onlyPublished(flush.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));

            // Maintenance settles; the manifest keeps the flush table and drops only the inputs.
            JsonNode[] settled = new JsonNode[1];
            Await.until(() -> {
                try {
                    JsonNode s = status(seed, operation);
                    settled[0] = s;
                    return "IDLE".equals(s.path("maintenance").path("phase").asText())
                            && s.path("immutableMemtable").isNull();
                } catch (Exception e) {
                    return false;
                }
            }, WAIT, ID + ": maintenance never settled");
            TreeSet<Long> published = new TreeSet<>();
            for (JsonNode table : settled[0].path("sstables")) {
                published.add(table.path("tableId").asLong());
            }
            check(published.equals(new TreeSet<>(List.of(output, flushed))), "final manifest",
                    "expected exactly the compaction output " + output + " and the flushed table "
                            + flushed + ", got " + published);
            check(settled[0].path("manifest").path("sstableCount").asInt() == 2, "final manifest",
                    settled[0].path("manifest").toString());

            expectModel(api, model, seed, operation, "before the restart");

            node.crash();
            node.start(true);
            node.awaitReady(READY_TIMEOUT_MS);
            expectModel(api, model, seed, operation, "after the restart");
        } finally {
            calls.shutdownNow();
            if (node.isAlive()) {
                try {
                    admin.clearFailpoints();
                } catch (Exception alreadyGone) {
                    // The process is stopped next anyway.
                }
            }
            node.close();
            VolumeTools.deleteDirectory(dataDir);
        }
        report.pass();
    }

    /** Five seeded, pairwise different values: alpha, beta, alpha again, gamma, newcomer. */
    static byte[][] values(long seed) {
        Random random = new Random(seed);
        byte[][] values = new byte[5][];
        for (int i = 0; i < values.length; i++) {
            values[i] = new byte[3 + i];
            random.nextBytes(values[i]);
        }
        return values;
    }

    private static void put(ApiClient api, Map<String, byte[]> model, String key, byte[] value,
                            long seed, int[] operation) throws Exception {
        HttpResponse<String> response =
                api.put(key, value, requestId(seed, operation), REQUEST_TIMEOUT_MS);
        check(response.statusCode() == 200, "PUT " + key,
                "HTTP " + response.statusCode() + ": " + response.body());
        model.put(key, value);
    }

    /**
     * {@code POST /admin/v1/storage/flush} or {@code compact}; a
     * {@code 409 MAINTENANCE_CONFLICT} is retried once the running call ends
     * (CP1 §23).
     */
    private static JsonNode maintain(String what, long seed, int[] operation) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + ADMIN_PORT + "/admin/v1/storage/" + what))
                            .header("X-Request-Id", requestId(seed, operation))
                            .header("X-Request-Timeout-Ms", String.valueOf(MAINTENANCE_TIMEOUT_MS))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = MAPPER.readTree(response.body());
            if (response.statusCode() == 409
                    && "MAINTENANCE_CONFLICT".equals(body.path("code").asText())
                    && attempt < MAX_CONFLICT_RETRIES) {
                Await.until(() -> idle(seed, operation), WAIT, ID + ": maintenance to finish");
                continue;
            }
            check(response.statusCode() == 200, what,
                    "HTTP " + response.statusCode() + ": " + response.body());
            return body;
        }
    }

    /** A {@code PUBLISHED} flush with exactly one new table; returns its id. */
    private static long onlyPublished(JsonNode flush) {
        check("PUBLISHED".equals(flush.path("result").asText())
                        && flush.path("publishedTableIds").size() == 1, "flush", flush.toString());
        return flush.path("publishedTableIds").get(0).asLong();
    }

    /** Exact GET of every key and an exact ordered SCAN over all of them. */
    private static void expectModel(ApiClient api, Map<String, byte[]> model, long seed,
                                    int[] operation, String when) throws Exception {
        for (Map.Entry<String, byte[]> entry : model.entrySet()) {
            HttpResponse<String> response =
                    api.get(entry.getKey(), requestId(seed, operation), REQUEST_TIMEOUT_MS);
            check(response.statusCode() == 200, "GET " + entry.getKey() + " " + when,
                    "HTTP " + response.statusCode() + ": " + response.body());
            String actual = hex(Base64.getDecoder().decode(
                    MAPPER.readTree(response.body()).path("value").asText()));
            check(actual.equals(hex(entry.getValue())), "GET " + entry.getKey() + " " + when,
                    "value " + actual + ", expected " + hex(entry.getValue()));
        }

        List<String> expected = new ArrayList<>();
        new TreeSet<>(model.keySet()).forEach(key -> expected.add(key + "=" + hex(model.get(key))));
        HttpResponse<String> response =
                api.scan("a", "z", 100, requestId(seed, operation), REQUEST_TIMEOUT_MS);
        check(response.statusCode() == 200, "SCAN " + when,
                "HTTP " + response.statusCode() + ": " + response.body());
        List<String> actual = new ArrayList<>();
        for (JsonNode item : MAPPER.readTree(response.body()).path("items")) {
            actual.add(item.path("key").asText() + "="
                    + hex(Base64.getDecoder().decode(item.path("value").asText())));
        }
        check(actual.equals(expected), "SCAN " + when, "got " + actual + ", expected " + expected);
    }

    private static boolean idle(long seed, int[] operation) {
        try {
            return "IDLE".equals(status(seed, operation).path("maintenance").path("phase").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static TreeSet<Long> ids(JsonNode array) {
        TreeSet<Long> ids = new TreeSet<>();
        array.forEach(id -> ids.add(id.asLong()));
        return ids;
    }

    private static String failpointState(AdminClient admin, String token) {
        try {
            return MAPPER.readTree(admin.getFailpoint(token).body()).path("state").asText();
        } catch (Exception e) {
            return "UNREADABLE";
        }
    }

    /** {@code GET /admin/v1/storage/status} (CP1 §24). */
    private static JsonNode status(long seed, int[] operation) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + ADMIN_PORT + "/admin/v1/storage/status"))
                        .header("X-Request-Id", requestId(seed, operation))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "storage status",
                "HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    /** Deterministic request ids: {@code <ID>-<seed>-<n>} (plan §14). */
    private static synchronized String requestId(long seed, int[] operation) {
        return ID + "-" + seed + "-" + (++operation[0]);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static void check(boolean condition, String what, String problem) {
        if (!condition) {
            throw new AssertionError(ID + " ORACLE " + what + ": " + problem);
        }
    }
}
