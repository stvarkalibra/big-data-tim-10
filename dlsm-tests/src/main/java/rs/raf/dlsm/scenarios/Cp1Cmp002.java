package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.model.ReferenceModel;
import rs.raf.dlsm.scenarios.Cp1Fls001.Mutation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static rs.raf.dlsm.scenarios.FlushScenarioChecks.awaitIdle;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.compact;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.expectModel;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.flush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.ids;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.inspect;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableIds;

/**
 * CP1-CMP-002 — tombstones survive compaction (CP1 §29.5).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node with two published
 *       tables and empty MemTables: T1 from {@code alpha=A1, beta=B1}; T2 from
 *       later {@code DELETE(alpha)}, {@code DELETE(ghost)} and
 *       {@code PUT(beta,B2)}. Values are seeded bytes.</li>
 *   <li><b>Operations:</b> read the model; manual compaction; wait until
 *       maintenance is idle; inspect the output; reset counters and read the
 *       tombstoned keys; read and scan everything; kill and restart; reset
 *       counters and read the tombstoned keys again; read and scan again.</li>
 *   <li><b>Failpoint or corruption:</b> none; the restart is a {@code SIGKILL}.</li>
 *   <li><b>Client outcome:</b> the compaction returns {@code PUBLISHED} with
 *       both inputs and a new output; every other call succeeds.</li>
 *   <li><b>Logical oracle:</b> before, after and after the restart
 *       {@code alpha} and {@code ghost} are {@code 404 NOT_FOUND},
 *       {@code beta = B2}, and a scan over the range holds only {@code beta}.</li>
 *   <li><b>Physical / status oracle:</b> the output holds exactly three
 *       records: {@code alpha} and {@code ghost} as {@code DELETE} with their
 *       greatest sequence and no value, {@code beta} as {@code PUT} with
 *       {@code B2}'s sequence and length; its {@code entryCount} is three.
 *       Reads of {@code alpha} and {@code ghost} on the single output table
 *       count Bloom positive probes and never a negative skip, so both
 *       tombstone keys are in the Bloom filter (it has no false negatives).</li>
 *   <li><b>Proves:</b> a tombstone is a real version that compaction keeps,
 *       indexes and adds to the Bloom filter (I8, CP1 §20.2). <b>Does not
 *       prove:</b> any rule for removing tombstones, which Checkpoint 1
 *       forbids.</li>
 * </ol>
 */
public final class Cp1Cmp002 {

    public static final String ID = "CP1-CMP-002";

    private static final int PUBLIC_PORT = 18320;
    private static final int ADMIN_PORT = 18321;
    private static final List<String> TOMBSTONED = List.of("alpha", "ghost");

    private Cp1Cmp002() {
    }

    static List<List<Mutation>> tables(long seed) {
        Random random = new Random(seed);
        return List.of(
                List.of(new Mutation("alpha", Cp1Fls001.bytes(random, 5)),
                        new Mutation("beta", Cp1Fls001.bytes(random, 6))),
                List.of(new Mutation("alpha", null),
                        new Mutation("ghost", null),
                        new Mutation("beta", Cp1Fls001.bytes(random, 1 + random.nextInt(12)))));
    }

    public static void run(long seed, Report report) throws Exception {
        List<List<Mutation>> tables = tables(seed);
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new HashSet<>();
        byte[] b2 = tables.get(1).get(2).value();

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            List<Long> inputs = new ArrayList<>();
            for (int i = 0; i < tables.size(); i++) {
                Cp1Fls001.apply(node, tables.get(i), model, keys);
                inputs.addAll(ids(flush(node, ADMIN_PORT, "flush T" + (i + 1)).path("publishedTableIds")));
                awaitIdle(node, ID);
            }
            node.check(inputs.size() == 2, "setup", "expected two published tables, got " + inputs);
            expectLogical(node, model, keys, b2, "before compaction");

            JsonNode result = compact(node, ADMIN_PORT, "compaction");
            node.check("PUBLISHED".equals(result.path("result").asText())
                            && ids(result.path("inputTableIds")).equals(inputs), "compaction",
                    "unexpected answer " + result);
            long output = result.path("outputTableId").asLong();
            JsonNode after = awaitIdle(node, ID);
            node.check(tableIds(after).equals(List.of(output)), "manifest",
                    "tables " + tableIds(after) + ", expected only the output " + output);
            node.check(after.path("sstables").path(0).path("entryCount").asLong() == 3, "manifest",
                    "entryCount " + after.path("sstables").path(0).path("entryCount")
                            + ", expected three records: two tombstones and beta");

            expectTombstonesKept(node, output, b2.length);
            expectTombstonesInBloom(node, "after compaction");
            expectLogical(node, model, keys, b2, "after compaction");

            node.kill();
            node.startReady();
            node.check(tableIds(node.storageStatus()).equals(List.of(output)), "restart",
                    "the restart changed the published tables");
            expectTombstonesInBloom(node, "after restart");
            expectLogical(node, model, keys, b2, "after restart");
        }
        report.pass();
    }

    private static void expectLogical(WalScenarioNode node, ReferenceModel model, Set<String> keys,
                                      byte[] b2, String when) throws Exception {
        for (String key : TOMBSTONED) {
            Optional<byte[]> value = node.get(key);
            node.check(value.isEmpty(), "GET " + key + " " + when,
                    "a deleted key returned a value: the tombstone was dropped or shadowed");
        }
        Optional<byte[]> beta = node.get("beta");
        node.check(beta.isPresent() && Arrays.equals(b2, beta.get()), "GET beta " + when,
                "beta is not B2");
        JsonNode scan = node.scan("alpha", "zulu", 10);
        node.check(scan.path("items").size() == 1
                        && "beta".equals(scan.path("items").path(0).path("key").asText()), "SCAN " + when,
                "expected only beta in [alpha, zulu): " + scan.path("items"));
        expectModel(node, model, keys, when);
    }

    private static void expectTombstonesKept(WalScenarioNode node, long output, int b2Length) throws Exception {
        JsonNode records = inspect(node, output).path("records");
        node.check(records.size() == 3, "output " + output, "expected 3 records, got " + records);

        long[] expectedSequences = {3, 5, 4};
        String[] expectedKeys = {"alpha", "beta", "ghost"};
        String[] expectedKinds = {"DELETE", "PUT", "DELETE"};
        for (int i = 0; i < 3; i++) {
            JsonNode record = records.path(i);
            node.check(expectedKeys[i].equals(record.path("key").asText())
                            && expectedKinds[i].equals(record.path("kind").asText())
                            && record.path("sequence").asLong() == expectedSequences[i], "output " + output,
                    "record " + i + " is " + record + ", expected " + expectedKeys[i] + " "
                            + expectedKinds[i] + " @" + expectedSequences[i]);
        }
        node.check(records.path(0).path("valueLength").isNull() && records.path(2).path("valueLength").isNull(),
                "output " + output, "a tombstone carries a value, it became a PUT: " + records);
        node.check(records.path(1).path("valueLength").asInt() == b2Length, "output " + output,
                "beta has length " + records.path(1).path("valueLength") + ", expected B2 with " + b2Length);
    }

    private static void expectTombstonesInBloom(WalScenarioNode node, String when) throws Exception {
        long epoch = node.resetStats();
        for (String key : TOMBSTONED) {
            node.check(node.get(key).isEmpty(), "GET " + key + " " + when, "a deleted key returned a value");
        }
        JsonNode stats = node.storageStatus().path("stats");
        node.check(stats.path("resetEpoch").asLong() == epoch, "Bloom " + when, "counters were reset by someone else");
        node.check(stats.path("bloomNegativeSkips").asLong() == 0, "Bloom " + when,
                "bloomNegativeSkips " + stats.path("bloomNegativeSkips")
                        + ": a tombstone key is missing from the Bloom filter");
        node.check(stats.path("bloomPositiveProbes").asLong() == TOMBSTONED.size(), "Bloom " + when,
                "bloomPositiveProbes " + stats.path("bloomPositiveProbes") + ", expected one per tombstone key");
    }
}
