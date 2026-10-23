package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.model.ReferenceModel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;

/**
 * CP1-WAL-001 — crash after acknowledged mutations (CP1 §29.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh node on an empty volume with the
 *       default {@code MEMTABLE_MAX_BYTES} (1 MiB), so the test writes never
 *       rotate; counters reset right before the measured interval.</li>
 *   <li><b>Operations:</b> {@value #MUTATIONS} sequential mutations from the
 *       seed: PUT, overwrite, DELETE of a written and of a never-written
 *       key, an empty value and binary values, with reads checked against
 *       the reference model along the way. Then {@code SIGKILL}, restart;
 *       one more PUT, {@code SIGKILL}, restart.</li>
 *   <li><b>Failpoint or corruption:</b> none; the crash is {@code SIGKILL}
 *       after every response arrived, never an orderly shutdown.</li>
 *   <li><b>Client outcome:</b> every mutation is acknowledged with
 *       {@code 200} before the crash; there is no ambiguous operation.</li>
 *   <li><b>Logical oracle:</b> after each restart every key ever touched
 *       and a few bounded scans equal the independent
 *       {@link ReferenceModel}: deleted keys stay absent, the empty value
 *       stays present and empty, binary values are exact.</li>
 *   <li><b>Physical / status oracle:</b> before the crash, one successful
 *       WAL force per acknowledged mutation. After the first restart:
 *       {@code walRecordsReplayed} equals the records above the
 *       {@code flushedThroughSequence} seen before the crash, the active WAL
 *       segment is new, and {@code nextSequence} is one above the last
 *       acknowledged mutation. The extra PUT gets exactly that sequence and
 *       survives the second crash; the next sequence moves past it.</li>
 *   <li><b>Proves:</b> I1 and I6 — an acknowledged mutation is durable
 *       across a process crash, and sequences are never reused.
 *       <b>Does not prove:</b> what happens to a mutation without a
 *       response (WAL-005, WAL-006) or to a damaged WAL (WAL-002, WAL-003).</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed.
 */
public final class Cp1Wal001 {

    public static final String ID = "CP1-WAL-001";

    /** CP1 §29.2: at least 25 sequential acknowledged mutations. */
    static final int MUTATIONS = 30;
    /** Ports of this scenario only, so it never collides with another node. */
    private static final int PUBLIC_PORT = 18110;
    private static final int ADMIN_PORT = 18111;

    private static final String NEVER_WRITTEN = "ghost";

    private Cp1Wal001() {
    }

    public static void run(long seed, Report report) throws Exception {
        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            ReferenceModel model = new ReferenceModel();
            TreeSet<String> touched = new TreeSet<>();

            node.startReady();
            long resetEpoch = node.resetStats();
            JsonNode fresh = node.storageStatus();
            node.check(fresh.path("nextSequence").asLong() == 1, "fresh status",
                    "nextSequence " + fresh.path("nextSequence") + ", expected 1");
            long firstActiveWal = fresh.path("activeMemtable").path("walSegmentId").asLong();

            runMutations(node, model, touched, new Random(seed));

            // CP1 §24: one successful WAL force per acknowledged mutation.
            JsonNode beforeCrash = node.storageStatus();
            JsonNode stats = beforeCrash.path("stats");
            node.check(stats.path("resetEpoch").asLong() == resetEpoch, "counters",
                    "counters were reset by someone else: " + stats.path("resetEpoch"));
            node.check(stats.path("walForcesSucceeded").asLong() == MUTATIONS, "counters",
                    "walForcesSucceeded " + stats.path("walForcesSucceeded")
                            + ", expected " + MUTATIONS);
            node.check(stats.path("walAppendsCompleted").asLong() == MUTATIONS, "counters",
                    "walAppendsCompleted " + stats.path("walAppendsCompleted")
                            + ", expected " + MUTATIONS);
            node.check(beforeCrash.path("nextSequence").asLong() == MUTATIONS + 1,
                    "status before crash", "nextSequence " + beforeCrash.path("nextSequence"));
            long flushedThrough = beforeCrash.path("manifest").path("flushedThroughSequence").asLong();

            node.kill();
            node.startReady();

            JsonNode recovered = node.storageStatus();
            node.check(recovered.path("stats").path("walRecordsReplayed").asLong()
                            == MUTATIONS - flushedThrough, "first recovery",
                    "walRecordsReplayed " + recovered.path("stats").path("walRecordsReplayed")
                            + ", expected " + (MUTATIONS - flushedThrough));
            node.check(recovered.path("nextSequence").asLong() == MUTATIONS + 1, "first recovery",
                    "nextSequence " + recovered.path("nextSequence") + ", expected "
                            + (MUTATIONS + 1));
            node.check(recovered.path("activeMemtable").path("walSegmentId").asLong()
                            > firstActiveWal, "first recovery",
                    "active WAL segment " + recovered.path("activeMemtable").path("walSegmentId")
                            + " is not newer than " + firstActiveWal);
            expectModel(node, model, touched, "after first restart");

            // The next mutation takes exactly the recovered next sequence.
            byte[] after = ("after-" + seed).getBytes(StandardCharsets.US_ASCII);
            node.put("after", after);
            model.put("after", after);
            touched.add("after");
            JsonNode extra = node.storageStatus();
            node.check(extra.path("activeMemtable").path("maxSequence").asLong() == MUTATIONS + 1,
                    "extra mutation", "got sequence "
                            + extra.path("activeMemtable").path("maxSequence")
                            + ", expected " + (MUTATIONS + 1));

            node.kill();
            node.startReady();

            JsonNode second = node.storageStatus();
            node.check(second.path("nextSequence").asLong() == MUTATIONS + 2, "second recovery",
                    "nextSequence " + second.path("nextSequence") + ", expected "
                            + (MUTATIONS + 2));
            // At least the extra record, which no flush covered; more if the
            // recovered generation was not flushed before the second crash.
            long replayed = second.path("stats").path("walRecordsReplayed").asLong();
            node.check(replayed >= 1 && replayed <= MUTATIONS + 1, "second recovery",
                    "walRecordsReplayed " + replayed);
            expectModel(node, model, touched, "after second restart");
        }
        report.pass();
    }

    /**
     * The fixed cases first (overwrite, delete of a written and of a
     * never-written key, empty and binary values), then random PUTs and
     * DELETEs from the seed. Reads are checked every ten mutations.
     */
    private static void runMutations(WalScenarioNode node, ReferenceModel model,
                                     TreeSet<String> touched, Random random) throws Exception {
        int done = 0;

        done += put(node, model, touched, "alpha", ascii("A1"));
        done += put(node, model, touched, "empty", new byte[0]);
        byte[] binary = new byte[40];
        random.nextBytes(binary);
        binary[0] = 0x00;
        binary[1] = (byte) 0xFF;
        binary[2] = (byte) 0x80;
        done += put(node, model, touched, "binary", binary);
        done += put(node, model, touched, "alpha", ascii("A2"));
        done += put(node, model, touched, "doomed", ascii("D"));
        done += delete(node, model, touched, "doomed");
        done += delete(node, model, touched, NEVER_WRITTEN);

        while (done < MUTATIONS) {
            String key = String.format("key%02d", random.nextInt(8));
            if (random.nextInt(10) < 7) {
                byte[] value = new byte[random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(64)];
                random.nextBytes(value);
                done += put(node, model, touched, key, value);
            } else {
                done += delete(node, model, touched, key);
            }
            if (done % 10 == 0) {
                for (int i = 0; i < 3; i++) {
                    String probe = new ArrayList<>(touched).get(random.nextInt(touched.size()));
                    expectKey(node, model, probe, "during the run (" + done + " mutations)");
                }
            }
        }
    }

    private static int put(WalScenarioNode node, ReferenceModel model, TreeSet<String> touched,
                           String key, byte[] value) throws Exception {
        node.put(key, value);
        model.put(key, value);
        touched.add(key);
        return 1;
    }

    private static int delete(WalScenarioNode node, ReferenceModel model, TreeSet<String> touched,
                              String key) throws Exception {
        node.delete(key);
        model.delete(key);
        touched.add(key);
        return 1;
    }

    /** Every key ever touched and three bounded scans equal the model. */
    private static void expectModel(WalScenarioNode node, ReferenceModel model,
                                    TreeSet<String> touched, String when) throws Exception {
        for (String key : touched) {
            expectKey(node, model, key, when);
        }
        expectScan(node, model, "a", "zzz", 1000, when);
        expectScan(node, model, "key00", "key05", 1000, when);
        expectScan(node, model, "a", "key", 2, when);
    }

    private static void expectKey(WalScenarioNode node, ReferenceModel model, String key,
                                  String when) throws Exception {
        Optional<byte[]> actual = node.get(key);
        byte[] expected = model.get(key);
        node.check(expected == null ? actual.isEmpty()
                        : actual.isPresent() && Arrays.equals(expected, actual.get()),
                "GET " + key + " " + when,
                "expected " + describe(expected) + " but got "
                        + describe(actual.orElse(null)));
    }

    private static void expectScan(WalScenarioNode node, ReferenceModel model, String start,
                                   String end, int limit, String when) throws Exception {
        String what = "SCAN[" + start + "," + end + ") limit=" + limit + " " + when;
        JsonNode body = node.scan(start, end, limit);

        List<String> actual = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            actual.add(item.path("key").asText() + "=" + item.path("value").asText());
        }
        List<String> expected = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : model.scan(start, end, limit).entrySet()) {
            expected.add(entry.getKey() + "="
                    + Base64.getEncoder().encodeToString(entry.getValue()));
        }
        node.check(expected.equals(actual), what, "expected " + expected + " but got " + actual);
        node.check(body.path("count").asInt(-1) == actual.size(), what,
                "count " + body.path("count") + " for " + actual.size() + " items");
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String describe(byte[] value) {
        return value == null ? "absent" : "base64:" + Base64.getEncoder().encodeToString(value);
    }
}
