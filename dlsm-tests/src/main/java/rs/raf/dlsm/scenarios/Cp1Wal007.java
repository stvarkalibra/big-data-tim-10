package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.fixtures.VolumeFixture;
import rs.raf.dlsm.fixtures.WalFixtureWriter;
import rs.raf.dlsm.fixtures.WalFixtureWriter.Mutation;
import rs.raf.dlsm.harness.Report;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
 *       {@code 5: PUT(gamma, G)}, {@code 6: PUT(alpha, A3)}. The invalid
 *       subcase is a separate volume with the same frames, except that the
 *       first record of segment 5 or 9 repeats or goes below the last
 *       sequence of the segment before it (from the seed).</li>
 *   <li><b>Operations:</b> valid set: {@code inspect wal}; start and wait
 *       for readiness; read every key; read the status;
 *       {@code PUT(delta, D)}; {@code SIGKILL}, restart; read every key
 *       again. Invalid set: {@code inspect wal}; start; read a key.</li>
 *   <li><b>Failpoint or corruption:</b> none; the crash is {@code SIGKILL}.
 *       The invalid set has valid framing and checksums; only the order of
 *       sequences across a segment boundary is wrong.</li>
 *   <li><b>Client outcome:</b> valid set: every request after readiness
 *       succeeds. Invalid set: readiness stays {@code 503} and every public
 *       request answers {@code 503 NODE_NOT_READY}.</li>
 *   <li><b>Logical oracle:</b> {@code alpha = A3}, {@code beta} absent,
 *       {@code gamma = G}; after the second restart also {@code delta = D}.
 *       The values follow the sequences, never the segment IDs or the
 *       listing order. The invalid set serves no key at all.</li>
 *   <li><b>Physical / status oracle:</b> recovery replays exactly six records
 *       and appends nothing (no WAL copies); {@code nextSequence} is 7 before
 *       {@code delta} and {@code delta} takes 7; the new active segment's ID
 *       is above 9 and never reused later; a prepared segment that still
 *       exists keeps its exact bytes (a flush may delete it once covered).
 *       The invalid set is {@code FAILED} with {@code WAL_SEQUENCE_ORDER} at
 *       the exact segment and offset, in {@code inspect wal} and in
 *       {@code lastFatalError}, and its WAL directory stays exactly as
 *       prepared: nothing sorted, cut or created.</li>
 *   <li><b>Proves:</b> I3 and I6 across segment boundaries: the greatest
 *       sequence wins, a tombstone in a later segment stays a deletion, gaps
 *       in segment IDs are harmless, and per-frame checksums do not make a
 *       log with a broken sequence order acceptable. <b>Does not prove:</b>
 *       anything about a damaged frame (WAL-003) or a torn tail (WAL-002).</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed.
 */
public final class Cp1Wal007 {

    public static final String ID = "CP1-WAL-007";

    private static final int PUBLIC_PORT = 18170;
    private static final int ADMIN_PORT = 18171;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** FORMAT.md §3.2: the first frame of a segment starts at this offset. */
    private static final int SEGMENT_HEADER_BYTES = 24;

    private Cp1Wal007() {
    }

    public static void run(long seed, Report report) throws Exception {
        runValidSet(seed);
        runInvalidOrder(seed);
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

    /**
     * The invalid subcase: every frame and checksum is valid, but the first
     * record of segment 5 or 9 repeats or goes below the last sequence of
     * the segment before it. Which boundary and which kind come from the seed.
     */
    private static void runInvalidOrder(long seed) throws Exception {
        boolean atNine = Math.floorMod(seed, 4L) >= 2;
        boolean duplicate = Math.floorMod(seed, 2L) == 0;
        long badSegment = atNine ? 9 : 5;
        long previousLast = atNine ? 4 : 2;
        long badSequence = duplicate ? previousLast : previousLast - 1;
        String badFile = String.format("wal-%020d.log", badSegment);
        String at = " (" + badFile + " starts with sequence " + badSequence
                + " after " + previousLast + ")";

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            Path walDir = VolumeFixture.walDir(node.dataDir());
            VolumeFixture.initialize(node.dataDir());
            Map<Long, byte[]> prepared = new LinkedHashMap<>();
            prepared.put(9L, write(walDir, 9, Mutation.put(atNine ? badSequence : 5, "gamma",
                    ascii("G-" + seed)), Mutation.put(6, "alpha", ascii("A3-" + seed))));
            prepared.put(5L, write(walDir, 5, Mutation.put(atNine ? 3 : badSequence, "alpha",
                    ascii("A2-" + seed)), Mutation.delete(4, "beta")));
            prepared.put(2L, write(walDir, 2, Mutation.put(1, "alpha", ascii("A1-" + seed)),
                    Mutation.put(2, "beta", ascii("B1-" + seed))));

            // The first record of the bad segment starts right after its header.
            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            JsonNode corruption = segmentReport(wal, badSegment).path("corruption");
            node.check(inspection.exitCode() == 1 && "CORRUPT".equals(wal.path("status").asText())
                            && wal.path("corruptSegmentId").asLong() == badSegment
                            && "WAL_SEQUENCE_ORDER".equals(corruption.path("category").asText())
                            && corruption.path("offset").asLong() == SEGMENT_HEADER_BYTES,
                    "inspect wal" + at, wal.toString());

            node.start();
            JsonNode ready = node.awaitLifecycle("FAILED");
            HttpResponse<String> readiness = node.readiness();
            node.check(readiness.statusCode() == 503, "readiness" + at,
                    "HTTP " + readiness.statusCode() + ": " + readiness.body());
            JsonNode fatal = ready.path("lastFatalError");
            String message = fatal.path("message").asText();
            node.check("WAL_SEQUENCE_ORDER".equals(fatal.path("code").asText())
                            && message.contains(badFile)
                            && message.contains("offset " + SEGMENT_HEADER_BYTES),
                    "lastFatalError" + at, fatal.toString());

            // No guessed order is applied: not even the first segment's key is served.
            HttpResponse<String> read = node.rawGet("beta");
            node.check(read.statusCode() == 503 && "NODE_NOT_READY".equals(
                            MAPPER.readTree(read.body()).path("code").asText()),
                    "GET beta" + at, "HTTP " + read.statusCode() + ": " + read.body());

            // Nothing was sorted, cut or created.
            node.kill();
            try (Stream<Path> files = Files.list(walDir)) {
                List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
                node.check(names.equals(List.of(String.format("wal-%020d.log", 2),
                                String.format("wal-%020d.log", 5), String.format("wal-%020d.log", 9))),
                        "WAL directory after startup" + at, "files " + names);
            }
            expectPreparedUnchanged(node, walDir, prepared, "after startup" + at);
        }
    }

    private static JsonNode segmentReport(JsonNode wal, long segmentId) {
        for (JsonNode segment : wal.path("segments")) {
            if (segment.path("segmentId").asLong() == segmentId) {
                return segment;
            }
        }
        throw new AssertionError(ID + " ORACLE inspect wal: no segment " + segmentId + " in " + wal);
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
