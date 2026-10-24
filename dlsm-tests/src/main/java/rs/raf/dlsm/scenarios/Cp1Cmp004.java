package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.model.ReferenceModel;
import rs.raf.dlsm.scenarios.Cp1Fls001.Mutation;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static rs.raf.dlsm.scenarios.FlushScenarioChecks.awaitIdle;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.expectModel;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.flush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.ids;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.inspect;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.postCompact;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableFile;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableIds;

/**
 * CP1-CMP-004 — crash after the compaction manifest, before the inputs are deleted (CP1 §29.5).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node with the two
 *       overlapping published tables of CP1-CMP-002 (T2 deletes {@code alpha}
 *       and {@code ghost} and overwrites {@code beta}) and empty MemTables;
 *       the old manifest {@code M} references both.</li>
 *   <li><b>Operations:</b> arm the failpoint; call the manual compaction and
 *       wait for the process to end; inspect the stopped volume; restart and
 *       wait until the old inputs are cleaned up; read everything and inspect
 *       the output; kill and restart again; read everything.</li>
 *   <li><b>Failpoint or corruption:</b>
 *       {@code compaction.after-manifest-publish.before-input-delete} with
 *       {@code CRASH}: the new manifest is durable, the inputs are still on
 *       disk. The second restart follows a {@code SIGKILL}.</li>
 *   <li><b>Client outcome:</b> the compaction gets no response (the process
 *       halted); every other call succeeds.</li>
 *   <li><b>Logical oracle:</b> after both restarts the state equals the
 *       state before the compaction: {@code alpha} and {@code ghost} are
 *       {@code 404}, {@code beta} is {@code B2}, one visible version per key.</li>
 *   <li><b>Physical / status oracle:</b> while stopped, {@code MANIFEST.json}
 *       is generation {@code M + 1}, references exactly one new table and no
 *       input, keeps the high-water mark, and both inputs are still on disk.
 *       After the restart the status shows that manifest, the output is a
 *       valid table that still holds both tombstones, the inputs are removed
 *       as orphans within a bounded wait and never come back into the
 *       manifest; the second restart keeps the same manifest and tables.</li>
 *   <li><b>Proves:</b> once the replacement manifest is durable the output is
 *       the authority and the old inputs are only files to clean up; the
 *       directory listing never puts them back (I4, CP1 §20.5).
 *       <b>Does not prove:</b> how readers of an older view finish before the
 *       deletion; the crash ends those readers.</li>
 * </ol>
 */
public final class Cp1Cmp004 {

    public static final String ID = "CP1-CMP-004";

    private static final int PUBLIC_PORT = 18340;
    private static final int ADMIN_PORT = 18341;
    private static final String FAILPOINT = "compaction.after-manifest-publish.before-input-delete";
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Cmp004() {
    }

    public static void run(long seed, Report report) throws Exception {
        List<List<Mutation>> tables = Cp1Cmp002.tables(seed + 4);
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
            JsonNode m = MAPPER.readTree(Files.readAllBytes(manifestFile));
            node.check(inputs.size() == 2 && tableIds(node.storageStatus()).equals(inputs), "setup",
                    "expected two published tables, got " + inputs);
            expectModel(node, model, keys, "before compaction");

            node.armFailpoint("cmp004-crash", """
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

            JsonNode published = MAPPER.readTree(Files.readAllBytes(manifestFile));
            node.check(published.path("generation").asLong() == m.path("generation").asLong() + 1,
                    "stopped volume", "manifest generation " + published.path("generation")
                            + ", expected " + (m.path("generation").asLong() + 1));
            node.check(published.path("flushedThroughSequence").asLong()
                            == m.path("flushedThroughSequence").asLong(), "stopped volume",
                    "the compaction moved the high-water mark");
            List<Long> referenced = new ArrayList<>();
            published.path("sstables").forEach(t -> referenced.add(t.path("tableId").asLong()));
            node.check(referenced.size() == 1 && !inputs.contains(referenced.get(0)), "stopped volume",
                    "the new manifest must reference exactly the output and no input: " + referenced);
            long output = referenced.get(0);
            Path sstDir = node.dataDir().resolve("lsm").resolve("sst");
            node.check(Files.exists(sstDir.resolve(tableFile(output))), "stopped volume",
                    "the referenced output " + output + " is not on disk");
            for (long input : inputs) {
                node.check(Files.exists(sstDir.resolve(tableFile(input))), "stopped volume",
                        "input " + input + " was deleted although the crash came before the delete");
            }

            node.startReady();
            expectOutputAuthoritative(node, published, output, "first restart");
            Await.until(() -> inputs.stream().noneMatch(id -> Files.exists(sstDir.resolve(tableFile(id)))),
                    CLEANUP_TIMEOUT, ID + ": the old inputs were not cleaned up");
            node.check(node.storageStatus().path("stats").path("orphanFilesRemoved").asLong() == inputs.size(),
                    "first restart", "orphanFilesRemoved does not count both old inputs");
            expectTombstonesKept(node, output);
            expectModel(node, model, keys, "after the first restart");

            node.kill();
            node.startReady();
            expectOutputAuthoritative(node, published, output, "second restart");
            for (long input : inputs) {
                node.check(!Files.exists(sstDir.resolve(tableFile(input))), "second restart",
                        "input " + input + " came back");
            }
            expectModel(node, model, keys, "after the second restart");
        }
        report.pass();
    }

    private static void expectOutputAuthoritative(WalScenarioNode node, JsonNode published, long output,
                                                  String when) throws Exception {
        JsonNode status = node.storageStatus();
        node.check(status.path("manifest").path("generation").asLong() == published.path("generation").asLong(),
                when, "recovery chose generation " + status.path("manifest").path("generation")
                        + ", the volume held " + published.path("generation"));
        node.check(tableIds(status).equals(List.of(output)), when,
                "tables " + tableIds(status) + ", expected only the output " + output);
        node.check(status.path("manifest").path("flushedThroughSequence").asLong()
                        == published.path("flushedThroughSequence").asLong(), when, "the high-water mark moved");
    }

    private static void expectTombstonesKept(WalScenarioNode node, long output) throws Exception {
        List<String> tombstones = new ArrayList<>();
        for (JsonNode record : inspect(node, output).path("records")) {
            if ("DELETE".equals(record.path("kind").asText())) {
                tombstones.add(record.path("key").asText());
            }
        }
        node.check(tombstones.equals(List.of("alpha", "ghost")), "output " + output,
                "tombstones " + tombstones + ", expected alpha and ghost");
    }
}
