package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.inspect.InspectSSTableCommand;
import rs.raf.dlsm.model.ReferenceModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

final class FlushScenarioChecks {

    static final String SCAN_START = "a";
    static final String SCAN_END = "z";
    static final int SCAN_LIMIT = 1000;

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private FlushScenarioChecks() {
    }

    static HttpResponse<String> postFlush(int adminPort, String requestId) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + adminPort + "/admin/v1/storage/flush"))
                        .header("X-Request-Id", requestId)
                        .header("X-Request-Timeout-Ms", Long.toString(WalScenarioNode.TIMEOUT_MS))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode flush(WalScenarioNode node, int adminPort, String what) throws Exception {
        String requestId = node.nextRequestId();
        HttpResponse<String> response = postFlush(adminPort, requestId);
        node.check(response.statusCode() == 200, what + " (" + requestId + ")",
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        node.check(body.path("protocolVersion").asInt() == 1
                        && "OK".equals(body.path("status").asText())
                        && "FLUSH".equals(body.path("operation").asText()), what,
                "unexpected envelope " + body);
        return body;
    }

    static HttpResponse<String> postCompact(int adminPort, String requestId) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + adminPort + "/admin/v1/storage/compact"))
                        .header("X-Request-Id", requestId)
                        .header("X-Request-Timeout-Ms", Long.toString(WalScenarioNode.TIMEOUT_MS))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode compact(WalScenarioNode node, int adminPort, String what) throws Exception {
        String requestId = node.nextRequestId();
        HttpResponse<String> response = postCompact(adminPort, requestId);
        node.check(response.statusCode() == 200, what + " (" + requestId + ")",
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        node.check(body.path("protocolVersion").asInt() == 1
                        && "OK".equals(body.path("status").asText())
                        && "COMPACTION".equals(body.path("operation").asText()), what,
                "unexpected envelope " + body);
        return body;
    }

    static JsonNode inspect(WalScenarioNode node, long tableId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = InspectSSTableCommand.run(node.dataDir(), tableId,
                new PrintStream(out, true, StandardCharsets.UTF_8));
        String json = out.toString(StandardCharsets.UTF_8);
        node.check(exit == InspectSSTableCommand.EXIT_OK, "inspect sstable " + tableId,
                "exit " + exit + ": " + json);
        return MAPPER.readTree(json);
    }

    static JsonNode awaitIdle(WalScenarioNode node, String scenarioId) {
        JsonNode[] last = new JsonNode[1];
        Await.until(() -> {
            try {
                last[0] = node.storageStatus();
            } catch (Exception e) {
                throw new AssertionError(scenarioId + ": storage status failed while waiting for idle", e);
            }
            return "IDLE".equals(last[0].path("maintenance").path("phase").asText())
                    && last[0].path("immutableMemtable").isNull();
        }, IDLE_TIMEOUT, scenarioId + ": maintenance did not become idle");
        return last[0];
    }

    static void expectModel(WalScenarioNode node, ReferenceModel model, Set<String> keys, String when)
            throws Exception {
        for (String key : keys.stream().sorted().toList()) {
            byte[] expected = model.get(key);
            Optional<byte[]> actual = node.get(key);
            if (expected == null) {
                node.check(actual.isEmpty(), "GET " + key + " " + when,
                        "deleted key returned " + actual.map(FlushScenarioChecks::hex).orElse(""));
            } else {
                node.check(actual.isPresent() && Arrays.equals(expected, actual.get()),
                        "GET " + key + " " + when,
                        "value " + actual.map(FlushScenarioChecks::hex).orElse("<404>")
                                + ", expected " + hex(expected));
            }
        }

        JsonNode scan = node.scan(SCAN_START, SCAN_END, SCAN_LIMIT);
        Map<String, byte[]> expected = model.scan(SCAN_START, SCAN_END, SCAN_LIMIT);
        List<String> scannedKeys = new ArrayList<>();
        for (JsonNode item : scan.path("items")) {
            String key = item.path("key").asText();
            scannedKeys.add(key);
            byte[] value = Base64.getDecoder().decode(item.path("value").asText());
            node.check(expected.containsKey(key) && Arrays.equals(expected.get(key), value),
                    "SCAN " + when, "item " + key + "=" + hex(value) + " is not in the model");
        }
        node.check(scannedKeys.equals(new ArrayList<>(expected.keySet())), "SCAN " + when,
                "keys " + scannedKeys + ", expected " + expected.keySet());
    }

    static List<String> finalTables(Path sstDir) throws IOException {
        try (Stream<Path> files = Files.list(sstDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("sst-") && name.endsWith(".sst"))
                    .sorted()
                    .toList();
        }
    }

    static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode id : array) {
            ids.add(id.asLong());
        }
        return ids;
    }

    static List<Long> tableIds(JsonNode status) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode table : status.path("sstables")) {
            ids.add(table.path("tableId").asLong());
        }
        return ids;
    }

    static String tableFile(long tableId) {
        return "sst-%020d.sst".formatted(tableId);
    }

    static String hex(byte[] bytes) {
        return bytes.length == 0 ? "<empty>" : HexFormat.of().formatHex(bytes);
    }
}
