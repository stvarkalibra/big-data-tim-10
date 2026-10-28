package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.Report;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * CP1-WAL-004 — injected I/O errors on append and force (CP1 §29.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> per subcase a fresh node with an acknowledged
 *       {@code PUT(base, V)}; counters reset right before the candidate.</li>
 *   <li><b>Operations:</b> {@code PUT(candidate, C)}; {@code GET(candidate)};
 *       one more mutation, {@code PUT(other)} or {@code DELETE(base)} from
 *       the seed; {@code SIGKILL}; {@code inspect wal}; restart; read every
 *       key; {@code PUT(after, R)}.</li>
 *   <li><b>Failpoint:</b> {@code IO_ERROR} filtered to the candidate's
 *       request, on (a) {@code wal.before-append}, (b)
 *       {@code wal.after-record-prefix} with {@code prefixBytes} from the seed,
 *       (c) {@code wal.after-append.before-sync}.</li>
 *   <li><b>Client outcome:</b> the candidate gets {@code 500 STORAGE_IO},
 *       never success, and is never readable; the engine is {@code FAILED},
 *       and the next mutation is refused with a storage or not-ready
 *       error.</li>
 *   <li><b>Logical oracle:</b> after the restart {@code base = V} always and
 *       the refused mutation left no trace; the candidate is absent in (a)
 *       and (b), and in (c) exactly {@code C} or absent, decided by what
 *       {@code inspect wal} saw on disk (as in WAL-006); {@code after} gets
 *       the next sequence, so nothing collides.</li>
 *   <li><b>Physical / status oracle:</b> (a) no byte of the candidate is
 *       written: one append attempt, zero bytes, the file keeps its length;
 *       (b) exactly the prefix stays, {@code inspect wal} sees a torn tail at
 *       the old end and recovery cuts it once; (c) one completed append and
 *       one force attempt, but no successful force. In every subcase the
 *       refused mutation appends nothing.</li>
 *   <li><b>Proves:</b> I2 and the fatal write-path rule of CP1 §9.3: a
 *       mutation whose WAL I/O failed is never acknowledged or visible, and
 *       nothing is appended behind the uncertain tail. <b>Does not prove:</b>
 *       that the candidate of (c) must survive or vanish; this run may show
 *       either.</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed.
 */
public final class Cp1Wal004 {

    public static final String ID = "CP1-WAL-004";

    private static final int PUBLIC_PORT = 18140;
    private static final int ADMIN_PORT = 18141;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** FORMAT.md §3.2–§3.3. */
    private static final int SEGMENT_HEADER_BYTES = 24;
    private static final int FRAME_OVERHEAD = 30;

    private static final String BASE = "base";
    private static final String CANDIDATE = "candidate";
    private static final String OTHER = "other";

    private Cp1Wal004() {
    }

    /** Where the failpoint stops the candidate's append. */
    private enum Subcase {
        BEFORE_APPEND("(a) wal.before-append"),
        AFTER_PREFIX("(b) wal.after-record-prefix"),
        BEFORE_SYNC("(c) wal.after-append.before-sync");

        final String label;

        Subcase(String label) {
            this.label = label;
        }
    }

    /** The acknowledged base: its segment and the file length {@code L} after it. */
    private record Prepared(long segmentId, Path segmentFile, long baseLength, long resetEpoch) {
    }

    public static void run(long seed, Report report) throws Exception {
        runBeforeAppend(seed);
        runAfterPrefix(seed);
        runBeforeSync(seed);
        report.pass();
    }

    /** (a): the error comes before the first byte, so nothing of the candidate is on disk. */
    private static void runBeforeAppend(long seed) throws Exception {
        Subcase subcase = Subcase.BEFORE_APPEND;
        byte[] base = ascii("V-" + seed);
        byte[] candidate = ascii("C-" + seed);

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            Prepared prepared = prepare(node, base);
            JsonNode stats = failCandidate(node, seed, subcase, prepared, candidate, """
                    {"name": "wal.before-append", "action": "IO_ERROR",
                     "filters": {"requestId": "%s"}}
                    """);

            String at = " " + subcase.label;
            expectCounter(node, stats, "walAppendAttempts", 1, at);
            expectCounter(node, stats, "walAppendsCompleted", 0, at);
            expectCounter(node, stats, "walBytesWritten", 0, at);
            expectCounter(node, stats, "walForceAttempts", 0, at);
            node.check(Files.size(prepared.segmentFile()) == prepared.baseLength(),
                    "segment after the failure" + at, "length " + Files.size(prepared.segmentFile())
                            + ", expected " + prepared.baseLength() + " (no candidate byte)");

            node.kill();
            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            node.check(inspection.exitCode() == 0 && "VALID".equals(wal.path("status").asText())
                            && wal.path("lastSequence").asLong() == 1,
                    "inspect wal before recovery" + at, wal.toString());

            expectRecovered(node, seed, subcase, prepared, base, null, 1, 0);
        }
    }

    /**
     * (b): the error comes after a strict prefix of the candidate's frame,
     * which stays on disk as a torn tail that recovery cuts once.
     */
    private static void runAfterPrefix(long seed) throws Exception {
        Subcase subcase = Subcase.AFTER_PREFIX;
        byte[] base = ascii("V-" + seed);
        byte[] candidate = ascii("C-" + seed);
        int frameLength = FRAME_OVERHEAD + CANDIDATE.length() + candidate.length;
        // Any strict prefix, from inside the guarded length to one byte short.
        int prefix = 1 + (int) Math.floorMod(seed, (long) frameLength - 1);
        String at = " " + subcase.label + " prefixBytes " + prefix;

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            Prepared prepared = prepare(node, base);
            JsonNode stats = failCandidate(node, seed, subcase, prepared, candidate, """
                    {"name": "wal.after-record-prefix", "action": "IO_ERROR",
                     "filters": {"requestId": "%%s"}, "parameters": {"prefixBytes": %d}}
                    """.formatted(prefix));

            expectCounter(node, stats, "walAppendAttempts", 1, at);
            expectCounter(node, stats, "walAppendsCompleted", 0, at);
            expectCounter(node, stats, "walBytesWritten", prefix, at);
            expectCounter(node, stats, "walForceAttempts", 0, at);
            long length = Files.size(prepared.segmentFile());
            node.check(length == prepared.baseLength() + prefix, "segment after the failure" + at,
                    "length " + length + ", expected " + (prepared.baseLength() + prefix));

            node.kill();
            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            node.check(inspection.exitCode() == 0 && "TORN_TAIL".equals(wal.path("status").asText())
                            && wal.path("tornSegmentId").asLong() == prepared.segmentId()
                            && wal.path("lastSequence").asLong() == 1,
                    "inspect wal before recovery" + at, wal.toString());
            JsonNode tornTail = segmentReport(wal, prepared.segmentId()).path("tornTail");
            node.check(tornTail.path("frameOffset").asLong() == prepared.baseLength(),
                    "inspect wal before recovery" + at, "torn frame at "
                            + tornTail.path("frameOffset") + ", expected " + prepared.baseLength());

            expectRecovered(node, seed, subcase, prepared, base, null, 1, 1);

            // Cut exactly at L; a flush after READY may already have deleted the segment.
            try {
                long repaired = Files.size(prepared.segmentFile());
                node.check(repaired == prepared.baseLength(), "repaired segment" + at,
                        "length " + repaired + ", expected " + prepared.baseLength());
            } catch (NoSuchFileException deletedByFlush) {
                // Allowed: its records are published in a table by now.
            }
        }
    }

    /**
     * (c): the frame is complete, but its force failed, so its durability is
     * unknown. After the restart the candidate is exactly {@code C} or absent;
     * which one is decided by what the kill left on disk, because replay never
     * drops a valid complete frame (CP1 §21.3).
     */
    private static void runBeforeSync(long seed) throws Exception {
        Subcase subcase = Subcase.BEFORE_SYNC;
        byte[] base = ascii("V-" + seed);
        byte[] candidate = ascii("C-" + seed);
        int frameLength = FRAME_OVERHEAD + CANDIDATE.length() + candidate.length;
        String at = " " + subcase.label;

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            Prepared prepared = prepare(node, base);
            JsonNode stats = failCandidate(node, seed, subcase, prepared, candidate, """
                    {"name": "wal.after-append.before-sync", "action": "IO_ERROR",
                     "filters": {"requestId": "%s"}}
                    """);

            expectCounter(node, stats, "walAppendAttempts", 1, at);
            expectCounter(node, stats, "walAppendsCompleted", 1, at);
            expectCounter(node, stats, "walBytesWritten", frameLength, at);
            expectCounter(node, stats, "walForceAttempts", 1, at);
            expectCounter(node, stats, "walForcesSucceeded", 0, at);
            long length = Files.size(prepared.segmentFile());
            node.check(length == prepared.baseLength() + frameLength, "segment after the failure" + at,
                    "length " + length + ", expected " + (prepared.baseLength() + frameLength));

            // Either complete model is allowed, never corruption or a partial value.
            node.kill();
            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            String walStatus = wal.path("status").asText();
            long lastKept = wal.path("lastSequence").asLong();
            node.check(inspection.exitCode() == 0
                            && ("VALID".equals(walStatus) || "TORN_TAIL".equals(walStatus))
                            && (lastKept == 1 || lastKept == 2),
                    "inspect wal before recovery" + at, wal.toString());
            boolean candidateKept = lastKept == 2;

            expectRecovered(node, seed, subcase, prepared, base, candidateKept ? candidate : null,
                    lastKept, "TORN_TAIL".equals(walStatus) ? 1 : 0);
        }
    }

    // ===== Shared steps =====

    /** A fresh node with an acknowledged {@code PUT(base, V)}; counters reset afterwards. */
    private static Prepared prepare(WalScenarioNode node, byte[] base) throws Exception {
        node.startReady();
        node.put(BASE, base);

        long segment = node.storageStatus().path("activeMemtable").path("walSegmentId").asLong();
        Path file = node.walSegment(segment);
        long length = Files.size(file);
        long expected = SEGMENT_HEADER_BYTES + FRAME_OVERHEAD + BASE.length() + base.length;
        node.check(length == expected, "segment before the candidate",
                "length " + length + ", expected " + expected);

        return new Prepared(segment, file, length, node.resetStats());
    }

    /**
     * Sends {@code PUT(candidate, C)} with {@code rule} armed for its request
     * only, then checks what every subcase shares: an error, never success;
     * the engine is {@code FAILED}; the candidate is not readable; the next
     * mutation is refused and appends nothing.
     *
     * @param rule the failpoint body, with {@code %s} for the request ID
     * @return the counters right after the candidate failed
     */
    private static JsonNode failCandidate(WalScenarioNode node, long seed, Subcase subcase,
                                          Prepared prepared, byte[] candidate, String rule)
            throws Exception {
        String at = " " + subcase.label;

        String requestId = node.nextRequestId();
        node.armFailpoint("wal004-" + subcase.name().toLowerCase(Locale.ROOT),
                rule.formatted(requestId));
        HttpResponse<String> response = node.rawPut(CANDIDATE, candidate, requestId);
        JsonNode body = MAPPER.readTree(response.body());
        node.check(response.statusCode() == 500 && "STORAGE_IO".equals(body.path("code").asText())
                        && requestId.equals(body.path("requestId").asText()),
                "PUT candidate" + at, "HTTP " + response.statusCode() + ": " + response.body());

        // CP1 §9.3: the write path is fatally failed.
        JsonNode failed = node.storageStatus();
        node.check("FAILED".equals(failed.path("lifecycle").asText())
                        && "STORAGE_IO".equals(failed.path("lastFatalError").path("code").asText()),
                "status after the failure" + at, "lifecycle " + failed.path("lifecycle")
                        + ", lastFatalError " + failed.path("lastFatalError"));
        JsonNode stats = failed.path("stats");
        node.check(stats.path("resetEpoch").asLong() == prepared.resetEpoch(), "counters" + at,
                "counters were reset by someone else: " + stats.path("resetEpoch"));
        long length = Files.size(prepared.segmentFile());

        // Never visible: reads may be refused or report the key absent (CP1-WAL-004).
        expectOneOf(node, node.rawGet(CANDIDATE),
                Map.of(503, "NODE_NOT_READY", 404, "NOT_FOUND"), "GET candidate" + at);

        // The next mutation is refused and touches no WAL.
        boolean delete = Math.floorMod(seed + subcase.ordinal(), 2L) == 1;
        HttpResponse<String> next = delete
                ? node.rawDelete(BASE)
                : node.rawPut(OTHER, ascii("O-" + seed), node.nextRequestId());
        expectOneOf(node, next, Map.of(503, "NODE_NOT_READY", 500, "STORAGE_IO"),
                (delete ? "DELETE base" : "PUT other") + " after the failure" + at);
        JsonNode afterNext = node.storageStatus().path("stats");
        node.check(afterNext.path("walAppendAttempts").asLong()
                        == stats.path("walAppendAttempts").asLong()
                        && Files.size(prepared.segmentFile()) == length,
                "refused mutation" + at, "it reached the WAL: walAppendAttempts "
                        + afterNext.path("walAppendAttempts") + ", length "
                        + Files.size(prepared.segmentFile()) + " (was " + length + ")");

        return stats;
    }

    /**
     * Restart over the same volume: {@code base = V}, the refused mutation
     * left no trace, the candidate is {@code expectedCandidate} (null for
     * absent), the next sequence is {@code lastKept + 1} and a new PUT takes it.
     */
    private static void expectRecovered(WalScenarioNode node, long seed, Subcase subcase,
                                        Prepared prepared, byte[] base, byte[] expectedCandidate,
                                        long lastKept, long tornTails) throws Exception {
        String at = " " + subcase.label;
        node.startReady();

        expectValue(node, BASE, base, "after the restart" + at);
        expectValue(node, CANDIDATE, expectedCandidate, "after the restart" + at);
        expectValue(node, OTHER, null, "after the restart" + at);

        JsonNode recovered = node.storageStatus();
        node.check(recovered.path("stats").path("walTornTailsTruncated").asLong() == tornTails,
                "recovery" + at, "walTornTailsTruncated "
                        + recovered.path("stats").path("walTornTailsTruncated") + ", expected " + tornTails);
        node.check(recovered.path("nextSequence").asLong() == lastKept + 1, "recovery" + at,
                "nextSequence " + recovered.path("nextSequence") + ", expected " + (lastKept + 1));
        node.check(recovered.path("activeMemtable").path("walSegmentId").asLong()
                        > prepared.segmentId(), "recovery" + at,
                "active WAL segment " + recovered.path("activeMemtable").path("walSegmentId")
                        + " is not newer than " + prepared.segmentId());

        node.put("after", ascii("R-" + seed));
        long sequence = node.storageStatus().path("activeMemtable").path("maxSequence").asLong();
        node.check(sequence == lastKept + 1, "PUT after" + at,
                "got sequence " + sequence + ", expected " + (lastKept + 1));
    }

    // ===== Checks =====

    private static JsonNode segmentReport(JsonNode wal, long segmentId) {
        for (JsonNode segment : wal.path("segments")) {
            if (segment.path("segmentId").asLong() == segmentId) {
                return segment;
            }
        }
        throw new AssertionError(ID + " ORACLE inspect wal: no segment " + segmentId + " in " + wal);
    }

    private static void expectCounter(WalScenarioNode node, JsonNode stats, String name,
                                      long expected, String at) {
        node.check(stats.path(name).asLong(-1) == expected, "counters" + at,
                name + " " + stats.path(name) + ", expected " + expected);
    }

    /** The response must be one of the allowed (HTTP status, error code) pairs. */
    private static void expectOneOf(WalScenarioNode node, HttpResponse<String> response,
                                    Map<Integer, String> allowed, String what) throws Exception {
        String code = MAPPER.readTree(response.body()).path("code").asText();
        node.check(code.equals(allowed.get(response.statusCode())), what,
                "HTTP " + response.statusCode() + ": " + response.body() + ", allowed " + allowed);
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
