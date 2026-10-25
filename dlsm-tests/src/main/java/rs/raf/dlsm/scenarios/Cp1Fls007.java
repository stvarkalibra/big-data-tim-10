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
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * CP1-FLS-007 — visible backpressure while a flush is paused (CP1 §29.3).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node with a tiny
 *       {@code MEMTABLE_MAX_BYTES = S}; every value alone fills a
 *       generation; {@code sstable.after-write.before-sync} armed with one
 *       {@code PAUSE} that outlasts the test; counters reset.</li>
 *   <li><b>Operations:</b> {@code PUT old=A} fills the active generation;
 *       {@code PUT current=B} rotates it, lets its flush reach the pause and
 *       fills the new active generation; once both are done,
 *       {@code PUT waiting=C} with a long deadline; poll the status until it
 *       shows the backpressure; release the flush; wait for {@code C} and
 *       for maintenance to settle; {@code SIGKILL}, restart, read all
 *       three.</li>
 *   <li><b>Failpoint:</b> {@code PAUSE} inside the first flush, after the
 *       SSTable is written and before its force: the only immutable slot
 *       stays taken.</li>
 *   <li><b>Client outcome:</b> {@code old} and {@code current} succeed;
 *       {@code waiting} does not finish before the release and then succeeds
 *       within its deadline.</li>
 *   <li><b>Logical oracle:</b> after the restart all three exact values are
 *       there.</li>
 *   <li><b>Physical / status oracle:</b> during the pause exactly one active
 *       and one immutable MemTable, the active one needing rotation,
 *       {@code writeBackpressure = true} and at least one waiting writer;
 *       after the release the backpressure ends, maintenance becomes idle
 *       with no immutable MemTable left, and the counters show published
 *       flushes.</li>
 *   <li><b>Proves:</b> a full engine waits for the single immutable slot
 *       instead of dropping writes or queueing more MemTables, the waiting
 *       writer releases the lock the flush needs, and the release wakes it
 *       (CP1 §10, §13). <b>Does not prove:</b> throughput.</li>
 * </ol>
 */
public final class Cp1Fls007 {

    public static final String ID = "CP1-FLS-007";

    private static final int PUBLIC_PORT = 18270;
    private static final int ADMIN_PORT = 18271;

    /** {@code S}: each value alone reaches it, so every PUT fills a generation. */
    static final int MEMTABLE_MAX_BYTES = 512;
    private static final long READY_TIMEOUT_MS = 20_000;
    private static final long REQUEST_TIMEOUT_MS = 5_000;
    /** The longest deadline the server allows (PI §17.3): ample time for the release. */
    private static final long WAITING_TIMEOUT_MS = 10_000;
    /** Longer than the whole scenario: the test releases the pause itself. */
    private static final int PAUSE_TIMEOUT_MS = 60_000;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Cp1Fls007() {
    }

    public static void run(long seed, Report report) throws Exception {
        byte[][] values = values(seed);
        String token = "fls007-" + seed;
        Path dataDir = Files.createTempDirectory("cp1-fls-007-" + seed + "-");
        NodeController node = new NodeController(dataDir, PUBLIC_PORT, ADMIN_PORT)
                .withEnvironment("MEMTABLE_MAX_BYTES", String.valueOf(MEMTABLE_MAX_BYTES));
        AdminClient admin = new AdminClient(ADMIN_PORT);
        ExecutorService clients = Executors.newSingleThreadExecutor();
        int[] operation = {0};

        try {
            node.start(true);
            node.awaitReady(READY_TIMEOUT_MS);
            ApiClient api = new ApiClient(PUBLIC_PORT);
            ApiClient waitingClient = new ApiClient(PUBLIC_PORT);

            long resetEpoch = MAPPER.readTree(adminCall("POST", "/admin/v1/storage/stats/reset",
                    requestId(seed, operation)).body()).path("resetEpoch").asLong();
            HttpResponse<String> armed = admin.setFailpoint(token, """
                    {"name": "%s", "action": "PAUSE", "afterHits": 0, "maxTriggers": 1,
                     "pauseTimeoutMs": %d}
                    """.formatted(FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC, PAUSE_TIMEOUT_MS));
            check(armed.statusCode() == 200, "arm failpoint", armed.body());

            // (1) The first value fills the active generation.
            expectOk(api.put("old", values[0], requestId(seed, operation), REQUEST_TIMEOUT_MS),
                    "PUT old");
            JsonNode afterOld = status(seed, operation);
            check(afterOld.path("activeMemtable").path("rotationRequired").asBoolean()
                            && afterOld.path("immutableMemtable").isNull(), "status after PUT old",
                    "expected a full active generation and no immutable one: " + afterOld);

            // (2)-(3) The second rotates it; its flush reaches the pause.
            expectOk(api.put("current", values[1], requestId(seed, operation), REQUEST_TIMEOUT_MS),
                    "PUT current");
            Await.until(() -> "TRIGGERED".equals(failpointState(admin, token)), WAIT,
                    ID + ": the flush never reached " + FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC);

            // (4) The third writer must wait for the only immutable slot.
            String waitingRequest = requestId(seed, operation);
            Future<HttpResponse<String>> waiting = clients.submit(() ->
                    waitingClient.put("waiting", values[2], waitingRequest, WAITING_TIMEOUT_MS));

            // (5) The status shows it: one active and one immutable, the writer waiting.
            JsonNode[] pressured = new JsonNode[1];
            Await.until(() -> {
                try {
                    JsonNode s = status(seed, operation);
                    pressured[0] = s;
                    return !s.path("immutableMemtable").isNull()
                            && s.path("activeMemtable").path("rotationRequired").asBoolean()
                            && s.path("writeBackpressure").asBoolean()
                            && s.path("backpressuredWriters").asLong() >= 1;
                } catch (Exception e) {
                    return false;
                }
            }, WAIT, ID + ": the status never showed the waiting writer");
            check(!waiting.isDone(), "PUT waiting", "finished before the flush was released");
            expectOneActiveOneImmutable(pressured[0]);

            // (6) Releasing the flush publishes it and wakes the writer.
            HttpResponse<String> released = admin.releaseFailpoint(token);
            check(released.statusCode() == 200
                            && MAPPER.readTree(released.body()).path("released").asBoolean(),
                    "release", released.body());
            expectOk(waiting.get(WAITING_TIMEOUT_MS, TimeUnit.MILLISECONDS), "PUT waiting");

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
            }, WAIT, ID + ": maintenance never settled after the release");
            JsonNode after = settled[0];
            check(!after.path("writeBackpressure").asBoolean()
                            && after.path("backpressuredWriters").asLong() == 0,
                    "status after the release", "backpressure did not end: " + after);
            JsonNode stats = after.path("stats");
            check(stats.path("resetEpoch").asLong() == resetEpoch, "counters",
                    "counters were reset by someone else: " + stats.path("resetEpoch"));
            check(stats.path("flushPublications").asLong() >= 1, "counters",
                    "no published flush: " + stats);

            // All three values survive a crash and restart.
            node.crash();
            node.start(true);
            node.awaitReady(READY_TIMEOUT_MS);
            String[] keys = {"old", "current", "waiting"};
            for (int i = 0; i < keys.length; i++) {
                expectValue(api, keys[i], values[i], requestId(seed, operation));
            }
        } finally {
            clients.shutdownNow();
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

    /** Three seeded values, each alone at least {@code S} logical bytes. */
    static byte[][] values(long seed) {
        Random random = new Random(seed);
        byte[][] values = new byte[3][MEMTABLE_MAX_BYTES];
        for (byte[] value : values) {
            random.nextBytes(value);
        }
        return values;
    }

    private static void expectOneActiveOneImmutable(JsonNode status) {
        int active = 0;
        for (JsonNode segment : status.path("walSegments")) {
            if ("ACTIVE".equals(segment.path("role").asText())) {
                active++;
            }
        }
        check(active == 1, "status during the pause",
                "expected exactly one active WAL segment: " + status.path("walSegments"));
        check(status.path("immutableMemtable").path("generation").asLong()
                        < status.path("activeMemtable").path("generation").asLong(),
                "status during the pause", "the immutable generation is not the older one: "
                        + status.path("immutableMemtable"));
    }

    private static void expectOk(HttpResponse<String> response, String what) {
        check(response.statusCode() == 200, what,
                "HTTP " + response.statusCode() + ": " + response.body());
    }

    private static void expectValue(ApiClient api, String key, byte[] expected, String requestId)
            throws Exception {
        HttpResponse<String> response = api.get(key, requestId, REQUEST_TIMEOUT_MS);
        check(response.statusCode() == 200, "GET " + key + " after the restart",
                "HTTP " + response.statusCode() + ": " + response.body());
        byte[] actual = Base64.getDecoder().decode(
                MAPPER.readTree(response.body()).path("value").asText());
        check(Arrays.equals(expected, actual), "GET " + key + " after the restart",
                "value of " + actual.length + " bytes differs from the written one");
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
        HttpResponse<String> response =
                adminCall("GET", "/admin/v1/storage/status", requestId(seed, operation));
        check(response.statusCode() == 200, "storage status",
                "HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static HttpResponse<String> adminCall(String method, String path, String requestId)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + ADMIN_PORT + path))
                        .header("X-Request-Id", requestId)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Deterministic request ids: {@code <ID>-<seed>-<n>} (plan §14). */
    private static String requestId(long seed, int[] operation) {
        return ID + "-" + seed + "-" + (++operation[0]);
    }

    private static void check(boolean condition, String what, String problem) {
        if (!condition) {
            throw new AssertionError(ID + " ORACLE " + what + ": " + problem);
        }
    }
}
