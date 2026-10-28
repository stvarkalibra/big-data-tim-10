package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import rs.raf.dlsm.fixtures.VolumeFixture;
import rs.raf.dlsm.fixtures.WalFixtureWriter;
import rs.raf.dlsm.fixtures.WalFixtureWriter.Mutation;
import rs.raf.dlsm.harness.Report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * CP1-WAL-007 — replay of several WAL segments in order (CP1 §29.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> a prepared volume written only from
 *       {@code FORMAT.md} by the fixture tools: the generation-0 manifest, no
 *       SSTable, and WAL segments {@code 2}, {@code 5} and {@code 9}, written
 *       in the order 9, 5, 2 and each forced. Segment 2 holds
 *       {@code 1: PUT(alpha, A1)}, {@code 2: PUT(beta, B1)}; segment 5
 *       {@code 3: PUT(alpha, A2)}, {@code 4: DELETE(beta)}; segment 9
 *       {@code 5: PUT(gamma, G)}, {@code 6: PUT(alpha, A3)}.</li>
 *   <li><b>Operations:</b> {@code inspect wal}; start and wait for
 *       readiness; read every key; read the status; {@code PUT(delta, D)};
 *       {@code SIGKILL}, restart; read every key again.</li>
 *   <li><b>Failpoint or corruption:</b> none; the crash is {@code SIGKILL}.</li>
 *   <li><b>Client outcome:</b> every request after readiness succeeds.</li>
 *   <li><b>Logical oracle:</b> {@code alpha = A3}, {@code beta} absent,
 *       {@code gamma = G}; after the second restart also {@code delta = D}.
 *       The values follow the sequences, never the segment IDs or the
 *       listing order.</li>
 *   <li><b>Physical / status oracle:</b> recovery replays exactly six records
 *       and appends nothing (no WAL copies); {@code nextSequence} is 7 before
 *       {@code delta} and {@code delta} takes 7; the new active segment's ID
 *       is above 9 and never reused later; a prepared segment that still
 *       exists keeps its exact bytes (a flush may delete it once covered).</li>
 *   <li><b>Proves:</b> I3 and I6 across segment boundaries: the greatest
 *       sequence wins, a tombstone in a later segment stays a deletion, and
 *       gaps in segment IDs are harmless. <b>Does not prove:</b> the handling
 *       of an invalid sequence order, which the second subcase covers.</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed.
 */
public final class Cp1Wal007 {

    public static final String ID = "CP1-WAL-007";

    private static final int PUBLIC_PORT = 18170;
    private static final int ADMIN_PORT = 18171;

    private Cp1Wal007() {
    }

    public static void run(long seed, Report report) throws Exception {
        runValidSet(seed);
        report.pass();
    }

    /** The valid prepared set of CP1-WAL-007. */
    private static void runValidSet(long seed) throws Exception {
        byte[] a1 = ascii("A1-" + seed);
        byte[] b1 = ascii("B1-" + seed);
        byte[] a2 = ascii("A2-" + seed);
        byte[] g = ascii("G-" + seed);
        byte[] a3 = ascii("A3-" + seed);
        byte[] d = ascii("D-" + seed);

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            // Written in the order 9, 5, 2: neither creation nor listing order is the log order.
            Path walDir = VolumeFixture.walDir(node.dataDir());
            VolumeFixture.initialize(node.dataDir());
            Map<Long, byte[]> prepared = new LinkedHashMap<>();
            prepared.put(9L, write(walDir, 9, Mutation.put(5, "gamma", g), Mutation.put(6, "alpha", a3)));
            prepared.put(5L, write(walDir, 5, Mutation.put(3, "alpha", a2), Mutation.delete(4, "beta")));
            prepared.put(2L, write(walDir, 2, Mutation.put(1, "alpha", a1), Mutation.put(2, "beta", b1)));

            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            node.check(inspection.exitCode() == 0 && "VALID".equals(wal.path("status").asText())
                            && wal.path("recordCount").asInt() == 6
                            && wal.path("lastSequence").asLong() == 6,
                    "inspect wal of the prepared volume", wal.toString());

            node.startReady();
            expectValue(node, "alpha", a3, "after recovery");
            expectValue(node, "beta", null, "after recovery");
            expectValue(node, "gamma", g, "after recovery");

            JsonNode recovered = node.storageStatus();
            JsonNode stats = recovered.path("stats");
            node.check(stats.path("walRecordsReplayed").asLong() == 6, "recovery",
                    "walRecordsReplayed " + stats.path("walRecordsReplayed") + ", expected 6");
            node.check(stats.path("walAppendAttempts").asLong() == 0
                            && stats.path("walBytesWritten").asLong() == 0, "recovery",
                    "replay appended to the WAL: walAppendAttempts " + stats.path("walAppendAttempts")
                            + ", walBytesWritten " + stats.path("walBytesWritten"));
            node.check(recovered.path("nextSequence").asLong() == 7, "recovery",
                    "nextSequence " + recovered.path("nextSequence") + ", expected 7");
            long firstActive = recovered.path("activeMemtable").path("walSegmentId").asLong();
            node.check(firstActive > 9, "recovery",
                    "new active WAL segment " + firstActive + " is not above 9");
            expectPreparedUnchanged(node, walDir, prepared, "after recovery");

            node.put("delta", d);
            long sequence = node.storageStatus().path("activeMemtable").path("maxSequence").asLong();
            node.check(sequence == 7, "PUT delta", "got sequence " + sequence + ", expected 7");

            node.kill();
            node.startReady();
            expectValue(node, "alpha", a3, "after the second restart");
            expectValue(node, "beta", null, "after the second restart");
            expectValue(node, "gamma", g, "after the second restart");
            expectValue(node, "delta", d, "after the second restart");

            JsonNode second = node.storageStatus();
            node.check(second.path("nextSequence").asLong() == 8, "second recovery",
                    "nextSequence " + second.path("nextSequence") + ", expected 8");
            long secondActive = second.path("activeMemtable").path("walSegmentId").asLong();
            node.check(secondActive > firstActive, "second recovery",
                    "active WAL segment " + secondActive + " is not above " + firstActive);
            expectPreparedUnchanged(node, walDir, prepared, "after the second restart");
        }
    }

    /** Writes one prepared segment and returns its exact bytes. */
    private static byte[] write(Path walDir, long segmentId, Mutation... mutations) throws Exception {
        return Files.readAllBytes(WalFixtureWriter.writeSegment(walDir, segmentId, mutations));
    }

    /** Replay never writes into an old segment; a flush may delete a covered one. */
    private static void expectPreparedUnchanged(WalScenarioNode node, Path walDir,
                                                Map<Long, byte[]> prepared, String when)
            throws Exception {
        for (Map.Entry<Long, byte[]> segment : prepared.entrySet()) {
            Path file = walDir.resolve(String.format("wal-%020d.log", segment.getKey()));
            byte[] now;
            try {
                now = Files.readAllBytes(file);
            } catch (NoSuchFileException deletedByFlush) {
                continue;
            }
            node.check(Arrays.equals(segment.getValue(), now), "prepared segment " + segment.getKey()
                    + " " + when, "changed: " + now.length + " bytes, was " + segment.getValue().length);
        }
    }

    /** {@code expected == null} means the key must be absent. */
    private static void expectValue(WalScenarioNode node, String key, byte[] expected,
                                    String when) throws Exception {
        Optional<byte[]> actual = node.get(key);
        boolean matches = expected == null
                ? actual.isEmpty()
                : actual.isPresent() && Arrays.equals(expected, actual.get());
        node.check(matches, "GET " + key + " " + when,
                "expected " + text(expected) + " but got " + text(actual.orElse(null)));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String text(byte[] value) {
        return value == null ? "absent" : new String(value, StandardCharsets.US_ASCII);
    }
}
