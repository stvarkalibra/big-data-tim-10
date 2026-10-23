package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.AdminClient;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.model.ReferenceModel;
import rs.raf.dlsm.scenarios.Cp1Fls001.Mutation;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static rs.raf.dlsm.scenarios.FlushScenarioChecks.awaitIdle;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.expectModel;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.flush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.ids;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.postFlush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableIds;

/**
 * CP1-FLS-005 — I/O error before the manifest rename (CP1 §29.3).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node; an earlier flush
 *       publishes one table, so the old manifest {@code M} references it;
 *       then a seeded mix of overwrite, empty value and {@code DELETE} that is
 *       confirmed but not flushed, with greatest sequence {@code T}.</li>
 *   <li><b>Operations:</b> record the bytes of {@code M}; reset counters; arm
 *       the failpoint; call the manual flush; wait until the manual and the
 *       queued automatic attempt have both failed and maintenance is idle;
 *       check the state and read everything; delete the rule; call the manual
 *       flush again; kill and restart; read everything.</li>
 *   <li><b>Failpoint or corruption:</b>
 *       {@code manifest.after-temp-sync.before-rename} with {@code IO_ERROR}
 *       and no trigger limit until it is deleted, so the automatic flush
 *       queued by the rotation fails too and cannot publish before the
 *       checks.</li>
 *   <li><b>Client outcome:</b> the first flush fails with HTTP {@code 500}
 *       and {@code STORAGE_IO}; the retry returns {@code PUBLISHED}; every
 *       mutation and read succeeds.</li>
 *   <li><b>Logical oracle:</b> after the failure, after the retry and after
 *       the restart every {@code GET} and the full {@code SCAN} match the
 *       model.</li>
 *   <li><b>Physical / status oracle:</b> after the failure
 *       {@code MANIFEST.json} is byte for byte {@code M}, the status still
 *       shows {@code M}'s generation, tables and high-water mark, the node is
 *       {@code READY} without a fatal error, {@code maintenance.lastError} is
 *       {@code STORAGE_IO}, two flush attempts and no publication were
 *       counted, every table of {@code M} and the WAL segment protecting the
 *       unflushed mutations are still on disk. The retry publishes generation
 *       {@code M + 1} covering {@code T}; the restart keeps it.</li>
 *   <li><b>Proves:</b> a failure before the rename leaves the old manifest
 *       authoritative and its inputs and WAL usable, the in-memory view does
 *       not move, and a later attempt publishes safely (CP1 §18.1, §26.1).
 *       <b>Does not prove:</b> a failure after the rename (CP1-FLS-006).</li>
 * </ol>
 */
public final class Cp1Fls005 {

    public static final String ID = "CP1-FLS-005";

    private static final int PUBLIC_PORT = 18250;
    private static final int ADMIN_PORT = 18251;
    private static final String FAILPOINT = "manifest.after-temp-sync.before-rename";
    private static final String TOKEN = "fls005-io-error";
    private static final Duration ATTEMPTS_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Fls005() {
    }

    static List<Mutation> earlier(long seed) {
        Random random = new Random(seed ^ 0x5A5A);
        return List.of(
                new Mutation("alpha", Cp1Fls001.bytes(random, 4)),
                new Mutation("india", Cp1Fls001.bytes(random, 7)));
    }

    static List<Mutation> unflushed(long seed) {
        Random random = new Random(seed * 13 + 1);
        return List.of(
                new Mutation("alpha", Cp1Fls001.bytes(random, 12)),
                new Mutation("india", null),
                new Mutation("charlie", new byte[0]),
                new Mutation("delta", Cp1Fls001.bytes(random, 1 + random.nextInt(16))),
                new Mutation("echo", null));
    }

    public static void run(long seed, Report report) throws Exception {
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new HashSet<>();

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            AdminClient admin = new AdminClient(ADMIN_PORT);
            Cp1Fls001.apply(node, earlier(seed), model, keys);
            flush(node, ADMIN_PORT, "earlier flush");
            awaitIdle(node, ID);

            Cp1Fls001.apply(node, unflushed(seed), model, keys);
            long target = node.storageStatus().path("nextSequence").asLong() - 1;
            Path manifestFile = node.dataDir().resolve("lsm").resolve("MANIFEST.json");
            byte[] oldManifest = Files.readAllBytes(manifestFile);
            JsonNode m = MAPPER.readTree(oldManifest);
            List<Long> referenced = new ArrayList<>();
            m.path("sstables").forEach(t -> referenced.add(t.path("tableId").asLong()));

            long epoch = node.resetStats();
            node.armFailpoint(TOKEN, """
                    {"name": "%s", "action": "IO_ERROR", "afterHits": 0, "maxTriggers": 0}
                    """.formatted(FAILPOINT));
            String requestId = node.nextRequestId();
            HttpResponse<String> failed = postFlush(ADMIN_PORT, requestId);
            node.check(failed.statusCode() == 500, "failed flush (" + requestId + ")",
                    "HTTP " + failed.statusCode() + ": " + failed.body());
            JsonNode error = MAPPER.readTree(failed.body());
            node.check("STORAGE_IO".equals(error.path("code").asText())
                            && requestId.equals(error.path("requestId").asText()), "failed flush",
                    "unexpected error envelope " + error);

            JsonNode afterFailure = awaitBothAttempts(node);
            node.check(afterFailure.path("stats").path("resetEpoch").asLong() == epoch, "counters",
                    "counters were reset by someone else");
            node.check(afterFailure.path("stats").path("flushPublications").asLong() == 0, "counters",
                    "a flush was published although every manifest rename failed");
            node.check(Arrays.equals(oldManifest, Files.readAllBytes(manifestFile)), "after failure",
                    "MANIFEST.json changed although the rename never happened");
            expectOldState(node, afterFailure, m, referenced);
            expectModel(node, model, keys, "after the failure");

            HttpResponse<String> deleted = admin.deleteFailpoint(TOKEN);
            node.check(deleted.statusCode() / 100 == 2, "delete failpoint",
                    "HTTP " + deleted.statusCode() + ": " + deleted.body());

            JsonNode retry = flush(node, ADMIN_PORT, "retry");
            node.check("PUBLISHED".equals(retry.path("result").asText()), "retry",
                    "result " + retry.path("result") + ", expected PUBLISHED");
            long generation = retry.path("manifestGeneration").asLong();
            node.check(generation == m.path("generation").asLong() + 1, "retry",
                    "generation " + generation + ", expected " + (m.path("generation").asLong() + 1));
            node.check(retry.path("flushedThroughSequence").asLong() >= target, "retry",
                    "flushedThroughSequence " + retry.path("flushedThroughSequence") + " is below " + target);
            List<Long> published = ids(retry.path("publishedTableIds"));
            node.check(!published.isEmpty() && published.stream().noneMatch(referenced::contains), "retry",
                    "publishedTableIds " + published + " must be new tables");
            expectModel(node, model, keys, "after the retry");

            node.kill();
            node.startReady();
            JsonNode recovered = node.storageStatus();
            node.check(recovered.path("manifest").path("generation").asLong() == generation, "restart",
                    "generation " + recovered.path("manifest").path("generation") + ", expected " + generation);
            List<Long> expectedTables = new ArrayList<>(referenced);
            expectedTables.addAll(published);
            node.check(tableIds(recovered).equals(expectedTables.stream().sorted().toList()), "restart",
                    "tables " + tableIds(recovered) + ", expected " + expectedTables);
            node.check(recovered.path("stats").path("walRecordsReplayed").asLong() == 0, "restart",
                    "walRecordsReplayed " + recovered.path("stats").path("walRecordsReplayed")
                            + " although the retry covered every mutation");
            expectModel(node, model, keys, "after restart");
        }
        report.pass();
    }

    private static JsonNode awaitBothAttempts(WalScenarioNode node) {
        JsonNode[] last = new JsonNode[1];
        Await.until(() -> {
            try {
                last[0] = node.storageStatus();
            } catch (Exception e) {
                throw new AssertionError(ID + ": storage status failed", e);
            }
            return last[0].path("stats").path("flushAttempts").asLong() >= 2
                    && "IDLE".equals(last[0].path("maintenance").path("phase").asText());
        }, ATTEMPTS_TIMEOUT, ID + ": the manual and the automatic flush attempt did not both finish");
        return last[0];
    }

    private static void expectOldState(WalScenarioNode node, JsonNode status, JsonNode m,
                                       List<Long> referenced) throws Exception {
        node.check("READY".equals(status.path("lifecycle").asText())
                        && status.path("lastFatalError").isNull(), "after failure",
                "a failure before the rename must not fail the engine: " + status.path("lifecycle")
                        + " " + status.path("lastFatalError"));
        JsonNode manifest = status.path("manifest");
        node.check(manifest.path("generation").asLong() == m.path("generation").asLong()
                        && manifest.path("flushedThroughSequence").asLong()
                        == m.path("flushedThroughSequence").asLong(), "after failure",
                "the in-memory manifest moved to " + manifest + " without a durable rename");
        node.check(tableIds(status).equals(referenced), "after failure",
                "tables " + tableIds(status) + ", expected the tables of M " + referenced);
        node.check("STORAGE_IO".equals(status.path("maintenance").path("lastError").path("code").asText()),
                "after failure", "maintenance.lastError " + status.path("maintenance").path("lastError"));
        node.check(!status.path("immutableMemtable").isNull(), "after failure",
                "the frozen generation disappeared although nothing was published");

        Path sstDir = node.dataDir().resolve("lsm").resolve("sst");
        for (long id : referenced) {
            node.check(Files.exists(sstDir.resolve(FlushScenarioChecks.tableFile(id))), "after failure",
                    "table " + id + " of M is gone");
        }
        node.check(Files.exists(node.walSegment(2)), "after failure",
                "the WAL segment protecting the unflushed mutations is gone");
    }
}
