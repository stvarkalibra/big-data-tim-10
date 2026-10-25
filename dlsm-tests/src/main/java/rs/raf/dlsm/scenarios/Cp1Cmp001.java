package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.model.ReferenceModel;
import rs.raf.dlsm.scenarios.Cp1Fls001.Mutation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static rs.raf.dlsm.scenarios.FlushScenarioChecks.awaitIdle;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.compact;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.expectModel;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.finalTables;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.flush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.ids;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.inspect;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableFile;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableIds;

/**
 * CP1-CMP-001 — compaction of overlapping versions (CP1 §29.5).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node with three published
 *       tables and empty MemTables: T1 from {@code alpha=A1, beta=B1,
 *       delta=D1}; T2 from later {@code alpha=A2, gamma=G2}; T3 from later
 *       {@code beta=B3, delta=D3, epsilon=E3}. Values are seeded bytes.</li>
 *   <li><b>Operations:</b> read the model; reset counters; manual
 *       compaction; wait until maintenance is idle; read again and inspect
 *       the output; kill and restart; read again; a second manual
 *       compaction over the single remaining table.</li>
 *   <li><b>Failpoint or corruption:</b> none; the restart is a {@code SIGKILL}.</li>
 *   <li><b>Client outcome:</b> the first compaction returns {@code PUBLISHED}
 *       with all three input IDs and one new output ID; the second returns
 *       {@code NO_OP} with the one seen table, {@code outputTableId = null}
 *       and the same generation.</li>
 *   <li><b>Logical oracle:</b> before, right after and after the restart the
 *       state is exactly {@code alpha=A2, beta=B3, delta=D3, epsilon=E3,
 *       gamma=G2}, in ordered scans.</li>
 *   <li><b>Physical / status oracle:</b> the manifest moves one generation,
 *       removes exactly the three inputs and adds the output; the high-water
 *       mark is unchanged; compaction attempts and publications grow by one;
 *       the output is a valid SSTable with exactly one record per key, ordered,
 *       each the version with the greatest sequence; the input files are
 *       gone and only the output remains; the restart keeps that manifest.</li>
 *   <li><b>Proves:</b> major compaction changes only the representation: it
 *       keeps the greatest sequence per key and atomically replaces exactly
 *       the selected set (CP1 §20.2, §20.3). <b>Does not prove:</b> keeping
 *       tombstones (CP1-CMP-002) or crash safety (CP1-CMP-003, CP1-CMP-004).</li>
 * </ol>
 */
public final class Cp1Cmp001 {

    public static final String ID = "CP1-CMP-001";

    private static final int PUBLIC_PORT = 18310;
    private static final int ADMIN_PORT = 18311;

    private Cp1Cmp001() {
    }

    static List<List<Mutation>> tables(long seed) {
        Random random = new Random(seed);
        return List.of(
                List.of(new Mutation("alpha", Cp1Fls001.bytes(random, 4)),
                        new Mutation("beta", Cp1Fls001.bytes(random, 5)),
                        new Mutation("delta", Cp1Fls001.bytes(random, 6))),
                List.of(new Mutation("alpha", Cp1Fls001.bytes(random, 7)),
                        new Mutation("gamma", Cp1Fls001.bytes(random, 8))),
                List.of(new Mutation("beta", Cp1Fls001.bytes(random, 9)),
                        new Mutation("delta", Cp1Fls001.bytes(random, 10)),
                        new Mutation("epsilon", Cp1Fls001.bytes(random, 11))));
    }

    static Map<String, Long> winningSequences(List<List<Mutation>> tables) {
        Map<String, Long> winners = new LinkedHashMap<>();
        long sequence = 0;
        for (List<Mutation> table : tables) {
            for (Mutation mutation : table) {
                winners.put(mutation.key(), ++sequence);
            }
        }
        return winners;
    }

    public static void run(long seed, Report report) throws Exception {
        List<List<Mutation>> tables = tables(seed);
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new HashSet<>();

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            List<Long> inputs = new ArrayList<>();
            for (int i = 0; i < tables.size(); i++) {
                Cp1Fls001.apply(node, tables.get(i), model, keys);
                JsonNode published = flush(node, ADMIN_PORT, "flush T" + (i + 1));
                inputs.addAll(ids(published.path("publishedTableIds")));
                awaitIdle(node, ID);
            }

            JsonNode before = node.storageStatus();
            node.check(tableIds(before).equals(inputs) && inputs.size() == 3, "setup",
                    "expected three published tables, status lists " + tableIds(before));
            node.check(before.path("activeMemtable").path("entryCount").asLong() == 0, "setup",
                    "the active MemTable is not empty");
            long generation = before.path("manifest").path("generation").asLong();
            long highWater = before.path("manifest").path("flushedThroughSequence").asLong();
            expectModel(node, model, keys, "before compaction");

            long epoch = node.resetStats();
            JsonNode result = compact(node, ADMIN_PORT, "compaction");
            node.check("PUBLISHED".equals(result.path("result").asText()), "compaction",
                    "result " + result.path("result") + ", expected PUBLISHED");
            node.check(ids(result.path("inputTableIds")).equals(inputs), "compaction",
                    "inputTableIds " + result.path("inputTableIds") + ", expected " + inputs);
            long output = result.path("outputTableId").asLong();
            node.check(!result.path("outputTableId").isNull() && !inputs.contains(output), "compaction",
                    "outputTableId " + result.path("outputTableId") + " is not a new table");
            node.check(result.path("manifestGeneration").asLong() == generation + 1, "compaction",
                    "manifestGeneration " + result.path("manifestGeneration") + ", expected " + (generation + 1));

            JsonNode after = awaitIdle(node, ID);
            node.check(tableIds(after).equals(List.of(output)), "manifest",
                    "tables " + tableIds(after) + ", expected only the output " + output);
            node.check(after.path("manifest").path("flushedThroughSequence").asLong() == highWater, "manifest",
                    "compaction moved the high-water mark from " + highWater + " to "
                            + after.path("manifest").path("flushedThroughSequence"));
            JsonNode stats = after.path("stats");
            node.check(stats.path("resetEpoch").asLong() == epoch, "counters", "counters were reset by someone else");
            node.check(stats.path("compactionAttempts").asLong() == 1
                            && stats.path("compactionPublications").asLong() == 1, "counters",
                    "compactionAttempts " + stats.path("compactionAttempts")
                            + ", compactionPublications " + stats.path("compactionPublications") + ", expected 1 and 1");
            Path sstDir = node.dataDir().resolve("lsm").resolve("sst");
            node.check(finalTables(sstDir).equals(List.of(tableFile(output))), "sst directory",
                    "final tables " + finalTables(sstDir) + ", expected only " + tableFile(output));
            for (long input : inputs) {
                node.check(!Files.exists(sstDir.resolve(tableFile(input))), "sst directory",
                        "input table " + input + " is still on disk after the publication");
            }
            expectOutput(node, output, winningSequences(tables));
            expectModel(node, model, keys, "right after compaction");

            node.kill();
            node.startReady();
            JsonNode recovered = node.storageStatus();
            node.check(recovered.path("manifest").path("generation").asLong() == generation + 1
                            && tableIds(recovered).equals(List.of(output)), "restart",
                    "manifest " + recovered.path("manifest") + " tables " + tableIds(recovered));
            expectModel(node, model, keys, "after restart");

            JsonNode again = compact(node, ADMIN_PORT, "second compaction");
            node.check("NO_OP".equals(again.path("result").asText()), "second compaction",
                    "result " + again.path("result") + ", expected NO_OP");
            node.check(ids(again.path("inputTableIds")).equals(List.of(output))
                            && again.path("outputTableId").isNull()
                            && again.path("manifestGeneration").asLong() == generation + 1, "second compaction",
                    "unexpected NO_OP answer " + again);
        }
        report.pass();
    }

    private static void expectOutput(WalScenarioNode node, long output, Map<String, Long> winners)
            throws Exception {
        JsonNode inspected = inspect(node, output);
        List<String> keys = new ArrayList<>();
        for (JsonNode record : inspected.path("records")) {
            String key = record.path("key").asText();
            keys.add(key);
            node.check("PUT".equals(record.path("kind").asText()), "output " + output,
                    "record " + record + " should be a PUT");
            node.check(winners.containsKey(key) && record.path("sequence").asLong() == winners.get(key),
                    "output " + output, "record " + record + " is not the newest version, expected sequence "
                            + winners.get(key));
        }
        node.check(keys.equals(winners.keySet().stream().sorted().toList()), "output " + output,
                "keys " + keys + ", expected exactly one record per key in order " + winners.keySet());
        node.check(inspected.path("sparseIndex").size() > 0, "output " + output, "the output has no sparse index");
    }
}
