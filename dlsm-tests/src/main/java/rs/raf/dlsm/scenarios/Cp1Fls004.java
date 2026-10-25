package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.model.ReferenceModel;
import rs.raf.dlsm.scenarios.Cp1Fls001.Mutation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static rs.raf.dlsm.scenarios.FlushScenarioChecks.awaitIdle;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.expectModel;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.finalTables;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.flush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.postFlush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableIds;

/**
 * CP1-FLS-004 — crash after the manifest is published, before the WAL is deleted (CP1 §29.3).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node; an earlier flush
 *       publishes one table, so manifest generation {@code G} references it;
 *       then a seeded mix of overwrite, empty value and {@code DELETE} that is
 *       confirmed but not flushed, with greatest sequence {@code T}.</li>
 *   <li><b>Operations:</b> arm the failpoint, call the manual flush and wait
 *       for the process to end; inspect the stopped volume; restart and read
 *       every key and one full {@code SCAN}; write a second, uncovered mix;
 *       kill and restart again; read again.</li>
 *   <li><b>Failpoint or corruption:</b>
 *       {@code flush.after-manifest-publish.before-wal-delete} with
 *       {@code CRASH}: the new manifest is durable, the covered WAL segment
 *       is not deleted yet. The second restart follows a {@code SIGKILL}.</li>
 *   <li><b>Client outcome:</b> the flush gets no response (the process
 *       halted); every other call succeeds.</li>
 *   <li><b>Logical oracle:</b> after both restarts every {@code GET} and the
 *       ordered, duplicate-free {@code SCAN} match the model; no deleted or
 *       overwritten value comes back.</li>
 *   <li><b>Physical / status oracle:</b> while stopped, the manifest is
 *       generation {@code G + 1}, references the flushed table and covers
 *       {@code T}, and the covered WAL segment is still on disk. The first
 *       restart replays no record ({@code walRecordsReplayed = 0}), keeps
 *       generation {@code G + 1} and its tables, and the covered segment is
 *       gone. The second restart replays exactly the uncovered mutations,
 *       and every manifest after it still references the flushed table.</li>
 *   <li><b>Proves:</b> after publication the leftover WAL is redundant;
 *       recovery skips every record with {@code sequence <= flushedThroughSequence},
 *       removes the covered segment and keeps the WAL above the high-water
 *       mark (CP1 §19.3, §21.3). <b>Does not prove:</b> a crash before the
 *       manifest is durable (CP1-FLS-003, CP1-FLS-005, CP1-FLS-006).</li>
 * </ol>
 */
public final class Cp1Fls004 {

    public static final String ID = "CP1-FLS-004";

    private static final int PUBLIC_PORT = 18240;
    private static final int ADMIN_PORT = 18241;
    private static final String FAILPOINT = "flush.after-manifest-publish.before-wal-delete";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Fls004() {
    }

    static List<Mutation> earlier(long seed) {
        Random random = new Random(seed);
        return List.of(
                new Mutation("alpha", Cp1Fls001.bytes(random, 6)),
                new Mutation("bravo", Cp1Fls001.bytes(random, 8)),
                new Mutation("hotel", Cp1Fls001.bytes(random, 2)));
    }

    static List<Mutation> covered(long seed) {
        Random random = new Random(seed * 31 + 7);
        return List.of(
                new Mutation("alpha", Cp1Fls001.bytes(random, 10)),
                new Mutation("bravo", null),
                new Mutation("charlie", new byte[0]),
                new Mutation("delta", Cp1Fls001.bytes(random, 5)),
                new Mutation("delta", Cp1Fls001.bytes(random, 9)),
                new Mutation("echo", null));
    }

    static List<Mutation> uncovered(long seed) {
        Random random = new Random(seed * 17 + 3);
        return List.of(
                new Mutation("alpha", null),
                new Mutation("foxtrot", Cp1Fls001.bytes(random, 1 + random.nextInt(20))),
                new Mutation("charlie", Cp1Fls001.bytes(random, 4)));
    }

    public static void run(long seed, Report report) throws Exception {
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new HashSet<>();

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            Cp1Fls001.apply(node, earlier(seed), model, keys);
            flush(node, ADMIN_PORT, "earlier flush");
            JsonNode before = awaitIdle(node, ID);
            long g = before.path("manifest").path("generation").asLong();
            List<Long> earlierTables = tableIds(before);

            Cp1Fls001.apply(node, covered(seed), model, keys);
            long target = node.storageStatus().path("nextSequence").asLong() - 1;
            Path coveredSegment = node.walSegment(2);
            node.check(Files.exists(coveredSegment), "setup",
                    "segment 2 should hold the mutations about to be flushed");

            node.armFailpoint("fls004-crash", """
                    {"name": "%s", "action": "CRASH", "afterHits": 0, "maxTriggers": 1}
                    """.formatted(FAILPOINT));
            String requestId = node.nextRequestId();
            try {
                var response = postFlush(ADMIN_PORT, requestId);
                node.check(false, "crash flush (" + requestId + ")",
                        "flush answered HTTP " + response.statusCode() + " although the process should halt");
            } catch (IOException expectedHalt) {
                node.awaitExit();
            }

            JsonNode published = MAPPER.readTree(
                    Files.readAllBytes(node.dataDir().resolve("lsm").resolve("MANIFEST.json")));
            node.check(published.path("generation").asLong() == g + 1, "stopped volume",
                    "manifest generation " + published.path("generation") + ", expected " + (g + 1));
            node.check(published.path("flushedThroughSequence").asLong() >= target, "stopped volume",
                    "flushedThroughSequence " + published.path("flushedThroughSequence")
                            + " does not cover the flushed generation up to " + target);
            List<Long> referenced = new ArrayList<>();
            published.path("sstables").forEach(t -> referenced.add(t.path("tableId").asLong()));
            node.check(referenced.containsAll(earlierTables) && referenced.size() == earlierTables.size() + 1,
                    "stopped volume", "manifest lists " + referenced + ", expected " + earlierTables + " plus one");
            Path sstDir = node.dataDir().resolve("lsm").resolve("sst");
            node.check(finalTables(sstDir).equals(referenced.stream().map(FlushScenarioChecks::tableFile)
                            .sorted().toList()), "stopped volume",
                    "final tables " + finalTables(sstDir) + " differ from the manifest " + referenced);
            node.check(Files.exists(coveredSegment), "stopped volume",
                    "the covered segment was deleted although the crash came before the WAL delete");

            node.startReady();
            JsonNode first = node.storageStatus();
            node.check(first.path("stats").path("walRecordsReplayed").asLong() == 0, "first restart",
                    "walRecordsReplayed " + first.path("stats").path("walRecordsReplayed")
                            + ": covered records were applied again");
            expectManifest(node, first, g + 1, referenced, target, "first restart");
            node.check(!Files.exists(coveredSegment), "first restart",
                    "the redundant covered segment was not removed");
            expectModel(node, model, keys, "after the first restart");

            List<Mutation> later = uncovered(seed);
            Cp1Fls001.apply(node, later, model, keys);
            node.check(walFiles(node).size() > 0, "uncovered mutations", "no WAL segment on disk");

            node.kill();
            node.startReady();
            JsonNode second = node.storageStatus();
            node.check(second.path("stats").path("walRecordsReplayed").asLong() == later.size(), "second restart",
                    "walRecordsReplayed " + second.path("stats").path("walRecordsReplayed")
                            + ", expected exactly the " + later.size() + " uncovered mutations");
            JsonNode settled = awaitIdle(node, ID);
            node.check(tableIds(settled).containsAll(referenced), "second restart",
                    "tables " + tableIds(settled) + " no longer include " + referenced);
            node.check(settled.path("manifest").path("generation").asLong() >= g + 1, "second restart",
                    "manifest went back to generation " + settled.path("manifest").path("generation"));
            expectModel(node, model, keys, "after the second restart");
        }
        report.pass();
    }

    private static void expectManifest(WalScenarioNode node, JsonNode status, long generation,
                                       List<Long> referenced, long target, String when) {
        JsonNode manifest = status.path("manifest");
        node.check(manifest.path("generation").asLong() == generation, when,
                "manifest generation " + manifest.path("generation") + ", expected " + generation);
        node.check(tableIds(status).equals(referenced.stream().sorted().toList()), when,
                "tables " + tableIds(status) + ", expected " + referenced);
        node.check(manifest.path("flushedThroughSequence").asLong() >= target, when,
                "flushedThroughSequence " + manifest.path("flushedThroughSequence") + " is below " + target);
        node.check(status.path("immutableMemtable").isNull(), when,
                "a recovered generation exists although every record was covered");
    }

    private static List<String> walFiles(WalScenarioNode node) throws IOException {
        try (Stream<Path> files = Files.list(node.dataDir().resolve("lsm").resolve("wal"))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("wal-")).toList();
        }
    }
}
