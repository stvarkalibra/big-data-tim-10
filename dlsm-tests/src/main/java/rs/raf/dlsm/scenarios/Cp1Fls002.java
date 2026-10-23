package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.AdminClient;
import rs.raf.dlsm.harness.ApiClient;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.NodeController;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.harness.VolumeTools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * CP1-FLS-002 — crash before force or before the final rename of an SSTable (CP1 §29.3).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node. Confirmed mutations
 *       are written but not flushed, so they exist only in the WAL.</li>
 *   <li><b>Failpoint:</b> for each hook (a) {@code sstable.after-write.before-sync}
 *       and (b) {@code sstable.after-sync.before-rename}, a manual flush is
 *       started and the process halts with {@code CRASH} at that point.</li>
 *   <li><b>Client outcome:</b> the flush never receives a response.</li>
 *   <li><b>Logical oracle:</b> after restart every confirmed key has the
 *       value from the model and the deleted key is {@code NOT_FOUND}.</li>
 *   <li><b>Physical oracle:</b> the manifest {@code M} is unchanged before
 *       and after recovery, no table is published in {@code sst/}, the
 *       temporary output is removed from {@code tmp/}, the
 *       {@code temporaryFilesRemoved} counter covers it, and at least one
 *       WAL segment remains until a later successful flush.</li>
 *   <li><b>Proves:</b> an unpublished, possibly complete, temporary SSTable
 *       is never authoritative. <b>Does not prove:</b> the
 *       {@code flush.*} hooks after publication (FLS-003 to FLS-004).</li>
 * </ol>
 */
public final class Cp1Fls002 {

    public static final String ID = "CP1-FLS-002";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final long TIMEOUT_MS = 5000;
    private static final int READY_TIMEOUT_MS = 20_000;

    /** Hooks (a) and (b) of the specification. */
    private static final List<String> HOOKS = List.of(
            "sstable.after-write.before-sync",
            "sstable.after-sync.before-rename");

    /** Each hook runs on its own ports, so runs never collide. */
    private static final int BASE_PUBLIC_PORT = 18094;

    private Cp1Fls002() {
    }

    public static void run(long seed, Report report) throws Exception {
        for (int index = 0; index < HOOKS.size(); index++) {
            runHook(seed, index, HOOKS.get(index));
        }
        report.pass();
    }

    private static void runHook(long seed, int index, String hook) throws Exception {
        int publicPort = BASE_PUBLIC_PORT + 2 * index;
        int adminPort = publicPort + 1;
        Path dataDir = Files.createTempDirectory("cp1-fls-002-" + seed + "-" + index + "-");

        try (NodeController node = new NodeController(dataDir, publicPort, adminPort)) {
            node.start(true);
            node.awaitReady(READY_TIMEOUT_MS);

            Steps steps = new Steps(new ApiClient(publicPort), seed, index);
            AdminClient admin = new AdminClient(adminPort);

            // Confirmed mutations, none of them flushed yet.
            Map<String, String> model = new TreeMap<>();
            steps.put(model, "alpha", "A1");
            steps.put(model, "beta", "B");
            steps.put(model, "epsilon", "");
            steps.put(model, "omega", "O");
            steps.put(model, "alpha", "A2");
            steps.delete(model, "beta");

            JsonNode manifestBefore = readManifest(dataDir);
            check(manifestBefore.path("sstables").isEmpty(), hook,
                    "expected no published tables before the flush");

            HttpResponse<String> armed = admin.setFailpoint(
                    "fls-002-" + index, hook, "CRASH", 0, 1, 0);
            check(armed.statusCode() / 100 == 2, hook,
                    "failpoint not armed: HTTP " + armed.statusCode() + " " + armed.body());

            try {
                HttpResponse<String> flush = admin.flush();
                throw new AssertionError(ID + " ORACLE " + hook
                        + ": flush answered HTTP " + flush.statusCode()
                        + " although the process should have halted");
            } catch (IOException expectedHalt) {
                // The hook halted the process; the connection is gone.
            }

            Await.until(() -> !node.isAlive(), Duration.ofSeconds(10),
                    ID + " " + hook + ": process did not halt");

            // Before recovery, manifest M is still the only authority.
            check(readManifest(dataDir).equals(manifestBefore), hook,
                    "manifest changed before recovery");
            check(!hasFiles(dataDir.resolve("lsm/sst"), "sst-"), hook,
                    "a finalized SSTable appeared before the final rename");

            int temporaryBefore = countFiles(dataDir.resolve("lsm/tmp"));

            node.restart();
            node.awaitReady(READY_TIMEOUT_MS);

            // Logical oracle: the WAL restores every confirmed mutation.
            for (Map.Entry<String, String> entry : model.entrySet()) {
                steps.expectValue(entry.getKey(), entry.getValue());
            }
            steps.expectNotFound("beta");

            // Physical oracle after recovery.
            check(readManifest(dataDir).equals(manifestBefore), hook,
                    "manifest changed by recovery");
            check(!hasFiles(dataDir.resolve("lsm/sst"), "sst-"), hook,
                    "recovery published a table");
            check(countFiles(dataDir.resolve("lsm/tmp")) == 0, hook,
                    "temporary output was not removed");
            check(hasFiles(dataDir.resolve("lsm/wal"), "wal-"), hook,
                    "WAL protecting unflushed mutations is missing");

            long removed = temporaryFilesRemoved(adminPort, hook);
            check(removed >= temporaryBefore, hook,
                    "temporaryFilesRemoved " + removed
                            + " does not cover " + temporaryBefore + " temporary file(s)");
        } finally {
            VolumeTools.deleteDirectory(dataDir);
        }
    }

    private static JsonNode readManifest(Path dataDir) throws IOException {
        return MAPPER.readTree(Files.readString(dataDir.resolve("lsm/MANIFEST.json")));
    }

    private static boolean hasFiles(Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
        }
    }

    private static int countFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return (int) entries.filter(Files::isRegularFile).count();
        }
    }

    private static long temporaryFilesRemoved(int adminPort, String hook) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + adminPort + "/admin/v1/storage/status"))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, hook,
                "status HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readTree(response.body()).path("stats")
                .path("temporaryFilesRemoved").asLong(-1);
    }

    private static void check(boolean condition, String what, String problem) {
        if (!condition) {
            throw new AssertionError(ID + " ORACLE " + what + ": " + problem);
        }
    }

    /** Numbered requests with deterministic ids: {@code <ID>-<seed>-<hook>-<n>} (plan §14). */
    private static final class Steps {
        private static final long TIMEOUT = TIMEOUT_MS;
        private final ApiClient api;
        private final long seed;
        private final int index;
        private int operation;

        Steps(ApiClient api, long seed, int index) {
            this.api = api;
            this.seed = seed;
            this.index = index;
        }

        private String nextRequestId() {
            return ID + "-" + seed + "-" + index + "-" + (++operation);
        }

        void put(Map<String, String> model, String key, String value) throws Exception {
            String requestId = nextRequestId();
            HttpResponse<String> response = api.put(
                    key, value.getBytes(StandardCharsets.UTF_8), requestId, TIMEOUT);
            check(response.statusCode() == 200, "PUT " + key,
                    "HTTP " + response.statusCode() + " (request " + requestId + ")");
            model.put(key, value);
        }

        void delete(Map<String, String> model, String key) throws Exception {
            String requestId = nextRequestId();
            HttpResponse<String> response = api.delete(key, requestId, TIMEOUT);
            check(response.statusCode() == 200, "DELETE " + key,
                    "HTTP " + response.statusCode() + " (request " + requestId + ")");
            model.remove(key);
        }

        void expectValue(String key, String expected) throws Exception {
            String requestId = nextRequestId();
            HttpResponse<String> response = api.get(key, requestId, TIMEOUT);
            check(response.statusCode() == 200, "GET " + key,
                    "HTTP " + response.statusCode() + " (request " + requestId + ")");
            JsonNode body = MAPPER.readTree(response.body());
            String actual = new String(
                    Base64.getDecoder().decode(body.path("value").asText()),
                    StandardCharsets.UTF_8);
            check(expected.equals(actual), "GET " + key,
                    "expected '" + expected + "' but got '" + actual + "'");
        }

        void expectNotFound(String key) throws Exception {
            String requestId = nextRequestId();
            HttpResponse<String> response = api.get(key, requestId, TIMEOUT);
            check(response.statusCode() == 404, "GET " + key,
                    "expected NOT_FOUND (404) but got HTTP " + response.statusCode());
        }
    }
}