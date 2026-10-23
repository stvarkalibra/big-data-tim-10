package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import rs.raf.dlsm.harness.Report;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * CP1-WAL-006 — crash after a complete append, before the force (CP1 §29.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> a fresh node with an acknowledged
 *       {@code PUT(base, V)}.</li>
 *   <li><b>Operations:</b> {@code PUT(candidate, C)}; restart; read both
 *       keys; {@code PUT(after, R)}; {@code SIGKILL}, restart; read all
 *       three.</li>
 *   <li><b>Failpoint:</b> {@code wal.after-append.before-sync} with
 *       {@code CRASH}, filtered to the request ID of the candidate PUT.</li>
 *   <li><b>Client outcome:</b> no response for the candidate (ambiguous);
 *       {@code base} and {@code after} are acknowledged.</li>
 *   <li><b>Logical oracle:</b> exactly two complete models are allowed after
 *       the first restart, {@code {base}} and {@code {base, candidate=C}};
 *       no other bytes. Which one is decided by what the crash left on disk:
 *       recovery must keep the candidate exactly when {@code inspect wal}
 *       showed its complete frame, because replay never drops a valid frame
 *       (CP1 §21.3). {@code base} and {@code after} always survive, and the
 *       candidate stays as it was after the second restart.</li>
 *   <li><b>Physical / status oracle:</b> before recovery the WAL is
 *       {@code VALID} or ends in a torn tail, never {@code CORRUPT}, and
 *       holds the base frame. {@code nextSequence} is one above the last
 *       frame recovery kept, so {@code after} takes exactly that sequence
 *       and nothing collides.</li>
 *   <li><b>Proves:</b> the ambiguous outcome before a force never yields a
 *       partial value, never loses earlier acknowledged data and never
 *       reuses a sequence. <b>Does not prove:</b> that the candidate must
 *       be present or absent; this run may show either.</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed.
 */
public final class Cp1Wal006 {

    public static final String ID = "CP1-WAL-006";

    private static final int PUBLIC_PORT = 18160;
    private static final int ADMIN_PORT = 18161;

    private Cp1Wal006() {
    }

    public static void run(long seed, Report report) throws Exception {
        byte[] base = ("V-" + seed).getBytes(StandardCharsets.US_ASCII);
        byte[] candidate = ("C-" + seed).getBytes(StandardCharsets.US_ASCII);
        byte[] after = ("R-" + seed).getBytes(StandardCharsets.US_ASCII);

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            node.startReady();
            node.put("base", base);

            String requestId = node.nextRequestId();
            node.armFailpoint("wal006-crash", """
                    {"name": "wal.after-append.before-sync", "action": "CRASH",
                     "filters": {"requestId": "%s"}}
                    """.formatted(requestId));
            Optional<HttpResponse<String>> response =
                    node.putMayCrash("candidate", candidate, requestId);
            node.check(response.isEmpty(), "PUT candidate",
                    "expected no response, got HTTP " + response.map(HttpResponse::statusCode)
                            .orElse(0) + ": " + response.map(HttpResponse::body).orElse(""));
            node.awaitExit();

            // What the crash left: base (sequence 1) and maybe the whole candidate frame.
            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            String walStatus = wal.path("status").asText();
            node.check(inspection.exitCode() == 0
                            && ("VALID".equals(walStatus) || "TORN_TAIL".equals(walStatus)),
                    "inspect wal after the crash", wal.toString());
            long lastKept = wal.path("lastSequence").asLong();
            node.check(lastKept == 1 || lastKept == 2, "inspect wal after the crash",
                    "lastSequence " + wal.path("lastSequence") + ", expected 1 or 2");
            boolean candidateKept = lastKept == 2;

            node.startReady();
            expectValue(node, "base", base, "after the first restart");
            expectValue(node, "candidate", candidateKept ? candidate : null,
                    "after the first restart (candidate frame "
                            + (candidateKept ? "complete" : "absent") + " on disk)");

            JsonNode recovered = node.storageStatus();
            node.check(recovered.path("nextSequence").asLong() == lastKept + 1, "first recovery",
                    "nextSequence " + recovered.path("nextSequence") + ", expected "
                            + (lastKept + 1));

            node.put("after", after);
            JsonNode afterPut = node.storageStatus();
            node.check(afterPut.path("activeMemtable").path("maxSequence").asLong() == lastKept + 1,
                    "PUT after", "got sequence " + afterPut.path("activeMemtable").path("maxSequence")
                            + ", expected " + (lastKept + 1));

            node.kill();
            node.startReady();
            expectValue(node, "base", base, "after the second restart");
            expectValue(node, "candidate", candidateKept ? candidate : null,
                    "after the second restart");
            expectValue(node, "after", after, "after the second restart");
            JsonNode second = node.storageStatus();
            node.check(second.path("nextSequence").asLong() == lastKept + 2, "second recovery",
                    "nextSequence " + second.path("nextSequence") + ", expected "
                            + (lastKept + 2));
        }
        report.pass();
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

    private static String text(byte[] value) {
        return value == null ? "absent" : new String(value, StandardCharsets.US_ASCII);
    }
}
