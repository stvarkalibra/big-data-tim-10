package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.model.ReferenceModel;

import java.io.IOException;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * CP1-FLS-001 — manual flush, no-op flush and restart (CP1 §29.3).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node over an empty data
 *       directory, default {@code MEMTABLE_MAX_BYTES}, ready; manifest
 *       generation 0 and no SSTable.</li>
 *   <li><b>Operations:</b> a seeded mix of {@code PUT}, overwrite, empty
 *       value and {@code DELETE} recorded in the reference model, with the
 *       greatest mutation sequence {@code T}; {@code POST /admin/v1/storage/flush};
 *       wait until maintenance is idle; a second flush without new mutations;
 *       counters reset, every {@code GET} and one full {@code SCAN}; kill and
 *       restart over the same volume; counters reset and the same reads.</li>
 *   <li><b>Failpoint or corruption:</b> none; the restart is a {@code SIGKILL}.</li>
 *   <li><b>Client outcome:</b> every call succeeds. The first flush returns
 *       {@code PUBLISHED}, {@code targetSequence = T}, a non-empty
 *       {@code publishedTableIds} and {@code flushedThroughSequence >= T};
 *       the second returns {@code NO_OP}, no tables and the same manifest
 *       generation.</li>
 *   <li><b>Logical oracle:</b> before and after the restart every
 *       {@code GET} and the full {@code SCAN} match the model exactly,
 *       deleted keys are {@code 404 NOT_FOUND} and the empty value is
 *       {@code 200} with zero bytes.</li>
 *   <li><b>Physical / status oracle:</b> after the first flush there is no
 *       frozen generation, the manifest lists exactly the published tables,
 *       every listed {@code sst-<id>.sst} exists and no other final table does,
 *       the covered WAL segment is gone (or {@code COVERED_PENDING_DELETE})
 *       and only the active segment remains; the restart keeps the same
 *       manifest, its high-water mark and {@code nextSequence = T + 1}; the
 *       reads after each reset load SSTable data blocks.</li>
 *   <li><b>Proves:</b> a published SSTable takes over durability from the WAL
 *       and the frozen MemTable (I4, I5, I9), and a flush with nothing to do
 *       publishes nothing. <b>Does not prove:</b> safety of a crash between the
 *       publication steps (CP1-FLS-002 to CP1-FLS-006).</li>
 * </ol>
 */
public final class Cp1Fls001 {

    public static final String ID = "CP1-FLS-001";

    private static final int PUBLIC_PORT = 18210;
    private static final int ADMIN_PORT = 18211;
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);
    private static final String SCAN_START = "a";
    private static final String SCAN_END = "z";
    private static final int SCAN_LIMIT = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Cp1Fls001() {
    }

    record Mutation(String key, byte[] value) {
        boolean isDelete() {
            return value == null;
        }
    }

    static List<Mutation> mutations(long seed) {
        Random random = new Random(seed);
        List<Mutation> mutations = new ArrayList<>();
        mutations.add(new Mutation("alpha", bytes(random, 6)));
        mutations.add(new Mutation("bravo", bytes(random, 9)));
        mutations.add(new Mutation("alpha", bytes(random, 13)));
        mutations.add(new Mutation("charlie", new byte[0]));
        mutations.add(new Mutation("delta", bytes(random, 4)));
        mutations.add(new Mutation("delta", null));
        mutations.add(new Mutation("echo", null));
        mutations.add(new Mutation("foxtrot", bytes(random, 1 + random.nextInt(32))));
        return mutations;
    }

    public static void run(long seed, Report report) throws Exception {
        List<Mutation> mutations = mutations(seed);
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new HashSet<>();

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();

            JsonNode fresh = node.storageStatus();
            node.check(fresh.path("manifest").path("generation").asLong(-1) == 0
                            && fresh.path("sstables").isEmpty(), "initial state",
                    "expected manifest generation 0 and no SSTable: " + fresh.path("manifest"));

            for (Mutation mutation : mutations) {
                keys.add(mutation.key());
                if (mutation.isDelete()) {
                    node.delete(mutation.key());
                    model.delete(mutation.key());
                } else {
                    node.put(mutation.key(), mutation.value());
                    model.put(mutation.key(), mutation.value());
                }
            }
            long target = mutations.size();
            node.check(node.storageStatus().path("nextSequence").asLong() == target + 1, "setup",
                    "the mix should use sequences 1.." + target);

            JsonNode first = flush(node, "first flush");
            node.check("PUBLISHED".equals(first.path("result").asText()), "first flush",
                    "result " + first.path("result") + ", expected PUBLISHED");
            node.check(first.path("targetSequence").asLong() == target, "first flush",
                    "targetSequence " + first.path("targetSequence") + ", expected " + target);
            node.check(first.path("publishedTableIds").size() > 0, "first flush",
                    "no published table");
            node.check(first.path("flushedThroughSequence").asLong() >= target, "first flush",
                    "flushedThroughSequence " + first.path("flushedThroughSequence")
                            + " is below the target " + target);
            long generation = first.path("manifestGeneration").asLong();
            List<Long> published = ids(first.path("publishedTableIds"));

            JsonNode settled = awaitIdle(node);
            expectPublishedState(node, settled, generation, published, target);

            JsonNode second = flush(node, "second flush");
            node.check("NO_OP".equals(second.path("result").asText()), "second flush",
                    "result " + second.path("result") + ", expected NO_OP");
            node.check(second.path("publishedTableIds").isArray()
                            && second.path("publishedTableIds").isEmpty(), "second flush",
                    "publishedTableIds " + second.path("publishedTableIds") + ", expected []");
            node.check(second.path("manifestGeneration").asLong() == generation, "second flush",
                    "manifest generation moved from " + generation + " to "
                            + second.path("manifestGeneration"));
            node.check(second.path("targetSequence").asLong() == target, "second flush",
                    "targetSequence " + second.path("targetSequence") + ", expected " + target);
            node.check(node.storageStatus().path("manifest").path("generation").asLong() == generation,
                    "second flush", "status shows a new manifest generation after NO_OP");

            expectReadsFromTables(node, model, keys, "before restart");

            node.kill();
            node.startReady();

            JsonNode recovered = node.storageStatus();
            expectPublishedState(node, recovered, generation, published, target);
            node.check(recovered.path("nextSequence").asLong() == target + 1, "restart",
                    "nextSequence " + recovered.path("nextSequence") + ", expected " + (target + 1));

            expectReadsFromTables(node, model, keys, "after restart");
        }
        report.pass();
    }

    private static JsonNode flush(WalScenarioNode node, String what) throws Exception {
        String requestId = node.nextRequestId();
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + ADMIN_PORT + "/admin/v1/storage/flush"))
                        .header("X-Request-Id", requestId)
                        .header("X-Request-Timeout-Ms", Long.toString(WalScenarioNode.TIMEOUT_MS))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        node.check(response.statusCode() == 200, what + " (" + requestId + ")",
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        node.check(body.path("protocolVersion").asInt() == 1
                        && "OK".equals(body.path("status").asText())
                        && "FLUSH".equals(body.path("operation").asText()), what,
                "unexpected envelope " + body);
        return body;
    }

    private static JsonNode awaitIdle(WalScenarioNode node) throws Exception {
        JsonNode[] last = new JsonNode[1];
        Await.until(() -> {
            try {
                last[0] = node.storageStatus();
            } catch (Exception e) {
                throw new AssertionError(ID + ": storage status failed while waiting for idle", e);
            }
            return "IDLE".equals(last[0].path("maintenance").path("phase").asText())
                    && last[0].path("immutableMemtable").isNull();
        }, IDLE_TIMEOUT, ID + ": maintenance did not become idle");
        return last[0];
    }

    private static void expectPublishedState(WalScenarioNode node, JsonNode status, long generation,
                                             List<Long> published, long target) throws IOException {
        JsonNode manifest = status.path("manifest");
        node.check(manifest.path("generation").asLong() == generation, "manifest",
                "generation " + manifest.path("generation") + ", expected " + generation);
        node.check(manifest.path("flushedThroughSequence").asLong() >= target, "manifest",
                "flushedThroughSequence " + manifest.path("flushedThroughSequence")
                        + " is below the target " + target);
        node.check(status.path("immutableMemtable").isNull(), "memtable",
                "a frozen generation is still waiting: " + status.path("immutableMemtable"));

        List<Long> listed = new ArrayList<>();
        for (JsonNode table : status.path("sstables")) {
            listed.add(table.path("tableId").asLong());
            node.check(table.path("maxSequence").asLong()
                            <= manifest.path("flushedThroughSequence").asLong(), "manifest",
                    "table " + table.path("tableId") + " is above the high-water mark");
        }
        node.check(listed.equals(published), "manifest",
                "manifest lists tables " + listed + ", the flush published " + published);
        node.check(manifest.path("sstableCount").asInt() == listed.size(), "manifest",
                "sstableCount " + manifest.path("sstableCount") + " for " + listed.size() + " tables");

        Path sstDir = node.dataDir().resolve("lsm").resolve("sst");
        List<String> expectedFiles = published.stream().map(Cp1Fls001::tableFile).sorted().toList();
        node.check(finalTables(sstDir).equals(expectedFiles), "sst directory",
                "final tables " + finalTables(sstDir) + ", expected exactly " + expectedFiles);

        long activeSegments = 0;
        for (JsonNode segment : status.path("walSegments")) {
            String role = segment.path("role").asText();
            node.check("ACTIVE".equals(role) || "COVERED_PENDING_DELETE".equals(role), "wal",
                    "segment " + segment.path("segmentId") + " still has role " + role);
            if ("ACTIVE".equals(role)) {
                activeSegments++;
            }
        }
        node.check(activeSegments == 1, "wal", "expected one active segment: " + status.path("walSegments"));
        node.check(!Files.exists(node.walSegment(1)), "wal",
                "the segment that protected the flushed generation was not deleted");
    }

    private static void expectReadsFromTables(WalScenarioNode node, ReferenceModel model,
                                              Set<String> keys, String when) throws Exception {
        long epoch = node.resetStats();
        for (String key : keys.stream().sorted().toList()) {
            byte[] expected = model.get(key);
            Optional<byte[]> actual = node.get(key);
            if (expected == null) {
                node.check(actual.isEmpty(), "GET " + key + " " + when,
                        "deleted key returned " + actual.map(Cp1Fls001::hex).orElse(""));
            } else {
                node.check(actual.isPresent() && Arrays.equals(expected, actual.get()),
                        "GET " + key + " " + when,
                        "value " + actual.map(Cp1Fls001::hex).orElse("<404>") + ", expected " + hex(expected));
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

        JsonNode stats = node.storageStatus().path("stats");
        node.check(stats.path("resetEpoch").asLong() == epoch, "counters " + when,
                "counters were reset by someone else");
        node.check(stats.path("sstableDataBlocksRead").asLong() > 0, "counters " + when,
                "reads did not load any SSTable data block");
    }

    private static List<String> finalTables(Path sstDir) throws IOException {
        try (Stream<Path> files = Files.list(sstDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("sst-") && name.endsWith(".sst"))
                    .sorted()
                    .toList();
        }
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode id : array) {
            ids.add(id.asLong());
        }
        return ids;
    }

    static String tableFile(long tableId) {
        return "sst-%020d.sst".formatted(tableId);
    }

    private static byte[] bytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String hex(byte[] bytes) {
        return bytes.length == 0 ? "<empty>" : HexFormat.of().formatHex(bytes);
    }
}
