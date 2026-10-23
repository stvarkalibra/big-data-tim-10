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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * CP1-API-004 — concurrent mutations of one key (CP1 §29.1, §28.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node, ready; counters
 *       reset; {@code wal.after-sync.before-memtable} armed with
 *       {@code PAUSE}, filtered to the request id of {@code PUT A}.</li>
 *   <li><b>Operations:</b> {@code PUT(alpha,A)} from one client; once the
 *       failpoint is {@code TRIGGERED}, and only then, {@code PUT(alpha,B)}
 *       from a second client; release the pause; wait for both responses
 *       within a bounded time; {@code GET alpha}; {@code SIGKILL}; inspect the
 *       WAL of the stopped volume; restart over it; {@code GET alpha}.</li>
 *   <li><b>Failpoint:</b> {@code PAUSE} right after A's WAL force, before
 *       A reaches the MemTable: A is linearized, but neither visible nor
 *       acknowledged, when B is invoked.</li>
 *   <li><b>Client outcome:</b> both {@code PUT}s answer {@code 200}.</li>
 *   <li><b>Logical oracle:</b> {@code GET} returns exactly {@code B} before
 *       the crash and after the restart.</li>
 *   <li><b>Physical / status oracle:</b> exactly two WAL appends and two
 *       successful forces since the reset; the stopped volume holds two
 *       complete {@code PUT} frames for {@code alpha} with strictly
 *       increasing sequences, A's first; the active MemTable keeps one
 *       record, B with sequence 2.</li>
 *   <li><b>Proves:</b> a mutation invoked after another one's WAL force
 *       gets a later sequence, and a delayed MemTable apply of the earlier
 *       one never overwrites it, in memory or after recovery (I2, I3).
 *       <b>Does not prove:</b> scan snapshots (API-005) or reads through
 *       SSTables.</li>
 * </ol>
 */
public final class Cp1Api004 {

    public static final String ID = "CP1-API-004";

    private static final int PUBLIC_PORT = 18040;
    private static final int ADMIN_PORT = 18041;

    private static final String KEY = "alpha";
    /** Longer than the whole scenario: the test releases the pause itself. */
    private static final int PAUSE_TIMEOUT_MS = 30_000;
    /** The request deadline the server allows at most (PI §17.3). */
    private static final long PUT_TIMEOUT_MS = 10_000;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Api004() {
    }

    public static void run(long seed, Report report) throws Exception {
        byte[][] values = values(seed);
        byte[] a = values[0];
        byte[] b = values[1];
        String requestA = ID + "-" + seed + "-A";
        String requestB = ID + "-" + seed + "-B";
        String token = "api004-" + seed;

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            AdminClient admin = new AdminClient(ADMIN_PORT);
            ApiClient clientA = new ApiClient(PUBLIC_PORT);
            ApiClient clientB = new ApiClient(PUBLIC_PORT);

            long resetEpoch = node.resetStats();
            JsonNode baseline = node.storageStatus().path("stats");
            node.armFailpoint(token, """
                    {"name": "%s", "action": "PAUSE", "afterHits": 0, "maxTriggers": 1,
                     "filters": {"requestId": "%s"}, "pauseTimeoutMs": %d}
                    """.formatted(FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE,
                    requestA, PAUSE_TIMEOUT_MS));

            ExecutorService clients = Executors.newFixedThreadPool(2);
            try {
                Future<HttpResponse<String>> putA =
                        clients.submit(() -> clientA.put(KEY, a, requestA, PUT_TIMEOUT_MS));
                // A's WAL force is done: it is linearized, not yet visible (CP1 §28.2).
                awaitState(admin, token, "TRIGGERED");

                // Only now is B invoked, from another client.
                Future<HttpResponse<String>> putB =
                        clients.submit(() -> clientB.put(KEY, b, requestB, PUT_TIMEOUT_MS));

                HttpResponse<String> released = admin.releaseFailpoint(token);
                node.check(released.statusCode() == 200
                                && MAPPER.readTree(released.body()).path("released").asBoolean(),
                        "release", "HTTP " + released.statusCode() + ": " + released.body());

                expectOk(node, putA.get(WAIT.toMillis(), TimeUnit.MILLISECONDS), requestA, "PUT A");
                expectOk(node, putB.get(WAIT.toMillis(), TimeUnit.MILLISECONDS), requestB, "PUT B");
            } finally {
                clients.shutdownNow();
            }
            expectState(node, admin, token, "RELEASED");

            expectValue(node, b, "GET before the crash");

            JsonNode status = node.storageStatus();
            JsonNode stats = status.path("stats");
            node.check(stats.path("resetEpoch").asLong() == resetEpoch, "counters",
                    "counters were reset by someone else: " + stats.path("resetEpoch"));
            expectDelta(node, baseline, stats, "walAppendsCompleted", 2);
            expectDelta(node, baseline, stats, "walForcesSucceeded", 2);
            JsonNode active = status.path("activeMemtable");
            node.check(active.path("entryCount").asLong() == 1
                            && active.path("maxSequence").asLong() == 2, "active MemTable",
                    "expected one record with sequence 2 (B), got " + active);

            node.kill();
            expectFramesInOrder(node, a.length, b.length);

            node.startReady();
            expectValue(node, b, "GET after the restart");
        }
        report.pass();
    }

    /** {@code A} and {@code B}: seeded bytes of different lengths, so their WAL frames differ. */
    static byte[][] values(long seed) {
        Random random = new Random(seed);
        byte[] a = new byte[5];
        byte[] b = new byte[9];
        random.nextBytes(a);
        random.nextBytes(b);
        return new byte[][] {a, b};
    }

    /** Bounded polling of the visible failpoint state (PI §21.1); never a sleep as proof. */
    private static void awaitState(AdminClient admin, String token, String expected) {
        Await.until(() -> expected.equals(state(admin, token)), WAIT,
                ID + ": failpoint " + token + " to become " + expected);
    }

    private static void expectState(WalScenarioNode node, AdminClient admin, String token,
                                    String expected) {
        String actual = state(admin, token);
        node.check(expected.equals(actual), "failpoint " + token,
                "state " + actual + ", expected " + expected);
    }

    private static String state(AdminClient admin, String token) {
        try {
            return MAPPER.readTree(admin.getFailpoint(token).body()).path("state").asText();
        } catch (Exception e) {
            return "UNREADABLE";
        }
    }

    private static void expectOk(WalScenarioNode node, HttpResponse<String> response,
                                 String requestId, String what) throws Exception {
        node.check(response.statusCode() == 200, what,
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        node.check(requestId.equals(body.path("requestId").asText()), what,
                "requestId " + body.path("requestId") + ", expected " + requestId);
        node.check(body.path("partitionId").asInt(-1) == 0 && body.path("epoch").asInt(-1) == 0,
                what, "partition " + body.path("partitionId") + "/" + body.path("epoch"));
    }

    private static void expectValue(WalScenarioNode node, byte[] expected, String what)
            throws Exception {
        Optional<byte[]> actual = node.get(KEY);
        node.check(actual.isPresent() && Arrays.equals(expected, actual.get()), what,
                "value " + actual.map(Cp1Api004::hex).orElse("NOT_FOUND")
                        + ", expected B " + hex(expected));
    }

    /**
     * {@code inspect wal} of the stopped volume: exactly two complete
     * {@code PUT} frames for {@code alpha}, sequences strictly increasing,
     * A's frame (by its value length) first.
     */
    private static void expectFramesInOrder(WalScenarioNode node, int lengthA, int lengthB)
            throws Exception {
        WalScenarioNode.WalInspection inspection = node.inspectWal();
        JsonNode wal = inspection.report();
        node.check(inspection.exitCode() == 0 && "VALID".equals(wal.path("status").asText()),
                "inspect wal", wal.toString());

        List<JsonNode> frames = new ArrayList<>();
        for (JsonNode segment : wal.path("segments")) {
            for (JsonNode record : segment.path("records")) {
                if (KEY.equals(record.path("key").asText())) {
                    frames.add(record);
                }
            }
        }
        node.check(frames.size() == 2, "inspect wal",
                "expected two frames for " + KEY + ", got " + frames);
        JsonNode first = frames.get(0);
        JsonNode second = frames.get(1);
        node.check(first.path("sequence").asLong() < second.path("sequence").asLong(),
                "inspect wal", "sequences are not increasing: " + frames);
        node.check("PUT".equals(first.path("kind").asText())
                        && first.path("valueLength").asInt() == lengthA
                        && "PUT".equals(second.path("kind").asText())
                        && second.path("valueLength").asInt() == lengthB,
                "inspect wal", "expected A's frame before B's, got " + frames);
    }

    private static void expectDelta(WalScenarioNode node, JsonNode before, JsonNode after,
                                    String counter, long expected) {
        long delta = after.path(counter).asLong() - before.path(counter).asLong();
        node.check(delta == expected, "counters",
                counter + " grew by " + delta + ", expected " + expected);
    }

    private static String hex(byte[] bytes) {
        return bytes.length == 0 ? "<empty>" : HexFormat.of().formatHex(bytes);
    }
}
