package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.AdminClient;
import rs.raf.dlsm.harness.ApiClient;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.NodeController;
import rs.raf.dlsm.harness.VolumeTools;
import rs.raf.dlsm.inspect.InspectWal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * One standalone node for a WAL scenario (CP1 §29.2): a child process of the
 * production server jar over the scenario's own data directory and ports,
 * restarted over the same volume (plan §14: a restart is always a new
 * process, a crash is {@code CRASH} or {@code SIGKILL}).
 *
 * <p>Besides the harness clients it covers what the WAL scenarios need and
 * the harness does not offer yet: the storage status and counter reset
 * (CP1 §24), readiness of a node that is not {@code READY}, waiting for a
 * crashed process to exit, and {@code inspect wal} over the stopped volume
 * (CP1 §25). It uses only public harness APIs.
 *
 * <p>Request IDs are deterministic: {@code <ID>-<seed>-<n>} (plan §14).
 */
final class WalScenarioNode implements AutoCloseable {

    static final long TIMEOUT_MS = 5_000;
    /** Standalone ready within 20 s (plan §18.1). */
    private static final long READY_TIMEOUT_MS = 20_000;
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String scenarioId;
    private final long seed;
    private final Path dataDir;
    private final int adminPort;
    private final NodeController controller;
    private final ApiClient api;
    private final AdminClient admin;
    private final HttpClient http = HttpClient.newHttpClient();
    private int operation;

    private WalScenarioNode(String scenarioId, long seed, Path dataDir,
                            int publicPort, int adminPort) {
        this.scenarioId = scenarioId;
        this.seed = seed;
        this.dataDir = dataDir;
        this.adminPort = adminPort;
        this.controller = new NodeController(dataDir, publicPort, adminPort);
        this.api = new ApiClient(publicPort);
        this.admin = new AdminClient(adminPort);
    }

    /** A node over a new, empty data directory; nothing runs yet. */
    static WalScenarioNode create(String scenarioId, long seed, int publicPort, int adminPort)
            throws IOException {
        Path dataDir = Files.createTempDirectory(
                scenarioId.toLowerCase(Locale.ROOT) + "-" + seed + "-");
        return new WalScenarioNode(scenarioId, seed, dataDir, publicPort, adminPort);
    }

    Path dataDir() {
        return dataDir;
    }

    /** {@code lsm/wal/wal-<20 digits>.log} (CP1 §14). */
    Path walSegment(long segmentId) {
        return dataDir.resolve("lsm").resolve("wal")
                .resolve(String.format("wal-%020d.log", segmentId));
    }

    // ===== Process =====

    /** Starts a new process in test mode over the same volume and waits for READY. */
    void startReady() throws Exception {
        controller.start(true);
        controller.awaitReady(READY_TIMEOUT_MS);
    }

    /** Starts a new process in test mode without waiting for any lifecycle. */
    void start() throws IOException {
        controller.start(true);
    }

    /** {@code SIGKILL}: no flush, no orderly shutdown (CP1-WAL-001). */
    void kill() {
        controller.crash();
    }

    /** Waits until the process has exited by itself, e.g. after a {@code CRASH} failpoint. */
    void awaitExit() {
        Await.until(() -> !controller.isAlive(), EXIT_TIMEOUT,
                scenarioId + ": node process to exit");
    }

    // ===== Public API =====

    String nextRequestId() {
        return scenarioId + "-" + seed + "-" + (++operation);
    }

    /** {@code PUT}; must answer {@code 200}. */
    void put(String key, byte[] value) throws Exception {
        String requestId = nextRequestId();
        expectStatus(200, api.put(key, value, requestId, TIMEOUT_MS), "PUT " + key, requestId);
    }

    /** {@code DELETE}; must answer {@code 200}, also for a key that never existed. */
    void delete(String key) throws Exception {
        String requestId = nextRequestId();
        expectStatus(200, api.delete(key, requestId, TIMEOUT_MS), "DELETE " + key, requestId);
    }

    /**
     * {@code PUT} that a {@code CRASH} failpoint may cut off: the response,
     * or empty when the connection broke without one (CP1-WAL-005, WAL-006).
     */
    Optional<HttpResponse<String>> putMayCrash(String key, byte[] value, String requestId) {
        try {
            return Optional.of(api.put(key, value, requestId, TIMEOUT_MS));
        } catch (Exception noResponse) {
            return Optional.empty();
        }
    }

    /** {@code GET}: the exact value, or empty for {@code 404 NOT_FOUND}; anything else fails. */
    Optional<byte[]> get(String key) throws Exception {
        String requestId = nextRequestId();
        HttpResponse<String> response = api.get(key, requestId, TIMEOUT_MS);
        if (response.statusCode() == 404) {
            check("NOT_FOUND".equals(MAPPER.readTree(response.body()).path("code").asText()),
                    "GET " + key, "404 without NOT_FOUND: " + response.body());
            return Optional.empty();
        }
        expectStatus(200, response, "GET " + key, requestId);
        return Optional.of(Base64.getDecoder().decode(
                MAPPER.readTree(response.body()).path("value").asText()));
    }

    /** {@code SCAN}; must answer {@code 200}. Returns the body. */
    JsonNode scan(String start, String end, int limit) throws Exception {
        String requestId = nextRequestId();
        HttpResponse<String> response = api.scan(start, end, limit, requestId, TIMEOUT_MS);
        expectStatus(200, response, "SCAN[" + start + "," + end + ")", requestId);
        return MAPPER.readTree(response.body());
    }

    /** The raw {@code GET} response, for a node that must refuse it. */
    HttpResponse<String> rawGet(String key) throws Exception {
        return api.get(key, nextRequestId(), TIMEOUT_MS);
    }

    // ===== Admin API =====

    /** {@code GET /admin/v1/storage/status} (CP1 §24); must answer {@code 200}. */
    JsonNode storageStatus() throws Exception {
        HttpResponse<String> response = adminCall("GET", "/admin/v1/storage/status");
        check(response.statusCode() == 200, "storage status",
                "HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    /** One counter from the storage status. */
    long stat(String name) throws Exception {
        JsonNode value = storageStatus().path("stats").path(name);
        check(value.isIntegralNumber(), "storage status", "no counter " + name);
        return value.asLong();
    }

    /** {@code POST /admin/v1/storage/stats/reset} (CP1 §24.1); returns the new {@code resetEpoch}. */
    long resetStats() throws Exception {
        HttpResponse<String> response = adminCall("POST", "/admin/v1/storage/stats/reset");
        check(response.statusCode() == 200, "stats reset",
                "HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body()).path("resetEpoch").asLong();
    }

    /** Arms a failpoint rule with a JSON body (PI §21.1); must answer {@code 200}. */
    void armFailpoint(String token, String jsonBody) throws Exception {
        HttpResponse<String> response = admin.setFailpoint(token, jsonBody);
        check(response.statusCode() == 200, "arm failpoint " + token,
                "HTTP " + response.statusCode() + ": " + response.body());
    }

    /**
     * Polls {@code /health/ready} until it reports {@code lifecycle} and
     * returns that body; for a node that must stay unready, e.g.
     * {@code FAILED} after a corrupt WAL (CP1 §21.4).
     */
    JsonNode awaitLifecycle(String lifecycle) {
        JsonNode[] last = new JsonNode[1];
        Await.until(() -> {
            if (!controller.isAlive()) {
                throw new AssertionError(scenarioId + ": node exited while waiting for "
                        + lifecycle);
            }
            try {
                last[0] = MAPPER.readTree(readiness().body());
                return lifecycle.equals(last[0].path("lifecycle").asText());
            } catch (Exception notYetListening) {
                return false;
            }
        }, Duration.ofMillis(READY_TIMEOUT_MS), scenarioId + ": lifecycle " + lifecycle);
        return last[0];
    }

    /** {@code GET /health/ready} as it is: {@code 503} while the node is not ready. */
    HttpResponse<String> readiness() throws Exception {
        return adminCall("GET", "/health/ready");
    }

    private HttpResponse<String> adminCall(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + adminPort + path))
                .header("X-Request-Id", nextRequestId())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ===== Offline inspection =====

    /** {@code inspect wal} result: the inspector's exit code and its JSON report. */
    record WalInspection(int exitCode, JsonNode report) {
    }

    /**
     * {@code dbctl inspect wal} over this volume (CP1 §25); read-only, so it
     * runs only while the node is stopped.
     */
    WalInspection inspectWal() throws Exception {
        check(!controller.isAlive(), "inspect wal", "the node is still running");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = InspectWal.run(dataDir, new PrintStream(out, true, StandardCharsets.UTF_8));
        return new WalInspection(exitCode, MAPPER.readTree(out.toString(StandardCharsets.UTF_8)));
    }

    // ===== Checks =====

    void check(boolean condition, String what, String problem) {
        if (!condition) {
            throw new AssertionError(scenarioId + " ORACLE " + what + ": " + problem);
        }
    }

    private void expectStatus(int status, HttpResponse<String> response,
                              String what, String requestId) {
        check(response.statusCode() == status, what,
                "HTTP " + response.statusCode() + " (request " + requestId + "): "
                        + response.body());
    }

    /**
     * Idempotent cleanup, also after a failure: failpoints cleared, the
     * process stopped, only this scenario's directory deleted (plan §14).
     */
    @Override
    public void close() throws IOException {
        if (controller.isAlive()) {
            try {
                admin.clearFailpoints();
            } catch (Exception alreadyGone) {
                // The process is killed next anyway.
            }
        }
        controller.close();
        VolumeTools.deleteDirectory(dataDir);
    }
}
