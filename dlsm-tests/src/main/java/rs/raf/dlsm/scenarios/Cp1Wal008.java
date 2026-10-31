package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.harness.VolumeTools;
import rs.raf.dlsm.model.ReferenceModel;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CP1-WAL-008 — repeated crashes during recovery (CP1 §29.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> a volume with six acknowledged mutations that
 *       exist only in the WAL and one strictly torn final frame, left by
 *       {@code wal.after-record-prefix} with {@code CRASH}. It is cloned before
 *       any recovery: one copy is the crash-free control, the other is
 *       interrupted.</li>
 *   <li><b>Operations:</b> recover the control normally and record its logical
 *       state. On the other copy start the node four times with one bootstrap
 *       rule each, then once without rules; {@code PUT(after, R)};
 *       {@code SIGKILL}, restart; read every key again. A third copy repeats
 *       this with the crashes in another order, from the seed.</li>
 *   <li><b>Failpoints:</b> bootstrap {@code CRASH} (CP1 §26.3) on (1)
 *       {@code recovery.after-manifest-load}, (2)
 *       {@code recovery.after-wal-tail-truncate}, (3) and (4)
 *       {@code recovery.after-wal-replay.before-ready}.</li>
 *   <li><b>Client outcome:</b> the torn {@code PUT} gets no response. No
 *       interrupted start ever reports ready; each exits with the failpoint's
 *       halt code.</li>
 *   <li><b>Logical oracle:</b> the final content equals the control and the
 *       independent {@link ReferenceModel}: every acknowledged mutation holds
 *       and the torn one is absent, before and after the flush of the recovered
 *       generation.</li>
 *   <li><b>Physical / status oracle:</b> before (2) the tail is still torn at
 *       its old end {@code L}; from (2) on the segment is exactly {@code L}
 *       bytes and never shrinks or grows. Every other segment holds only its
 *       header and its ID is never reused. The final start cuts nothing,
 *       replays exactly the six records, appends nothing, takes the control's
 *       next sequence, and ends with one usable active segment and an empty
 *       {@code tmp/}. The same holds in the other order, where the tail stays
 *       torn only until the first crash past the repair.</li>
 *   <li><b>Proves:</b> I6: reading the manifest, repairing the tail and
 *       replaying can each be repeated, in any order, without side effects
 *       piling up.
 *       <b>Does not prove:</b> that directories are identical byte for byte
 *       after each attempt, which is not required (the segment IDs differ
 *       from the control), or anything about a damaged frame (WAL-003).</li>
 * </ol>
 *
 * <p>Any violated expectation is thrown, so the runner records the
 * scenario as failed.
 */
public final class Cp1Wal008 {

    public static final String ID = "CP1-WAL-008";

    private static final int PUBLIC_PORT = 18180;
    private static final int ADMIN_PORT = 18181;

    /** FORMAT.md §3.2–§3.3. */
    private static final int SEGMENT_HEADER_BYTES = 24;
    private static final int FRAME_OVERHEAD = 30;

    private static final String AFTER_MANIFEST_LOAD = "recovery.after-manifest-load";
    private static final String AFTER_TAIL_TRUNCATE = "recovery.after-wal-tail-truncate";
    private static final String BEFORE_READY = "recovery.after-wal-replay.before-ready";
    /** The crashes of CP1 §29.2, in the order given there. */
    private static final List<String> SPEC_ORDER = List.of(
            AFTER_MANIFEST_LOAD, AFTER_TAIL_TRUNCATE, BEFORE_READY, BEFORE_READY);
    private static final String TORN_KEY = "torn";
    private static final Pattern SEGMENT_NAME = Pattern.compile("wal-(\\d{20})\\.log");

    private Cp1Wal008() {
    }

    /**
     * The crashed volume: the segment, its length {@code baseLength} = L after
     * the acknowledged frames, its length with the torn prefix, and how many
     * records it holds.
     */
    private record TornVolume(long segmentId, long baseLength, long tornLength, int records) {
    }

    public static void run(long seed, Report report) throws Exception {
        ReferenceModel model = new ReferenceModel();
        Set<String> keys = new TreeSet<>();

        try (WalScenarioNode prepared = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            TornVolume volume = prepareTornVolume(prepared, seed, model, keys);
            Map<String, String> control = recoverControl(prepared, seed, volume, model, keys);
            // Each copy mutates its own model: the final recovery adds a key.
            recoverInterrupted(prepared, seed, volume, copyOf(model), new TreeSet<>(keys), control);
            recoverInSeedOrder(prepared, seed, volume, copyOf(model), new TreeSet<>(keys), control);
        }
        report.pass();
    }

    private static ReferenceModel copyOf(ReferenceModel model) {
        ReferenceModel copy = new ReferenceModel();
        for (String key : model.keySet()) {
            copy.put(key, model.get(key));
        }
        return copy;
    }

    /** Six acknowledged mutations and a strict prefix of a seventh, cut from the seed. */
    private static TornVolume prepareTornVolume(WalScenarioNode node, long seed, ReferenceModel model,
                                                Set<String> keys) throws Exception {
        node.startReady();
        byte[] binary = new byte[32];
        new Random(seed).nextBytes(binary);

        long frames = 0;
        frames += put(node, model, keys, "alpha", ascii("A1-" + seed));
        frames += put(node, model, keys, "beta", ascii("B-" + seed));
        frames += put(node, model, keys, "alpha", ascii("A2-" + seed));
        frames += put(node, model, keys, "empty", new byte[0]);
        frames += delete(node, model, keys, "beta");
        frames += put(node, model, keys, "gamma", binary);
        int records = 6;

        long segment = node.storageStatus().path("activeMemtable").path("walSegmentId").asLong();
        Path file = node.walSegment(segment);
        long baseLength = SEGMENT_HEADER_BYTES + frames;
        node.check(Files.size(file) == baseLength, "segment before the crash",
                "length " + Files.size(file) + ", expected " + baseLength);

        byte[] torn = ascii("T-" + seed);
        keys.add(TORN_KEY);
        int tornFrame = FRAME_OVERHEAD + TORN_KEY.length() + torn.length;
        int prefix = 1 + (int) Math.floorMod(seed, (long) tornFrame - 1);
        String requestId = node.nextRequestId();
        node.armFailpoint("wal008-torn", """
                {"name": "wal.after-record-prefix", "action": "CRASH",
                 "filters": {"requestId": "%s"}, "parameters": {"prefixBytes": %d}}
                """.formatted(requestId, prefix));
        Optional<HttpResponse<String>> response = node.putMayCrash(TORN_KEY, torn, requestId);
        node.check(response.isEmpty(), "PUT torn", "expected no response, got HTTP "
                + response.map(HttpResponse::statusCode).orElse(0));
        node.awaitExit();

        TornVolume volume = new TornVolume(segment, baseLength, baseLength + prefix, records);
        expectStillTorn(node, volume, "after the crash");
        return volume;
    }

    /** The crash-free control: recovers normally; returns its logical state. */
    private static Map<String, String> recoverControl(WalScenarioNode prepared, long seed, TornVolume volume,
                                                      ReferenceModel model, Set<String> keys) throws Exception {
        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            VolumeTools.cloneVolume(prepared.dataDir(), node.dataDir());
            node.startReady();

            JsonNode status = node.storageStatus();
            JsonNode stats = status.path("stats");
            expectNumber(node, stats, "walTornTailsTruncated", 1, "control recovery");
            expectNumber(node, stats, "walRecordsReplayed", volume.records(), "control recovery");
            expectNumber(node, status, "nextSequence", volume.records() + 1, "control recovery");

            FlushScenarioChecks.expectModel(node, model, keys, "in the control");
            return logicalState(node, keys);
        }
    }

    /** The copy that crashes at every recovery hook, in the order of CP1 §29.2. */
    private static void recoverInterrupted(WalScenarioNode prepared, long seed, TornVolume volume,
                                           ReferenceModel model, Set<String> keys,
                                           Map<String, String> control) throws Exception {
        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            VolumeTools.cloneVolume(prepared.dataDir(), node.dataDir());

            crashAt(node, AFTER_MANIFEST_LOAD);
            expectStillTorn(node, volume, "after " + AFTER_MANIFEST_LOAD);

            crashAt(node, AFTER_TAIL_TRUNCATE);
            expectRepaired(node, volume, "after " + AFTER_TAIL_TRUNCATE);
            List<Long> others = otherSegments(node, volume);
            node.check(others.isEmpty(), "after " + AFTER_TAIL_TRUNCATE,
                    "no new segment may exist yet, found " + others);

            crashAt(node, BEFORE_READY);
            long firstNew = expectRepairedWithNewSegment(node, volume, volume.segmentId(),
                    "after the first " + BEFORE_READY);

            crashAt(node, BEFORE_READY);
            long secondNew = expectRepairedWithNewSegment(node, volume, firstNew,
                    "after the second " + BEFORE_READY);

            expectFinalRecovery(node, seed, volume, model, keys, control, secondNew);
        }
    }

    /**
     * The same crashes in another order, from the seed: the outcome must not
     * depend on one order of crashes (CP1 §29.2). Only the first crash past
     * the manifest hook repairs the tail, and only the replay hook adds a
     * segment.
     */
    private static void recoverInSeedOrder(WalScenarioNode prepared, long seed, TornVolume volume,
                                           ReferenceModel model, Set<String> keys,
                                           Map<String, String> control) throws Exception {
        List<String> order = new ArrayList<>(SPEC_ORDER);
        Collections.shuffle(order, new Random(seed));
        if (order.equals(SPEC_ORDER)) {
            Collections.reverse(order);
        }

        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            VolumeTools.cloneVolume(prepared.dataDir(), node.dataDir());

            boolean repaired = false;
            long greatest = volume.segmentId();
            for (int i = 0; i < order.size(); i++) {
                String failpoint = order.get(i);
                String when = "after crash " + (i + 1) + " of " + order;
                crashAt(node, failpoint);

                repaired |= !failpoint.equals(AFTER_MANIFEST_LOAD);
                if (failpoint.equals(BEFORE_READY)) {
                    greatest = expectRepairedWithNewSegment(node, volume, greatest, when);
                } else if (repaired) {
                    expectRepaired(node, volume, when);
                    expectOnlyHeaders(node, volume, when);
                } else {
                    expectStillTorn(node, volume, when);
                }
            }

            expectFinalRecovery(node, seed, volume, model, keys, control, greatest);
        }
    }

    /** One start with a bootstrap {@code CRASH} on {@code failpoint}; it must never get ready. */
    private static void crashAt(WalScenarioNode node, String failpoint) throws Exception {
        node.startWithBootstrapRules("""
                [{"token": "wal008-crash", "name": "%s", "action": "CRASH",
                  "afterHits": 0, "maxTriggers": 1, "filters": {}}]
                """.formatted(failpoint));
        node.awaitCrashBeforeReady("crash at " + failpoint);
    }

    /** The start without rules: same logical state as the control, nothing repaired or appended again. */
    private static void expectFinalRecovery(WalScenarioNode node, long seed, TornVolume volume,
                                            ReferenceModel model, Set<String> keys,
                                            Map<String, String> control, long lastNewSegment)
            throws Exception {
        node.startReady();

        JsonNode status = node.storageStatus();
        JsonNode stats = status.path("stats");
        expectNumber(node, stats, "walTornTailsTruncated", 0, "final recovery");
        expectNumber(node, stats, "walRecordsReplayed", volume.records(), "final recovery");
        expectNumber(node, stats, "walAppendAttempts", 0, "final recovery");
        expectNumber(node, stats, "walBytesWritten", 0, "final recovery");
        expectNumber(node, status, "nextSequence", volume.records() + 1, "final recovery");
        long active = status.path("activeMemtable").path("walSegmentId").asLong();
        node.check(active > lastNewSegment, "final recovery",
                "active WAL segment " + active + " is not above " + lastNewSegment);

        FlushScenarioChecks.expectModel(node, model, keys, "after the final recovery");
        expectControl(node, keys, control, "after the final recovery");
        try {
            long repaired = Files.size(node.walSegment(volume.segmentId()));
            node.check(repaired == volume.baseLength(), "final recovery",
                    "repaired segment is " + repaired + " bytes, expected " + volume.baseLength());
        } catch (NoSuchFileException deletedByFlush) {
            // Allowed: the flush after READY may already have covered it.
        }

        // The flush of the recovered generation leaves one active segment and nothing temporary.
        JsonNode idle = FlushScenarioChecks.awaitIdle(node, ID);
        expectNumber(node, idle.path("manifest"), "flushedThroughSequence", volume.records(),
                "after the flush");
        JsonNode segments = idle.path("walSegments");
        node.check(segments.size() == 1 && segments.path(0).path("segmentId").asLong() == active
                        && "ACTIVE".equals(segments.path(0).path("role").asText()),
                "after the flush", "walSegments " + segments);
        node.check(segmentIds(node).equals(List.of(active)), "after the flush",
                "WAL directory holds " + segmentIds(node) + ", expected only " + active);
        try (Stream<Path> temporary = Files.list(node.dataDir().resolve("lsm").resolve("tmp"))) {
            List<String> names = temporary.map(p -> p.getFileName().toString()).toList();
            node.check(names.isEmpty(), "after the flush", "tmp/ holds " + names);
        }
        expectControl(node, keys, control, "after the flush");

        // The active segment is usable, and what it takes survives the next crash.
        put(node, model, keys, "after", ascii("R-" + seed));
        long sequence = node.storageStatus().path("activeMemtable").path("maxSequence").asLong();
        node.check(sequence == volume.records() + 1, "PUT after",
                "got sequence " + sequence + ", expected " + (volume.records() + 1));
        node.kill();
        node.startReady();
        FlushScenarioChecks.expectModel(node, model, keys, "after the last restart");
        expectNumber(node, node.storageStatus(), "nextSequence", volume.records() + 2,
                "after the last restart");
    }

    // ===== Physical checks =====

    /** The torn prefix is still there; recovery has not repaired anything. */
    private static void expectStillTorn(WalScenarioNode node, TornVolume volume, String when)
            throws Exception {
        long length = Files.size(node.walSegment(volume.segmentId()));
        node.check(length == volume.tornLength(), "segment " + when,
                "length " + length + ", expected " + volume.tornLength());

        WalScenarioNode.WalInspection inspection = node.inspectWal();
        JsonNode wal = inspection.report();
        node.check(inspection.exitCode() == 0 && "TORN_TAIL".equals(wal.path("status").asText())
                        && wal.path("tornSegmentId").asLong() == volume.segmentId()
                        && wal.path("recordCount").asInt() == volume.records(),
                "inspect wal " + when, wal.toString());
        JsonNode tornTail = segmentReport(wal, volume.segmentId()).path("tornTail");
        node.check(tornTail.path("frameOffset").asLong() == volume.baseLength(),
                "inspect wal " + when, "torn frame at " + tornTail.path("frameOffset")
                        + ", expected " + volume.baseLength());
    }

    /** The cut is exactly at L and every acknowledged record is still there. */
    private static void expectRepaired(WalScenarioNode node, TornVolume volume, String when)
            throws Exception {
        long length = Files.size(node.walSegment(volume.segmentId()));
        node.check(length == volume.baseLength(), "segment " + when,
                "length " + length + ", expected " + volume.baseLength());

        WalScenarioNode.WalInspection inspection = node.inspectWal();
        JsonNode wal = inspection.report();
        node.check(inspection.exitCode() == 0 && "VALID".equals(wal.path("status").asText())
                        && wal.path("recordCount").asInt() == volume.records()
                        && wal.path("lastSequence").asLong() == volume.records(),
                "inspect wal " + when, wal.toString());
    }

    /**
     * Repaired as before, plus a new segment above {@code previous}: a crash
     * never hands out an ID twice.
     *
     * @return the greatest segment ID on disk
     */
    private static long expectRepairedWithNewSegment(WalScenarioNode node, TornVolume volume,
                                                     long previous, String when) throws Exception {
        expectRepaired(node, volume, when);

        List<Long> others = expectOnlyHeaders(node, volume, when);
        node.check(!others.isEmpty() && others.getLast() > previous, when,
                "expected a new segment above " + previous + ", found " + others);
        return others.getLast();
    }

    /** Replay writes no copies: every segment but the repaired one holds only its header. */
    private static List<Long> expectOnlyHeaders(WalScenarioNode node, TornVolume volume, String when)
            throws Exception {
        List<Long> others = otherSegments(node, volume);
        for (long other : others) {
            long length = Files.size(node.walSegment(other));
            node.check(length == SEGMENT_HEADER_BYTES, when,
                    "segment " + other + " holds " + length + " bytes, expected only its header");
        }
        return others;
    }

    /** Segments on disk other than the repaired one, ascending. */
    private static List<Long> otherSegments(WalScenarioNode node, TornVolume volume) throws Exception {
        List<Long> others = new ArrayList<>(segmentIds(node));
        others.remove(Long.valueOf(volume.segmentId()));
        return others;
    }

    /** Segment IDs from the file names in {@code lsm/wal/}, ascending. */
    private static List<Long> segmentIds(WalScenarioNode node) throws Exception {
        try (Stream<Path> files = Files.list(node.dataDir().resolve("lsm").resolve("wal"))) {
            List<Long> ids = new ArrayList<>();
            for (Path file : files.toList()) {
                Matcher name = SEGMENT_NAME.matcher(file.getFileName().toString());
                node.check(name.matches(), "WAL directory", "unexpected file " + file.getFileName());
                ids.add(Long.parseLong(name.group(1)));
            }
            ids.sort(null);
            return ids;
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

    // ===== Logical checks =====

    private static void expectControl(WalScenarioNode node, Set<String> keys,
                                      Map<String, String> control, String when) throws Exception {
        Map<String, String> actual = logicalState(node, keys);
        node.check(actual.equals(control), "logical state " + when,
                "got " + actual + ", the control has " + control);
    }

    /** Every key's value (Base64, or absent) and one whole-range scan, as the client sees them. */
    private static Map<String, String> logicalState(WalScenarioNode node, Set<String> keys)
            throws Exception {
        Map<String, String> state = new TreeMap<>();
        for (String key : keys) {
            state.put(key, node.get(key).map(Base64.getEncoder()::encodeToString).orElse("<absent>"));
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : node.scan(FlushScenarioChecks.SCAN_START, FlushScenarioChecks.SCAN_END,
                FlushScenarioChecks.SCAN_LIMIT).path("items")) {
            items.add(item.path("key").asText() + "=" + item.path("value").asText());
        }
        state.put("<scan>", items.toString());
        return state;
    }

    private static void expectNumber(WalScenarioNode node, JsonNode parent, String name, long expected,
                                     String when) {
        node.check(parent.path(name).asLong(-1) == expected, when,
                name + " " + parent.path(name) + ", expected " + expected);
    }

    // ===== Mutations =====

    /** {@code PUT}; returns the length of its WAL frame (FORMAT.md §3.3). */
    private static int put(WalScenarioNode node, ReferenceModel model, Set<String> keys,
                           String key, byte[] value) throws Exception {
        node.put(key, value);
        model.put(key, value);
        keys.add(key);
        return FRAME_OVERHEAD + key.length() + value.length;
    }

    /** {@code DELETE}; returns the length of its WAL frame (FORMAT.md §3.3). */
    private static int delete(WalScenarioNode node, ReferenceModel model, Set<String> keys,
                              String key) throws Exception {
        node.delete(key);
        model.delete(key);
        keys.add(key);
        return FRAME_OVERHEAD + key.length();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
