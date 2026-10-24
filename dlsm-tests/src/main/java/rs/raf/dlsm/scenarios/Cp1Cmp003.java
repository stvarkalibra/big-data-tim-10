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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static rs.raf.dlsm.scenarios.FlushScenarioChecks.awaitIdle;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.compact;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.expectModel;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.finalTables;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.flush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.ids;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.inspect;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.postCompact;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableFile;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableIds;

/**
 * CP1-CMP-003 — crash after the compaction output is finalized, before the manifest (CP1 §29.5).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node with the three
 *       overlapping published tables of CP1-CMP-001 and empty MemTables; the
 *       old manifest {@code M} references all three.</li>
 *   <li><b>Operations:</b> record the bytes of {@code M}; arm the failpoint;
 *       call the manual compaction and wait for the process to end; inspect
 *       the stopped volume; restart over the same volume; read everything;
 *       call the manual compaction again; read everything.</li>
 *   <li><b>Failpoint or corruption:</b>
 *       {@code compaction.after-output-finalize.before-manifest} with
 *       {@code CRASH}: the output is forced, renamed and its directory
 *       forced, but no manifest names it.</li>
 *   <li><b>Client outcome:</b> the first compaction gets no response (the
 *       process halted); the retry returns {@code PUBLISHED}; every other
 *       call succeeds.</li>
 *   <li><b>Logical oracle:</b> after the restart and after the retry every
 *       {@code GET} and the full {@code SCAN} match the model before the
 *       compaction.</li>
 *   <li><b>Physical / status oracle:</b> while stopped,
 *       {@code MANIFEST.json} is byte for byte {@code M}, every input is on
 *       disk, and exactly one unreferenced final table exists, a valid
 *       SSTable with one record per key. After the restart recovery keeps
 *       {@code M}'s generation and tables, counts one removed orphan and no
 *       compaction publication, and the orphan is gone. The retry replaces
 *       exactly the inputs with a new output whose ID is above the orphan's.</li>
 *   <li><b>Proves:</b> a finalized compaction output is only an orphan until
 *       a manifest names it; the selected inputs stay the authority and are
 *       never deleted early (I4, CP1 §20.5). <b>Does not prove:</b> a crash
 *       after the publication (CP1-CMP-004).</li>
 * </ol>
 */
public final class Cp1Cmp003 {

    public static final String ID = "CP1-CMP-003";

    private static final int PUBLIC_PORT = 18330;
    private static final int ADMIN_PORT = 18331;
    private static final String FAILPOINT = "compaction.after-output-finalize.before-manifest";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Cmp003() {
    }

    public static void run(long seed, Report report) throws Exception {
        List<List<Mutation>> tables = Cp1Cmp001.tables(seed + 3);
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new HashSet<>();

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            List<Long> inputs = new ArrayList<>();
            for (int i = 0; i < tables.size(); i++) {
                Cp1Fls001.apply(node, tables.get(i), model, keys);
                inputs.addAll(ids(flush(node, ADMIN_PORT, "flush T" + (i + 1)).path("publishedTableIds")));
                awaitIdle(node, ID);
            }
            Path manifestFile = node.dataDir().resolve("lsm").resolve("MANIFEST.json");
            byte[] oldManifest = Files.readAllBytes(manifestFile);
            JsonNode m = MAPPER.readTree(oldManifest);
            node.check(inputs.size() == 3 && tableIds(node.storageStatus()).equals(inputs), "setup",
                    "expected three published tables, got " + inputs);
            expectModel(node, model, keys, "before compaction");

            node.armFailpoint("cmp003-crash", """
                    {"name": "%s", "action": "CRASH", "afterHits": 0, "maxTriggers": 1}
                    """.formatted(FAILPOINT));
            String requestId = node.nextRequestId();
            try {
                HttpResponse<String> response = postCompact(ADMIN_PORT, requestId);
                node.check(false, "crash compaction (" + requestId + ")",
                        "answered HTTP " + response.statusCode() + " although the process should halt");
            } catch (IOException expectedHalt) {
                node.awaitExit();
            }

            node.check(Arrays.equals(oldManifest, Files.readAllBytes(manifestFile)), "stopped volume",
                    "MANIFEST.json changed although the crash came before the manifest");
            Path sstDir = node.dataDir().resolve("lsm").resolve("sst");
            List<String> files = finalTables(sstDir);
            for (long input : inputs) {
                node.check(files.contains(tableFile(input)), "stopped volume",
                        "selected input " + input + " was deleted before any publication");
            }
            List<String> orphans = files.stream()
                    .filter(name -> inputs.stream().noneMatch(id -> tableFile(id).equals(name)))
                    .toList();
            node.check(orphans.size() == 1, "stopped volume",
                    "expected exactly one unreferenced output, found " + orphans + " in " + files);
            long orphanId = Long.parseLong(orphans.get(0).substring(4, 24));
            JsonNode orphanRecords = inspect(node, orphanId).path("records");
            node.check(orphanRecords.size() == Cp1Cmp001.winningSequences(tables).size(), "stopped volume",
                    "the orphan output has " + orphanRecords.size() + " records, expected one per key");

            node.startReady();
            JsonNode recovered = node.storageStatus();
            node.check(recovered.path("manifest").path("generation").asLong() == m.path("generation").asLong()
                            && tableIds(recovered).equals(inputs), "recovery",
                    "recovery chose " + recovered.path("manifest") + " with tables " + tableIds(recovered)
                            + ", expected M with " + inputs);
            node.check(recovered.path("manifest").path("flushedThroughSequence").asLong()
                            == m.path("flushedThroughSequence").asLong(), "recovery", "the high-water mark moved");
            node.check(recovered.path("stats").path("orphanFilesRemoved").asLong() == 1, "recovery",
                    "orphanFilesRemoved " + recovered.path("stats").path("orphanFilesRemoved") + ", expected 1");
            node.check(recovered.path("stats").path("compactionPublications").asLong() == 0, "recovery",
                    "a compaction publication was counted for the crashed attempt");
            node.check(!Files.exists(sstDir.resolve(tableFile(orphanId))), "recovery",
                    "the orphan output " + orphanId + " is still on disk");
            expectModel(node, model, keys, "after restart");

            JsonNode retry = compact(node, ADMIN_PORT, "retry");
            node.check("PUBLISHED".equals(retry.path("result").asText())
                            && ids(retry.path("inputTableIds")).equals(inputs), "retry",
                    "unexpected answer " + retry);
            long output = retry.path("outputTableId").asLong();
            node.check(output > orphanId, "retry",
                    "output " + output + " reuses or precedes the orphan ID " + orphanId);
            node.check(tableIds(awaitIdle(node, ID)).equals(List.of(output)), "retry",
                    "the retry did not replace exactly the inputs");
            expectModel(node, model, keys, "after the retry");
        }
        report.pass();
    }
}
