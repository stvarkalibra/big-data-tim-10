package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import rs.raf.dlsm.harness.Report;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;

/**
 * CP1-WAL-005 — crash after the WAL force, before the MemTable (CP1 §29.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh node on an empty volume, without
 *       {@code alpha}.</li>
 *   <li><b>Operations:</b> {@code PUT(alpha, A)}; restart; {@code GET(alpha)};
 *       {@code SIGKILL}, restart; {@code GET(alpha)}.</li>
 *   <li><b>Failpoint:</b> {@code wal.after-sync.before-memtable} with
 *       {@code CRASH}, filtered to the request ID of that PUT.</li>
 *   <li><b>Client outcome:</b> no response; the connection breaks when the
 *       process halts. A made-up error response before the halt fails the
 *       scenario.</li>
 *   <li><b>Logical oracle:</b> {@code GET(alpha)} is exactly {@code A}
 *       after both restarts. Absence is not an allowed outcome: the
 *       failpoint fires only after a successful force.</li>
 *   <li><b>Physical / status oracle:</b> before recovery, {@code inspect wal}
 *       reports a {@code VALID} log holding exactly that one record, so
 *       there is nothing torn or corrupt. Recovery replays it
 *       ({@code walRecordsReplayed} 1, {@code walTornTailsTruncated} 0),
 *       sets {@code nextSequence} above it and opens a new active segment
 *       that holds only its header: no copy of the record is appended.</li>
 *   <li><b>Proves:</b> I1/I2 at the durability boundary: the forced WAL
 *       record, not the MemTable, is the authoritative copy, and a request
 *       without a response can still be durable. <b>Does not prove:</b>
 *       anything about a record that was not forced (WAL-006).</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed.
 */
public final class Cp1Wal005 {

    public static final String ID = "CP1-WAL-005";

    private static final int PUBLIC_PORT = 18150;
    private static final int ADMIN_PORT = 18151;
    /** WAL segment header (FORMAT.md §3.2): an empty segment is exactly this long. */
    private static final long SEGMENT_HEADER_BYTES = 24;

    private Cp1Wal005() {
    }

    public static void run(long seed, Report report) throws Exception {
        byte[] value = ("A-" + seed).getBytes(StandardCharsets.US_ASCII);

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            node.check(node.get("alpha").isEmpty(), "fresh node", "alpha already exists");
            long crashedSegment = node.storageStatus()
                    .path("activeMemtable").path("walSegmentId").asLong();

            String requestId = node.nextRequestId();
            node.armFailpoint("wal005-crash", """
                    {"name": "wal.after-sync.before-memtable", "action": "CRASH",
                     "filters": {"requestId": "%s"}}
                    """.formatted(requestId));

            Optional<HttpResponse<String>> response = node.putMayCrash("alpha", value, requestId);
            node.check(response.isEmpty(), "PUT alpha",
                    "expected no response, got HTTP " + response.map(HttpResponse::statusCode)
                            .orElse(0) + ": " + response.map(HttpResponse::body).orElse(""));
            node.awaitExit();

            // The forced record is complete on disk: nothing torn, nothing corrupt.
            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            node.check(inspection.exitCode() == 0 && "VALID".equals(wal.path("status").asText()),
                    "inspect wal after the crash", wal.toString());
            node.check(wal.path("recordCount").asInt() == 1
                            && wal.path("lastSequence").asLong() == 1,
                    "inspect wal after the crash", "expected exactly sequence 1: " + wal);

            node.startReady();
            expectAlpha(node, value, "after the first restart");

            JsonNode recovered = node.storageStatus();
            JsonNode stats = recovered.path("stats");
            node.check(stats.path("walRecordsReplayed").asLong() == 1, "first recovery",
                    "walRecordsReplayed " + stats.path("walRecordsReplayed") + ", expected 1");
            node.check(stats.path("walTornTailsTruncated").asLong() == 0, "first recovery",
                    "walTornTailsTruncated " + stats.path("walTornTailsTruncated"));
            node.check(recovered.path("nextSequence").asLong() == 2, "first recovery",
                    "nextSequence " + recovered.path("nextSequence") + ", expected 2");
            long activeSegment = recovered.path("activeMemtable").path("walSegmentId").asLong();
            node.check(activeSegment > crashedSegment, "first recovery",
                    "active WAL segment " + activeSegment + " is not newer than " + crashedSegment);
            // Replay applies the record in memory only; it never appends a copy.
            long activeSize = Files.size(node.walSegment(activeSegment));
            node.check(activeSize == SEGMENT_HEADER_BYTES, "first recovery",
                    "new active segment has " + activeSize + " bytes, expected only the header");

            node.kill();
            node.startReady();
            expectAlpha(node, value, "after the second restart");
            JsonNode second = node.storageStatus();
            node.check(second.path("nextSequence").asLong() == 2, "second recovery",
                    "nextSequence " + second.path("nextSequence") + ", expected 2");
            node.check(second.path("stats").path("walTornTailsTruncated").asLong() == 0,
                    "second recovery", "walTornTailsTruncated "
                            + second.path("stats").path("walTornTailsTruncated"));
        }
        report.pass();
    }

    private static void expectAlpha(WalScenarioNode node, byte[] expected, String when)
            throws Exception {
        Optional<byte[]> actual = node.get("alpha");
        node.check(actual.isPresent() && Arrays.equals(expected, actual.get()), "GET alpha " + when,
                "expected " + new String(expected, StandardCharsets.US_ASCII) + " but got "
                        + actual.map(v -> new String(v, StandardCharsets.US_ASCII))
                        .orElse("absent"));
    }
}
