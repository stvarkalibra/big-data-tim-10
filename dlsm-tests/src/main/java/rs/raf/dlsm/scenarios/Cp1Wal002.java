package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import rs.raf.dlsm.harness.Report;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * CP1-WAL-002 — torn final frame at several cut points (CP1 §29.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> per cut, a fresh volume with acknowledged
 *       {@code PUT(base1, V1)} and {@code PUT(base2, V2)}; the active segment
 *       ID and its exact length {@code L} are recorded, and the length of the
 *       next frame is computed independently as {@code 30 + k + v}
 *       (FORMAT.md §3.3).</li>
 *   <li><b>Operations:</b> {@code PUT(torn, T)}; {@code inspect wal};
 *       restart; {@code SIGKILL}, restart.</li>
 *   <li><b>Failpoint:</b> {@code wal.after-record-prefix} with {@code CRASH}
 *       and {@code prefixBytes} at three cuts: inside the 8-byte length
 *       guard (from the seed), right after it, and one byte before the end
 *       of the frame.</li>
 *   <li><b>Client outcome:</b> no response for {@code torn}; the base PUTs
 *       are acknowledged.</li>
 *   <li><b>Logical oracle:</b> after both restarts {@code base1} and
 *       {@code base2} are exact and {@code torn} is {@code NOT_FOUND}: no
 *       complete frame of it ever existed.</li>
 *   <li><b>Physical / status oracle:</b> after the crash the segment is
 *       exactly {@code L + prefixBytes} long and {@code inspect wal} reports
 *       {@code TORN_TAIL} at frame offset {@code L}. The first recovery
 *       truncates it to exactly {@code L} ({@code walTornTailsTruncated} 1)
 *       and becomes {@code READY} without reporting corruption; the second
 *       truncates nothing. While the repaired segment exists, it stays
 *       {@code L} long; a later flush may delete it.</li>
 *   <li><b>Proves:</b> only a strictly incomplete final frame is cut, at
 *       its start, durably and once; earlier complete frames are untouched.
 *       <b>Does not prove:</b> the handling of a complete damaged frame,
 *       which is corruption (WAL-003).</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed.
 */
public final class Cp1Wal002 {

    public static final String ID = "CP1-WAL-002";

    private static final int PUBLIC_PORT = 18120;
    private static final int ADMIN_PORT = 18121;

    /** FORMAT.md §3.2–§3.3: segment header, guarded frame header, fixed frame bytes. */
    private static final int SEGMENT_HEADER_BYTES = 24;
    private static final int LENGTH_GUARD_BYTES = 8;
    private static final int FRAME_OVERHEAD = 30;

    private Cp1Wal002() {
    }

    public static void run(long seed, Report report) throws Exception {
        byte[] torn = ("T-" + seed).getBytes(StandardCharsets.US_ASCII);
        int tornFrame = FRAME_OVERHEAD + "torn".length() + torn.length;

        int insideGuard = 1 + (int) Math.floorMod(seed, LENGTH_GUARD_BYTES - 1);
        for (int cut : List.of(insideGuard, LENGTH_GUARD_BYTES, tornFrame - 1)) {
            runCut(seed, cut, torn);
        }
        report.pass();
    }

    private static void runCut(long seed, int cut, byte[] torn) throws Exception {
        byte[] v1 = ("V1-" + seed).getBytes(StandardCharsets.US_ASCII);
        byte[] v2 = ("V2-" + seed).getBytes(StandardCharsets.US_ASCII);
        String at = " (prefixBytes " + cut + ")";

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            node.put("base1", v1);
            node.put("base2", v2);

            long segment = node.storageStatus().path("activeMemtable").path("walSegmentId").asLong();
            Path segmentFile = node.walSegment(segment);
            long length = Files.size(segmentFile);
            long expectedLength = SEGMENT_HEADER_BYTES
                    + FRAME_OVERHEAD + "base1".length() + v1.length
                    + FRAME_OVERHEAD + "base2".length() + v2.length;
            node.check(length == expectedLength, "segment before the crash" + at,
                    "length " + length + ", expected " + expectedLength);

            String requestId = node.nextRequestId();
            node.armFailpoint("wal002-crash", """
                    {"name": "wal.after-record-prefix", "action": "CRASH",
                     "filters": {"requestId": "%s"}, "parameters": {"prefixBytes": %d}}
                    """.formatted(requestId, cut));
            Optional<HttpResponse<String>> response = node.putMayCrash("torn", torn, requestId);
            node.check(response.isEmpty(), "PUT torn" + at,
                    "expected no response, got HTTP " + response.map(HttpResponse::statusCode)
                            .orElse(0));
            node.awaitExit();

            // Exactly the strict prefix reached the file.
            node.check(Files.size(segmentFile) == length + cut, "segment after the crash" + at,
                    "length " + Files.size(segmentFile) + ", expected " + (length + cut));

            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            node.check(inspection.exitCode() == 0 && "TORN_TAIL".equals(wal.path("status").asText())
                            && wal.path("tornSegmentId").asLong() == segment
                            && wal.path("recordCount").asInt() == 2,
                    "inspect wal before recovery" + at, wal.toString());
            JsonNode tornTail = segmentReport(wal, segment).path("tornTail");
            node.check(tornTail.path("frameOffset").asLong() == length, "inspect wal before recovery" + at,
                    "torn frame at " + tornTail.path("frameOffset") + ", expected " + length);

            node.startReady();
            expectBases(node, v1, v2, "after the first restart" + at);
            JsonNode first = node.storageStatus();
            node.check(first.path("stats").path("walTornTailsTruncated").asLong() == 1,
                    "first recovery" + at, "walTornTailsTruncated "
                            + first.path("stats").path("walTornTailsTruncated") + ", expected 1");
            expectRepairedLength(node, segmentFile, length, "after the first restart" + at);

            node.kill();
            node.startReady();
            expectBases(node, v1, v2, "after the second restart" + at);
            JsonNode second = node.storageStatus();
            node.check(second.path("stats").path("walTornTailsTruncated").asLong() == 0,
                    "second recovery" + at, "walTornTailsTruncated "
                            + second.path("stats").path("walTornTailsTruncated") + ", expected 0");
            expectRepairedLength(node, segmentFile, length, "after the second restart" + at);
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

    private static void expectBases(WalScenarioNode node, byte[] v1, byte[] v2, String when)
            throws Exception {
        expectValue(node, "base1", v1, when);
        expectValue(node, "base2", v2, when);
        expectValue(node, "torn", null, when);
    }

    /** A flush may already have deleted the repaired segment; if it exists, its end is {@code L}. */
    private static void expectRepairedLength(WalScenarioNode node, Path segmentFile, long length,
                                             String when) throws Exception {
        long size;
        try {
            size = Files.size(segmentFile);
        } catch (NoSuchFileException deletedByFlush) {
            return;
        }
        node.check(size == length, "repaired segment " + when,
                "length " + size + ", expected " + length);
    }

    /** {@code expected == null} means the key must be absent. */
    private static void expectValue(WalScenarioNode node, String key, byte[] expected,
                                    String when) throws Exception {
        Optional<byte[]> actual = node.get(key);
        boolean matches = expected == null
                ? actual.isEmpty()
                : actual.isPresent() && Arrays.equals(expected, actual.get());
        node.check(matches, "GET " + key + " " + when, "expected "
                + (expected == null ? "absent" : new String(expected, StandardCharsets.US_ASCII))
                + " but got " + actual.map(v -> new String(v, StandardCharsets.US_ASCII))
                .orElse("absent"));
    }
}
