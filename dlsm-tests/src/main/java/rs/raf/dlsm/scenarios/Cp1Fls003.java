package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.inspect.InspectSSTableCommand;
import rs.raf.dlsm.model.ReferenceModel;
import rs.raf.dlsm.scenarios.Cp1Fls001.Mutation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static rs.raf.dlsm.scenarios.FlushScenarioChecks.awaitIdle;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.expectModel;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.finalTables;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.flush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.postFlush;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableFile;
import static rs.raf.dlsm.scenarios.FlushScenarioChecks.tableIds;

/**
 * CP1-FLS-003 — crash after the SSTable is finalized, before the manifest (CP1 §29.3).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh standalone node; one earlier flush
 *       publishes table {@code P}, so the old manifest {@code M} already
 *       references a table; then a seeded mix of {@code PUT}, overwrite,
 *       empty value and {@code DELETE} that is confirmed but not flushed.</li>
 *   <li><b>Operations:</b> record the bytes of {@code M}, arm the failpoint,
 *       call the manual flush and wait for the process to end; inspect the
 *       stopped volume; restart over the same volume and wait until
 *       maintenance is idle; every {@code GET} and one full {@code SCAN}.</li>
 *   <li><b>Failpoint or corruption:</b>
 *       {@code flush.after-sstable-finalize.before-manifest} with
 *       {@code CRASH}: the new table is forced, renamed and its directory
 *       forced, but no manifest names it.</li>
 *   <li><b>Client outcome:</b> the flush gets no response (the process
 *       halted); every other call succeeds.</li>
 *   <li><b>Logical oracle:</b> after the restart every confirmed value and
 *       tombstone, flushed or not, matches the model; deleted keys are
 *       {@code 404}.</li>
 *   <li><b>Physical / status oracle:</b> while stopped, {@code MANIFEST.json}
 *       is byte for byte {@code M}, exactly one final table that {@code M}
 *       does not reference exists, it is a valid SSTable holding exactly the
 *       unflushed mutations, and the WAL segment protecting them is still on
 *       disk. After the restart, recovery counts one removed orphan and
 *       replays at least the unflushed mutations from the WAL, the orphan
 *       file is gone, and its table ID never appears in a manifest: either
 *       {@code M} is still current with its high-water mark, or a later real
 *       publication added exactly one table with a higher, new ID.</li>
 *   <li><b>Proves:</b> a finalized table is not part of the database until a
 *       manifest names it (I4); recovery trusts {@code M} and the WAL, removes
 *       the orphan and never reuses its ID (CP1 §18.3). <b>Does not prove:</b>
 *       crashes before the rename (CP1-FLS-002) or after the manifest rename
 *       (CP1-FLS-004 to CP1-FLS-006).</li>
 * </ol>
 */
public final class Cp1Fls003 {

    public static final String ID = "CP1-FLS-003";

    private static final int PUBLIC_PORT = 18230;
    private static final int ADMIN_PORT = 18231;
    private static final String FAILPOINT = "flush.after-sstable-finalize.before-manifest";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cp1Fls003() {
    }

    static List<Mutation> published(long seed) {
        Random random = new Random(seed);
        return List.of(
                new Mutation("alpha", Cp1Fls001.bytes(random, 5)),
                new Mutation("bravo", Cp1Fls001.bytes(random, 7)),
                new Mutation("golf", Cp1Fls001.bytes(random, 3)));
    }

    static List<Mutation> unflushed(long seed) {
        Random random = new Random(~seed);
        return List.of(
                new Mutation("alpha", Cp1Fls001.bytes(random, 11)),
                new Mutation("charlie", new byte[0]),
                new Mutation("bravo", null),
                new Mutation("delta", Cp1Fls001.bytes(random, 1 + random.nextInt(24))),
                new Mutation("echo", null));
    }

    public static void run(long seed, Report report) throws Exception {
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new HashSet<>();
        List<Mutation> later = unflushed(seed);

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            Cp1Fls001.apply(node, published(seed), model, keys);
            JsonNode first = flush(node, ADMIN_PORT, "earlier flush");
            node.check("PUBLISHED".equals(first.path("result").asText()), "earlier flush",
                    "result " + first.path("result") + ", expected PUBLISHED");
            awaitIdle(node, ID);

            Cp1Fls001.apply(node, later, model, keys);
            long target = node.storageStatus().path("nextSequence").asLong() - 1;

            Path manifestFile = node.dataDir().resolve("lsm").resolve("MANIFEST.json");
            byte[] oldManifest = Files.readAllBytes(manifestFile);
            JsonNode m = MAPPER.readTree(oldManifest);
            List<Long> referenced = new ArrayList<>();
            m.path("sstables").forEach(t -> referenced.add(t.path("tableId").asLong()));
            long highWater = m.path("flushedThroughSequence").asLong();
            node.check(!referenced.isEmpty() && highWater < target, "setup",
                    "expected an earlier table and unflushed mutations above " + highWater);
            node.check(Files.exists(node.walSegment(2)), "setup",
                    "the active WAL segment 2 should hold the unflushed mutations");

            node.armFailpoint("fls003-crash", """
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

            node.check(Arrays.equals(oldManifest, Files.readAllBytes(manifestFile)), "stopped volume",
                    "MANIFEST.json changed although the crash came before the manifest");
            Path sstDir = node.dataDir().resolve("lsm").resolve("sst");
            List<String> files = finalTables(sstDir);
            List<String> orphans = files.stream()
                    .filter(name -> referenced.stream().noneMatch(id -> tableFile(id).equals(name)))
                    .toList();
            node.check(orphans.size() == 1, "stopped volume",
                    "expected exactly one unreferenced final table, found " + orphans + " in " + files);
            long orphanId = Long.parseLong(orphans.get(0).substring(4, 24));
            expectValidOrphan(node, orphanId, later, highWater, target);
            node.check(Files.exists(node.walSegment(2)), "stopped volume",
                    "the WAL protecting the unflushed mutations was deleted before any publication");

            node.startReady();
            JsonNode recovered = node.storageStatus();
            node.check(recovered.path("stats").path("orphanFilesRemoved").asLong() == 1, "recovery",
                    "orphanFilesRemoved " + recovered.path("stats").path("orphanFilesRemoved") + ", expected 1");
            node.check(recovered.path("stats").path("walRecordsReplayed").asLong() >= later.size(), "recovery",
                    "walRecordsReplayed " + recovered.path("stats").path("walRecordsReplayed")
                            + ", expected at least the " + later.size() + " unflushed mutations");
            node.check(!Files.exists(sstDir.resolve(tableFile(orphanId))), "recovery",
                    "the orphan table " + orphanId + " is still on disk");

            JsonNode settled = awaitIdle(node, ID);
            expectNoOrphanPublished(node, settled, m, referenced, orphanId, target);
            node.check(!finalTables(sstDir).contains(tableFile(orphanId)), "recovery",
                    "the orphan table came back");

            expectModel(node, model, keys, "after restart");
        }
        report.pass();
    }

    private static void expectValidOrphan(WalScenarioNode node, long orphanId, List<Mutation> later,
                                          long highWater, long target) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = InspectSSTableCommand.run(node.dataDir(), orphanId,
                new PrintStream(out, true, StandardCharsets.UTF_8));
        String json = out.toString(StandardCharsets.UTF_8);
        node.check(exit == InspectSSTableCommand.EXIT_OK, "inspect orphan " + orphanId,
                "exit " + exit + ": " + json);

        Set<String> keys = new TreeSet<>();
        for (JsonNode record : MAPPER.readTree(json).path("records")) {
            keys.add(record.path("key").asText());
            long sequence = record.path("sequence").asLong();
            node.check(sequence > highWater && sequence <= target, "inspect orphan " + orphanId,
                    "record " + record + " is outside the unflushed range (" + highWater + ", " + target + "]");
        }
        Set<String> expected = new TreeSet<>();
        later.forEach(mutation -> expected.add(mutation.key()));
        node.check(keys.equals(expected), "inspect orphan " + orphanId,
                "keys " + keys + ", expected the unflushed keys " + expected);
    }

    private static void expectNoOrphanPublished(WalScenarioNode node, JsonNode status, JsonNode m,
                                                List<Long> referenced, long orphanId, long target) {
        JsonNode manifest = status.path("manifest");
        List<Long> listed = tableIds(status);
        node.check(!listed.contains(orphanId), "manifest",
                "the orphan table " + orphanId + " became part of the database: " + listed);

        long oldGeneration = m.path("generation").asLong();
        long generation = manifest.path("generation").asLong();
        if (generation == oldGeneration) {
            node.check(listed.equals(referenced), "manifest",
                    "manifest M lists " + referenced + ", status lists " + listed);
            node.check(manifest.path("flushedThroughSequence").asLong()
                            == m.path("flushedThroughSequence").asLong(), "manifest",
                    "the high-water mark moved without a publication");
            return;
        }
        node.check(generation == oldGeneration + 1, "manifest",
                "generation " + generation + ", expected " + oldGeneration + " or " + (oldGeneration + 1));
        node.check(listed.size() == referenced.size() + 1 && listed.containsAll(referenced), "manifest",
                "a later flush should add exactly one table to " + referenced + ", status lists " + listed);
        long added = listed.stream().filter(id -> !referenced.contains(id)).findFirst().orElseThrow();
        node.check(added > orphanId, "manifest",
                "the new table " + added + " reuses or precedes the orphan ID " + orphanId);
        node.check(manifest.path("flushedThroughSequence").asLong() >= target, "manifest",
                "the later publication does not cover the recovered mutations up to " + target);
    }
}
