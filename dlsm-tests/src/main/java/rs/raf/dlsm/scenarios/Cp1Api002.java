package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.AdminClient;
import rs.raf.dlsm.harness.ApiClient;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.inspect.InspectSSTableCommand;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * CP1-API-002 — deleting existing and missing keys (CP1 §29.1).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node, ready, with
 *       {@code alpha=A} stored and confirmed by {@code GET}.</li>
 *   <li><b>Operations:</b> {@code DELETE(alpha)}, {@code GET(alpha)};
 *       {@code DELETE(ghost)} for a key never written, {@code GET(ghost)};
 *       a scan over both keys; a manual flush that waits for publication;
 *       {@code SIGKILL}; {@code inspect sstable} of every published table on
 *       the stopped volume; a restart over the same volume; the same reads
 *       again.</li>
 *   <li><b>Failpoint or corruption:</b> none.</li>
 *   <li><b>Client outcome:</b> both {@code DELETE}s answer {@code 200};
 *       every later {@code GET} of either key answers the shared
 *       {@code 404 NOT_FOUND} envelope, before and after the restart.</li>
 *   <li><b>Logical oracle:</b> a scan covering both keys with a large limit
 *       emits neither of them.</li>
 *   <li><b>Physical / status oracle:</b> both {@code DELETE}s take sequences
 *       above the old {@code PUT}; the published tables hold a tombstone for
 *       {@code alpha} and for {@code ghost}, neither encoded as an empty
 *       {@code PUT}; the manifest high-water mark covers both deletes and
 *       survives the restart. The superseded {@code PUT} of {@code alpha}
 *       may be absent from the flushed table; if present, its sequence is
 *       below the tombstone's.</li>
 *   <li><b>Proves:</b> {@code DELETE} is a sequenced mutation, deleting a
 *       missing key still leaves a tombstone, and a tombstone stays a real
 *       record through flush and recovery, so {@code A} never comes back
 *       (I3, I8). <b>Does not prove:</b> tombstones across several tables
 *       or compaction (SST-005, CMP-002).</li>
 * </ol>
 */
public final class Cp1Api002 {

    public static final String ID = "CP1-API-002";

    private static final int PUBLIC_PORT = 18020;
    private static final int ADMIN_PORT = 18021;

    private static final String KEY = "alpha";
    private static final String GHOST = "ghost";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Api002() {
    }

    public static void run(long seed, Report report) throws Exception {
        byte[] a = value(seed);

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            ApiClient api = new ApiClient(PUBLIC_PORT);
            AdminClient admin = new AdminClient(ADMIN_PORT);

            node.put(KEY, a);
            Optional<byte[]> stored = node.get(KEY);
            node.check(stored.isPresent() && Arrays.equals(a, stored.get()), "setup",
                    "GET alpha did not return A");
            long putSequence = node.storageStatus().path("activeMemtable")
                    .path("maxSequence").asLong();

            delete(node, api, KEY);
            expectNotFound(node, api, KEY, "after DELETE");
            delete(node, api, GHOST);
            expectNotFound(node, api, GHOST, "after DELETE of a key never written");
            expectNeitherScanned(node, api, "before the flush");

            // Both deletes are mutations: two new sequences above the PUT.
            long deleteAlpha = putSequence + 1;
            long deleteGhost = putSequence + 2;
            node.check(node.storageStatus().path("nextSequence").asLong() == deleteGhost + 1,
                    "status", "the two DELETEs did not take two new sequences");

            List<Long> tables = flush(node, admin, deleteGhost);
            expectManifestCovers(node, deleteGhost, "after the flush");

            node.kill();
            expectTombstones(node, tables, putSequence, deleteAlpha, deleteGhost);

            node.startReady();
            expectNotFound(node, api, KEY, "after the restart");
            expectNotFound(node, api, GHOST, "after the restart");
            expectNeitherScanned(node, api, "after the restart");
            expectManifestCovers(node, deleteGhost, "after the restart");
        }
        report.pass();
    }

    /** {@code A}: seeded bytes, never empty, so a tombstone cannot pass for it. */
    static byte[] value(long seed) {
        byte[] a = new byte[6];
        new Random(seed).nextBytes(a);
        a[0] = 0x00;
        return a;
    }

    /** {@code DELETE} answers {@code 200}, also for a key that never existed (never {@code 404}). */
    private static void delete(WalScenarioNode node, ApiClient api, String key) throws Exception {
        String requestId = node.nextRequestId();
        HttpResponse<String> response = api.delete(key, requestId, WalScenarioNode.TIMEOUT_MS);
        node.check(response.statusCode() == 200, "DELETE " + key,
                "HTTP " + response.statusCode() + ": " + response.body());
        expectEnvelope(node, MAPPER.readTree(response.body()), requestId, "DELETE " + key);
    }

    /** The shared {@code 404 NOT_FOUND} envelope, with the standalone partition. */
    private static void expectNotFound(WalScenarioNode node, ApiClient api, String key,
                                       String when) throws Exception {
        String what = "GET " + key + " " + when;
        String requestId = node.nextRequestId();
        HttpResponse<String> response = api.get(key, requestId, WalScenarioNode.TIMEOUT_MS);
        node.check(response.statusCode() == 404, what,
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        node.check("NOT_FOUND".equals(body.path("code").asText()), what,
                "code " + body.path("code") + ", expected NOT_FOUND");
        expectEnvelope(node, body, requestId, what);
    }

    /** {@code SCAN[a,z) limit=100}: neither deleted key is emitted. */
    private static void expectNeitherScanned(WalScenarioNode node, ApiClient api, String when)
            throws Exception {
        String requestId = node.nextRequestId();
        HttpResponse<String> response =
                api.scan("a", "z", 100, requestId, WalScenarioNode.TIMEOUT_MS);
        node.check(response.statusCode() == 200, "SCAN " + when,
                "HTTP " + response.statusCode() + ": " + response.body());
        for (JsonNode item : MAPPER.readTree(response.body()).path("items")) {
            String key = item.path("key").asText();
            node.check(!KEY.equals(key) && !GHOST.equals(key), "SCAN " + when,
                    "deleted key " + key + " was emitted");
        }
    }

    /** Manual flush (CP1 §23.1): waits until everything up to the last delete is published. */
    private static List<Long> flush(WalScenarioNode node, AdminClient admin, long target)
            throws Exception {
        HttpResponse<String> response = admin.flush();
        node.check(response.statusCode() == 200, "flush",
                "HTTP " + response.statusCode() + ": " + response.body());
        JsonNode body = MAPPER.readTree(response.body());
        node.check("PUBLISHED".equals(body.path("result").asText())
                        && body.path("flushedThroughSequence").asLong() >= target, "flush",
                "expected a published table covering sequence " + target + ", got " + body);

        List<Long> tables = new ArrayList<>();
        body.path("publishedTableIds").forEach(id -> tables.add(id.asLong()));
        node.check(!tables.isEmpty(), "flush", "no published table: " + body);
        return tables;
    }

    private static void expectManifestCovers(WalScenarioNode node, long sequence, String when)
            throws Exception {
        long flushedThrough = node.storageStatus().path("manifest")
                .path("flushedThroughSequence").asLong();
        node.check(flushedThrough >= sequence, "manifest " + when,
                "flushedThroughSequence " + flushedThrough + " does not cover " + sequence);
    }

    /**
     * {@code inspect sstable} of the published tables on the stopped volume
     * (CP1 §25): a tombstone for each key at its delete sequence, never an
     * empty {@code PUT}; the old {@code PUT} of alpha only below its tombstone.
     */
    private static void expectTombstones(WalScenarioNode node, List<Long> tables,
                                         long putSequence, long deleteAlpha, long deleteGhost)
            throws Exception {
        List<JsonNode> records = new ArrayList<>();
        for (long tableId : tables) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = InspectSSTableCommand.run(node.dataDir(), tableId,
                    new PrintStream(out, true, StandardCharsets.UTF_8));
            String json = out.toString(StandardCharsets.UTF_8);
            node.check(exit == InspectSSTableCommand.EXIT_OK, "inspect sstable " + tableId,
                    "exit " + exit + ": " + json);
            MAPPER.readTree(json).path("records").forEach(records::add);
        }

        expectTombstone(node, records, KEY, deleteAlpha);
        expectTombstone(node, records, GHOST, deleteGhost);
        for (JsonNode record : records) {
            if (KEY.equals(record.path("key").asText())
                    && "PUT".equals(record.path("kind").asText())) {
                node.check(record.path("sequence").asLong() == putSequence, "inspect sstable",
                        "unexpected PUT of alpha: " + record);
            }
        }
    }

    private static void expectTombstone(WalScenarioNode node, List<JsonNode> records,
                                        String key, long sequence) {
        List<JsonNode> forKey = new ArrayList<>();
        for (JsonNode record : records) {
            if (key.equals(record.path("key").asText())) {
                forKey.add(record);
            }
        }
        boolean tombstone = forKey.stream().anyMatch(r ->
                "DELETE".equals(r.path("kind").asText())
                        && r.path("sequence").asLong() == sequence
                        && r.path("valueLength").isNull());
        node.check(tombstone, "inspect sstable",
                "no tombstone for " + key + " at sequence " + sequence + ": " + forKey);
        boolean emptyPut = forKey.stream().anyMatch(r ->
                "PUT".equals(r.path("kind").asText()) && r.path("valueLength").asInt(-1) == 0);
        node.check(!emptyPut, "inspect sstable",
                "DELETE of " + key + " was stored as an empty PUT: " + forKey);
    }

    private static void expectEnvelope(WalScenarioNode node, JsonNode body,
                                       String requestId, String what) {
        node.check(requestId.equals(body.path("requestId").asText()), what,
                "requestId " + body.path("requestId") + ", expected " + requestId);
        node.check(body.path("partitionId").asInt(-1) == 0 && body.path("epoch").asInt(-1) == 0,
                what, "partition " + body.path("partitionId") + "/" + body.path("epoch"));
    }
}
