package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.ApiClient;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.NodeController;
import rs.raf.dlsm.harness.VolumeTools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * One server process of CP1-MDL-002 over a given data directory: the
 * campaign's own node, a second process over the same directory, or a node
 * over a damaged clone. A restart is always a new process (plan §14).
 *
 * <p>Only a node that created its directory deletes it on close, so a
 * second process never removes the first one's volume.
 */
final class Mdl002Node implements AutoCloseable {

    static final long TIMEOUT_MS = 5_000;
    /** A {@code CRASH} failpoint halts the process with this code. */
    static final int CRASH_EXIT_CODE = 137;
    /** Standalone ready within 20 s (plan §18.1). */
    private static final long READY_TIMEOUT_MS = 20_000;
    private static final Duration WAIT = Duration.ofSeconds(20);
    /** A manual flush or compaction waits for its job up to this deadline (CP1 §23). */
    private static final long MAINTENANCE_TIMEOUT_MS = 10_000;
    private static final String BOOTSTRAP_ENV = "DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final Path dataDir;
    private final boolean ownsDirectory;
    private final int adminPort;
    private final NodeController controller;
    private final ApiClient api;
    private final HttpClient http = HttpClient.newHttpClient();

    Mdl002Node(String name, Path dataDir, boolean ownsDirectory, int publicPort, int adminPort) {
        this.name = name;
        this.dataDir = dataDir;
        this.ownsDirectory = ownsDirectory;
        this.adminPort = adminPort;
        this.controller = new NodeController(dataDir, publicPort, adminPort);
        this.api = new ApiClient(publicPort);
    }

    /** A server setting for every later start, e.g. {@code MEMTABLE_MAX_BYTES}. */
    Mdl002Node withEnvironment(String variable, String value) {
        controller.withEnvironment(variable, value);
        return this;
    }

    Path dataDir() {
        return dataDir;
    }

    // ===== Process =====

    /** A new process in test mode; must report {@code READY} within 20 s. */
    void startReady() throws Exception {
        controller.start(true);
        try {
            controller.awaitReady(READY_TIMEOUT_MS);
        } catch (RuntimeException notReady) {
            throw new AssertionError(Cp1Mdl002.ID + " ORACLE " + name
                    + ": a start without failpoints did not become READY: " + notReady.getMessage(),
                    notReady);
        }
    }

    /** A new process in test mode, without waiting for any lifecycle. */
    void start() throws IOException {
        controller.start(true);
    }

    /** A new process with bootstrap rules for this start only (CP1 §26.3). */
    void startWithBootstrap(String rulesJson) throws IOException {
        controller.withEnvironment(BOOTSTRAP_ENV, Base64.getEncoder()
                .encodeToString(rulesJson.getBytes(StandardCharsets.UTF_8)));
        try {
            controller.start(true);
        } finally {
            controller.withoutEnvironment(BOOTSTRAP_ENV);
        }
    }

    /** {@code SIGKILL} with nothing in flight. */
    void kill() {
        controller.crash();
    }

    boolean isAlive() {
        return controller.isAlive();
    }

    /** The process must stop by itself through a {@code CRASH} failpoint (exit code 137). */
    void awaitHalt(String what) {
        Await.until(() -> !controller.isAlive(), WAIT, Cp1Mdl002.ID + ": " + what + ": process to halt");
        int code = controller.exitCode().orElse(-1);
        Cp1Mdl002.check(code == CRASH_EXIT_CODE, what,
                "exit code " + code + ", expected " + CRASH_EXIT_CODE + " from the CRASH failpoint");
    }

    /**
     * A start that a {@code CRASH} failpoint must stop during recovery: it may
     * never report ready on the way, and it must halt with 137, not with a
     * configuration error ({@code 2}) or a bind failure ({@code 1}).
     */
    void awaitCrashBeforeReady(String what) {
        Await.until(() -> {
            if (!controller.isAlive()) {
                return true;
            }
            try {
                Cp1Mdl002.check(readiness().statusCode() != 200, what, "the node became ready");
            } catch (IOException | InterruptedException notListeningOrAlreadyGone) {
                return false;
            }
            return false;
        }, WAIT, Cp1Mdl002.ID + ": " + what + ": process to halt");
        int code = controller.exitCode().orElse(-1);
        Cp1Mdl002.check(code == CRASH_EXIT_CODE, what,
                "exit code " + code + ", expected " + CRASH_EXIT_CODE);
    }

    /**
     * The end of a start that must fail: {@code /health/ready} with lifecycle
     * {@code FAILED}. {@code READY} on the way fails at once, since a node
     * must never serve a damaged or foreign volume (CP1 §21.4).
     */
    JsonNode awaitStartupFailure(String what) {
        JsonNode[] last = new JsonNode[1];
        Await.until(() -> {
            Cp1Mdl002.check(controller.isAlive(), what,
                    "the process exited (code " + controller.exitCode() + ") instead of staying FAILED");
            try {
                HttpResponse<String> response = readiness();
                last[0] = MAPPER.readTree(response.body());
                Cp1Mdl002.check(response.statusCode() != 200, what, "the node became READY");
            } catch (IOException | InterruptedException notYetListening) {
                return false;
            }
            return "FAILED".equals(last[0].path("lifecycle").asText());
        }, WAIT, Cp1Mdl002.ID + ": " + what + ": lifecycle FAILED");
        return last[0];
    }

    /** Polls {@code /health/ready} until it reports {@code lifecycle}; the process must stay up. */
    JsonNode awaitLifecycle(String lifecycle, String what) {
        JsonNode[] last = new JsonNode[1];
        Await.until(() -> {
            Cp1Mdl002.check(controller.isAlive(), what, "the process exited while waiting for " + lifecycle);
            try {
                last[0] = MAPPER.readTree(readiness().body());
            } catch (IOException | InterruptedException notYetListening) {
                return false;
            }
            return lifecycle.equals(last[0].path("lifecycle").asText());
        }, WAIT, Cp1Mdl002.ID + ": " + what + ": lifecycle " + lifecycle);
        return last[0];
    }

    // ===== Public API =====

    HttpResponse<String> put(String key, byte[] value, String requestId) throws Exception {
        return api.put(key, value, requestId, TIMEOUT_MS);
    }

    HttpResponse<String> delete(String key, String requestId) throws Exception {
        return api.delete(key, requestId, TIMEOUT_MS);
    }

    HttpResponse<String> get(String key, String requestId) throws Exception {
        return api.get(key, requestId, TIMEOUT_MS);
    }

    HttpResponse<String> scan(String start, String end, int limit, String requestId) throws Exception {
        return api.scan(start, end, limit, requestId, TIMEOUT_MS);
    }

    /**
     * A request that a {@code CRASH} failpoint must cut off: empty when the
     * connection broke without a response, the response otherwise.
     */
    static Optional<HttpResponse<String>> mayCrash(Callable<HttpResponse<String>> request) {
        try {
            return Optional.of(request.call());
        } catch (Exception noResponse) {
            return Optional.empty();
        }
    }

    // ===== Admin API =====

    /** {@code POST /admin/v1/storage/flush} with the request ID the failpoint filter sees. */
    HttpResponse<String> flush(String requestId) throws Exception {
        return maintenance("/admin/v1/storage/flush", requestId);
    }

    /** {@code POST /admin/v1/storage/compact} with the request ID the failpoint filter sees. */
    HttpResponse<String> compact(String requestId) throws Exception {
        return maintenance("/admin/v1/storage/compact", requestId);
    }

    /** {@code GET /admin/v1/storage/status} (CP1 §24); must answer {@code 200}. */
    JsonNode status() throws Exception {
        HttpResponse<String> response = admin("GET", "/admin/v1/storage/status", null);
        Cp1Mdl002.check(response.statusCode() == 200, name + " storage status",
                "HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    /** {@code POST /admin/v1/storage/stats/reset}; returns the new {@code resetEpoch}. */
    long resetStats() throws Exception {
        HttpResponse<String> response = admin("POST", "/admin/v1/storage/stats/reset", null);
        Cp1Mdl002.check(response.statusCode() == 200, name + " stats reset",
                "HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body()).path("resetEpoch").asLong();
    }

    /** Arms a rule (PI §21.1); an unknown name or action is a harness error, never ignored. */
    void arm(String token, String ruleJson) throws Exception {
        HttpResponse<String> response = admin("PUT", "/admin/v1/failpoints/" + token, ruleJson);
        Cp1Mdl002.check(response.statusCode() == 200, "arm " + token + " " + ruleJson,
                "HTTP " + response.statusCode() + ": " + response.body());
    }

    void disarm(String token) throws Exception {
        HttpResponse<String> response = admin("DELETE", "/admin/v1/failpoints/" + token, null);
        Cp1Mdl002.check(response.statusCode() / 100 == 2, "disarm " + token,
                "HTTP " + response.statusCode() + ": " + response.body());
    }

    /** {@code GET /health/ready} as it is: {@code 503} while the node is not ready. */
    HttpResponse<String> readiness() throws IOException, InterruptedException {
        return admin("GET", "/health/ready", null);
    }

    /**
     * Waits until nothing runs in the background: the node is {@code READY},
     * no frozen generation waits and maintenance is {@code IDLE}.
     */
    JsonNode awaitQuiet(String what) {
        JsonNode[] last = new JsonNode[1];
        Await.until(() -> {
            try {
                last[0] = status();
            } catch (Exception e) {
                throw new AssertionError(Cp1Mdl002.ID + ": " + what + ": storage status failed", e);
            }
            JsonNode maintenance = last[0].path("maintenance");
            JsonNode frozen = last[0].path("immutableMemtable");
            return "READY".equals(last[0].path("lifecycle").asText())
                    && (frozen.isMissingNode() || frozen.isNull())
                    && "IDLE".equals(maintenance.path("phase").asText())
                    && "NONE".equals(maintenance.path("operation").asText());
        }, WAIT, Cp1Mdl002.ID + ": " + what + ": maintenance to settle");
        return last[0];
    }

    private HttpResponse<String> maintenance(String path, String requestId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + adminPort + path))
                .header("X-Request-Id", requestId)
                .header("X-Request-Timeout-Ms", Long.toString(MAINTENANCE_TIMEOUT_MS))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> admin(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + adminPort + path));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Stops the process; deletes the directory only if this node created it. */
    @Override
    public void close() throws IOException {
        controller.close();
        if (ownsDirectory) {
            VolumeTools.deleteDirectory(dataDir);
        }
    }
}