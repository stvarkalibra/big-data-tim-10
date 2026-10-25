package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.model.ReferenceModel;
import rs.raf.dlsm.scenarios.Cp1Fls001.Mutation;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static rs.raf.dlsm.scenarios.FlushScenarioChecks.awaitIdle;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.expectModel;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.finalTables;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.flush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.postFlush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableFile;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableIds;

/**
 * CP1-FLS-006 — failure after the manifest rename, before the directory sync (CP1 §29.3).
 *
 * <ol>
 *   <li><b>Initial state:</b> for each sub-case its own fresh volume and
 *       ports; an earlier flush publishes one table, so {@code M_old}
 *       references it; then a seeded mix of overwrite, empty value and
 *       {@code DELETE} that is confirmed but not flushed, greatest sequence
 *       {@code T}.</li>
 *   <li><b>Operations:</b> arm the failpoint and call the manual flush;
 *       inspect the volume; restart over the same volume, read every key and
 *       one full {@code SCAN}; restart again and read again.</li>
 *   <li><b>Failpoint or corruption:</b>
 *       {@code manifest.after-rename.before-directory-sync}, once with
 *       {@code CRASH} and once with {@code IO_ERROR}: the new manifest has
 *       replaced {@code MANIFEST.json}, but {@code lsm/} was not forced.</li>
 *   <li><b>Client outcome:</b> {@code CRASH}: no response, the process
 *       halts. {@code IO_ERROR}: HTTP {@code 500} with {@code STORAGE_IO},
 *       then the node is {@code FAILED} with a fatal error and public reads
 *       are {@code 503 NODE_NOT_READY} until the restart.</li>
 *   <li><b>Logical oracle:</b> after each restart every {@code GET} and the
 *       full {@code SCAN} match the model before the failure, whichever
 *       complete manifest recovery found.</li>
 *   <li><b>Physical / status oracle:</b> before the restart
 *       {@code MANIFEST.json} is exactly a complete {@code M_old} or a
 *       complete {@code M_new} (generation {@code + 1}, one more table,
 *       high-water mark covering {@code T}), every table it references
 *       exists, no table of {@code M_old} and not the WAL segment protecting
 *       the unflushed mutations was deleted. After the restart the status
 *       shows that same generation and tables; with {@code M_new} no WAL
 *       record is replayed, with {@code M_old} the unflushed mutations are
 *       replayed and the new output is removed as an orphan. A second
 *       restart keeps a valid manifest and the same logical state.</li>
 *   <li><b>Proves:</b> at the only boundary where two complete generations
 *       are legal, both are protected because nothing is deleted before the
 *       directory sync, and both give the same logical state; an
 *       {@code IO_ERROR} there fails the engine (CP1 §18.1, §26.1).
 *       <b>Does not prove:</b> a failure before the rename (CP1-FLS-005).</li>
 * </ol>
 */
public final class Cp1Fls006 {

    public static final String ID = "CP1-FLS-006";

    private static final String FAILPOINT = "manifest.after-rename.before-directory-sync";
    private static final int CRASH_PUBLIC_PORT = 18260;
    private static final int IO_ERROR_PUBLIC_PORT = 18262;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Fls006() {
    }

    static List<Mutation> earlier(long seed) {
        Random random = new Random(seed + 606);
        return List.of(
                new Mutation("alpha", Cp1Fls001.bytes(random, 5)),
                new Mutation("juliet", Cp1Fls001.bytes(random, 6)),
                new Mutation("kilo", Cp1Fls001.bytes(random, 3)));
    }

    static List<Mutation> unflushed(long seed) {
        Random random = new Random(seed * 7 - 6);
        return List.of(
                new Mutation("alpha", Cp1Fls001.bytes(random, 9)),
                new Mutation("juliet", null),
                new Mutation("charlie", new byte[0]),
                new Mutation("delta", Cp1Fls001.bytes(random, 1 + random.nextInt(18))),
                new Mutation("echo", null));
    }

    public static void run(long seed, Report report) throws Exception {
        runCase(seed, "CRASH", CRASH_PUBLIC_PORT);
        runCase(seed, "IO_ERROR", IO_ERROR_PUBLIC_PORT);
        report.pass();
    }

    private static void runCase(long seed, String action, int publicPort) throws Exception {
        int adminPort = publicPort + 1;
        String what = action + ": ";
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new HashSet<>();
        List<Mutation> later = unflushed(seed);

        try (WalScenarioNode node = WalScenarioNode.create(ID + "-" + action, seed, publicPort, adminPort)) {
            node.startReady();
            Cp1Fls001.apply(node, earlier(seed), model, keys);
            flush(node, adminPort, what + "earlier flush");
            awaitIdle(node, ID);

            Cp1Fls001.apply(node, later, model, keys);
            long target = node.storageStatus().path("nextSequence").asLong() - 1;
            Path manifestFile = node.dataDir().resolve("lsm").resolve("MANIFEST.json");
            JsonNode oldManifest = MAPPER.readTree(Files.readAllBytes(manifestFile));
            List<Long> oldTables = tables(oldManifest);
            Path protecting = node.walSegment(2);
            node.check(Files.exists(protecting), what + "setup",
                    "segment 2 should hold the unflushed mutations");

            node.armFailpoint("fls006-" + action.toLowerCase(), """
                    {"name": "%s", "action": "%s", "afterHits": 0, "maxTriggers": 1}
                    """.formatted(FAILPOINT, action));
            String requestId = node.nextRequestId();
            if (action.equals("CRASH")) {
                try {
                    HttpResponse<String> response = postFlush(adminPort, requestId);
                    node.check(false, what + "flush (" + requestId + ")",
                            "answered HTTP " + response.statusCode() + " although the process should halt");
                } catch (IOException expectedHalt) {
                    node.awaitExit();
                }
            } else {
                HttpResponse<String> response = postFlush(adminPort, requestId);
                node.check(response.statusCode() == 500, what + "flush (" + requestId + ")",
                        "HTTP " + response.statusCode() + ": " + response.body());
                JsonNode error = MAPPER.readTree(response.body());
                node.check("STORAGE_IO".equals(error.path("code").asText()), what + "flush",
                        "unexpected error envelope " + error);
                JsonNode ready = node.awaitLifecycle("FAILED");
                node.check(!ready.path("lastFatalError").isNull()
                                && !ready.path("lastFatalError").isMissingNode(), what + "FAILED",
                        "/health/ready has no lastFatalError: " + ready);
                HttpResponse<String> read = node.rawGet("alpha");
                node.check(read.statusCode() == 503
                                && "NODE_NOT_READY".equals(MAPPER.readTree(read.body()).path("code").asText()),
                        what + "GET while FAILED", "HTTP " + read.statusCode() + ": " + read.body());
            }

            JsonNode found = MAPPER.readTree(Files.readAllBytes(manifestFile));
            boolean isNew = found.path("generation").asLong() == oldManifest.path("generation").asLong() + 1;
            expectCompleteGeneration(node, what, found, oldManifest, oldTables, isNew, target);
            for (long id : oldTables) {
                node.check(Files.exists(sstDir(node).resolve(tableFile(id))), what + "before restart",
                        "table " + id + " of M_old was deleted before the directory sync");
            }
            node.check(Files.exists(protecting), what + "before restart",
                    "the WAL protecting the unflushed mutations was deleted before the directory sync");

            if (action.equals("IO_ERROR")) {
                node.kill();
            }
            node.startReady();
            JsonNode recovered = node.storageStatus();
            node.check(recovered.path("manifest").path("generation").asLong()
                            == found.path("generation").asLong(), what + "restart",
                    "recovery chose generation " + recovered.path("manifest").path("generation")
                            + ", the volume held " + found.path("generation"));
            long replayed = recovered.path("stats").path("walRecordsReplayed").asLong();
            if (isNew) {
                node.check(tableIds(recovered).equals(tables(found)), what + "restart",
                        "tables " + tableIds(recovered) + ", M_new lists " + tables(found));
                node.check(replayed == 0, what + "restart",
                        "walRecordsReplayed " + replayed + " although M_new covers every mutation");
            } else {
                node.check(replayed >= later.size(), what + "restart",
                        "walRecordsReplayed " + replayed + ", expected the " + later.size()
                                + " unflushed mutations under M_old");
                node.check(recovered.path("stats").path("orphanFilesRemoved").asLong() >= 1, what + "restart",
                        "the unreferenced new output was not removed as an orphan under M_old");
            }
            awaitIdle(node, ID);
            expectModel(node, model, keys, what + "after the first restart");

            node.kill();
            node.startReady();
            JsonNode again = node.storageStatus();
            node.check(again.path("manifest").path("generation").asLong()
                            >= found.path("generation").asLong(), what + "second restart",
                    "manifest went back to generation " + again.path("manifest").path("generation"));
            for (long id : tableIds(again)) {
                node.check(Files.exists(sstDir(node).resolve(tableFile(id))), what + "second restart",
                        "table " + id + " is referenced but missing");
            }
            expectModel(node, model, keys, what + "after the second restart");
        }
    }

    private static void expectCompleteGeneration(WalScenarioNode node, String what, JsonNode found,
                                                 JsonNode old, List<Long> oldTables, boolean isNew,
                                                 long target) throws IOException {
        if (!isNew) {
            node.check(found.equals(old), what + "before restart",
                    "MANIFEST.json is neither M_old nor a complete M_new: " + found);
            return;
        }
        List<Long> newTables = tables(found);
        node.check(newTables.size() == oldTables.size() + 1 && newTables.containsAll(oldTables),
                what + "before restart", "M_new lists " + newTables + ", expected " + oldTables + " plus one");
        node.check(found.path("flushedThroughSequence").asLong() >= target, what + "before restart",
                "M_new flushedThroughSequence " + found.path("flushedThroughSequence") + " is below " + target);
        List<String> onDisk = finalTables(sstDir(node));
        for (long id : newTables) {
            node.check(onDisk.contains(tableFile(id)), what + "before restart",
                    "M_new references table " + id + " that is not on disk");
        }
    }

    private static List<Long> tables(JsonNode manifest) {
        List<Long> ids = new ArrayList<>();
        manifest.path("sstables").forEach(t -> ids.add(t.path("tableId").asLong()));
        ids.sort(null);
        return ids;
    }

    private static Path sstDir(WalScenarioNode node) {
        return node.dataDir().resolve("lsm").resolve("sst");
    }
}
