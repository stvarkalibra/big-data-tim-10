package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.fixtures.CorruptionTools;
import rs.raf.dlsm.harness.Await;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.harness.RequestIds;
import rs.raf.dlsm.harness.VolumeTools;
import rs.raf.dlsm.model.CandidateSet;
import rs.raf.dlsm.model.ReferenceModel;
import rs.raf.dlsm.scenarios.Mdl002Matrix.Corruption;
import rs.raf.dlsm.scenarios.Mdl002Matrix.CrashRow;
import rs.raf.dlsm.scenarios.Mdl002Matrix.IoRow;
import rs.raf.dlsm.scenarios.Mdl002Matrix.Leftover;
import rs.raf.dlsm.scenarios.Mdl002Matrix.Manifest;
import rs.raf.dlsm.scenarios.Mdl002Matrix.StartupGuard;
import rs.raf.dlsm.scenarios.Mdl002Matrix.Target;
import rs.raf.dlsm.scenarios.Mdl002Matrix.Wal;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class Cp1Mdl002 {

    public static final String ID = "CP1-MDL-002";

    /** CP1 §29.6: 50 crash/restart cycles. */
    static final int MIN_CRASH_CYCLES = 50;
    static final int KEY_SPACE = 40;
    static final String MEMTABLE_MAX_BYTES = "2048";

    private static final int PUBLIC_PORT = 18620;
    private static final int ADMIN_PORT = 18621;
    private static final int SECOND_PUBLIC_PORT = 18622;
    private static final int SECOND_ADMIN_PORT = 18623;
    private static final int CLONE_PUBLIC_PORT = 18624;
    private static final int CLONE_ADMIN_PORT = 18625;

    private static final String SCAN_START = "mdl";
    private static final String SCAN_END = "mdm";
    private static final int SCAN_ALL_LIMIT = 1000;
    private static final int GET_SAMPLE = 8;
    private static final int FILL_VALUE_BYTES = 256;
    private static final int MAX_FILL_PUTS = 64;

    private static final int SEGMENT_HEADER_BYTES = 24;
    private static final int FRAME_OVERHEAD_BYTES = 30;
    private static final int FIRST_RECORD_OFFSET = 16 + 16;

    private static final Path REPORTS = Path.of("reports");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long seed;
    private final Random random;
    private final List<String> campaignLog = new ArrayList<>();
    private final Map<String, Integer> crashCoverage = new TreeMap<>();
    private final Set<String> ioCoverage = new TreeSet<>();
    private final Set<String> corruptionCoverage = new TreeSet<>();
    private final Set<String> guardCoverage = new TreeSet<>();
    private ReferenceModel model = new ReferenceModel();
    private Mdl002Node main;
    private int requests;
    private int cycle;
    private int crashCycles;
    private String phase = "SETUP";
    private final long startNanos = System.nanoTime();
    private long phaseStartNanos = startNanos;
    private final Map<String, Long> phaseMillis = new LinkedHashMap<>();

    private Cp1Mdl002(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public static void run(long seed, Report report) throws Exception {
        Cp1Mdl002 campaign = new Cp1Mdl002(seed);
        try {
            campaign.runAll();
        } catch (AssertionError e) {
            Path log = campaign.writeLog();
            report.fail(campaign.phase, e.getMessage(), List.of(log.toString()));
            return;
        } finally {
            campaign.close();
        }
        Path log = campaign.writeLog();
        report.pass();
    }

    private void runAll() throws Exception {
        main = new Mdl002Node("main", Files.createTempDirectory("cp1-mdl-002-" + seed + "-"),
                true, PUBLIC_PORT, ADMIN_PORT)
                .withEnvironment("MEMTABLE_MAX_BYTES", MEMTABLE_MAX_BYTES);
        main.startReady();

        enter("CRASH");
        for (CrashRow row : Mdl002Matrix.schedule(seed, MIN_CRASH_CYCLES)) {
            cycle++;
            workload();
            if (row.target() == Target.RECOVERY) {
                recoveryCycle(row);
            } else {
                recover(crash(row));
            }
        }
        enter("IO_ERROR");
        for (IoRow row : Mdl002Matrix.ioSchedule(seed)) {
            cycle++;
            workload();
            ioIteration(row);
        }
        main.kill();
        recover(Crash.quiet("restart after the IO_ERROR iterations", Disk.read(main.dataDir()), model));

        enter("CORRUPTION");
        corruptions();

        enter("STARTUP_GUARD");
        startupGuards();

        enter("COVERAGE");
        checkCoverage();
        enter("DONE");
    }

    private void enter(String next) {
        long now = System.nanoTime();
        phaseMillis.merge(phase, (now - phaseStartNanos) / 1_000_000, Long::sum);
        phase = next;
        phaseStartNanos = now;
    }

    /** A seeded mix (CP1 §29.6) between failures; */
    private void workload() throws Exception {
        int operations = 3 + random.nextInt(5);
        for (int i = 0; i < operations; i++) {
            int choice = random.nextInt(100);
            String key = key(random.nextInt(KEY_SPACE));
            if (choice < 45) {
                confirmedPut(key, value());
            } else if (choice < 65) {
                confirmedDelete(key);
            } else if (choice < 85) {
                checkGet(key);
            } else {
                checkScan();
            }
        }
        if (random.nextInt(100) < 20) {
            boolean flush = random.nextBoolean();
            String requestId = nextId();
            HttpResponse<String> response = flush ? main.flush(requestId) : main.compact(requestId);
            check(response.statusCode() == 200, (flush ? "flush " : "compaction ") + requestId,
                    "HTTP " + response.statusCode() + ": " + response.body());
        }
    }
    private void confirmedPut(String key, byte[] value) throws Exception {
        String requestId = nextId();
        HttpResponse<String> response = main.put(key, value, requestId);
        check(response.statusCode() == 200, "PUT " + key + " (" + requestId + ")",
                "HTTP " + response.statusCode() + ": " + response.body());
        model.put(key, value);
    }
    private void confirmedDelete(String key) throws Exception {
        String requestId = nextId();
        HttpResponse<String> response = main.delete(key, requestId);
        check(response.statusCode() == 200, "DELETE " + key + " (" + requestId + ")",
                "HTTP " + response.statusCode() + ": " + response.body());
        model.delete(key);
    }
    private void checkGet(String key) throws Exception {
        String requestId = nextId();
        HttpResponse<String> response = main.get(key, requestId);
        byte[] expected = model.get(key);
        if (expected == null) {
            check(response.statusCode() == 404, "GET " + key + " (" + requestId + ")",
                    "expected 404 for an absent key, got HTTP " + response.statusCode()
                            + ": " + response.body());
            return;
        }
        check(response.statusCode() == 200, "GET " + key + " (" + requestId + ")",
                "HTTP " + response.statusCode() + ": " + response.body());
        check(Arrays.equals(expected, valueOf(MAPPER.readTree(response.body()))),
                "GET " + key + " (" + requestId + ")", "value differs from the model");
    }

    private void checkScan() throws Exception {
        int from = random.nextInt(KEY_SPACE);
        int to = from + 1 + random.nextInt(KEY_SPACE - from);
        String start = key(from);
        String end = to == KEY_SPACE ? SCAN_END : key(to);
        int limit = 1 + random.nextInt(12);
        String requestId = nextId();
        String what = "SCAN [" + start + "," + end + ") limit " + limit + " (" + requestId + ")";
        HttpResponse<String> response = main.scan(start, end, limit, requestId);
        check(response.statusCode() == 200, what,
                "HTTP " + response.statusCode() + ": " + response.body());
        TreeMap<String, byte[]> observed = items(MAPPER.readTree(response.body()), what);
        check(sameState(model.scan(start, end, limit), observed), what,
                "items differ from the model: " + describe(observed));
    }

    // ===== Crash cycles =====
    private record Crash(String hook, String target, String requestId, String key, Disk before,
                         Disk after, CandidateSet candidates, int tornTails, Long newSegmentId,
                         Long activeSegmentId) {

        static Crash quiet(String what, Disk disk, ReferenceModel model) {
            return new Crash(what, "none in flight", null, null, disk, disk,
                    CandidateSet.exactly(model), 0, null, null);
        }
    }

    private Crash crash(CrashRow row) throws Exception {
        String hook = row.hook();
        JsonNode status = quiesce(hook);
        status = switch (row.target()) {
            case ROTATION -> fillUntilRotationRequired(hook);
            case FLUSH -> ensureActiveNotEmpty(status);
            case COMPACTION -> ensureTables(2, hook);
            default -> status;
        };
        long activeSegment = status.path("activeMemtable").path("walSegmentId").asLong();
        Disk before = Disk.read(main.dataDir());

        String requestId = nextId();
        String key = null;
        byte[] value = null;
        boolean mutation = row.target() == Target.MUTATION || row.target() == Target.ROTATION;
        if (mutation) {
            key = key(random.nextInt(KEY_SPACE));
            value = value();
            // A DELETE target now and then, preferably of a live key.
            if (row.target() == Target.MUTATION && random.nextInt(4) == 0) {
                List<String> live = new ArrayList<>(model.snapshot().keySet());
                key = live.isEmpty() ? key : live.get(random.nextInt(live.size()));
                value = null;
            }
        }
        // A strict prefix: every frame of this key space is at least 36 bytes.
        int prefixBytes = row.wal() == Wal.PREFIX ? 1 + random.nextInt(FRAME_OVERHEAD_BYTES - 1) : 0;
        main.arm("mdl002-c" + cycle, rule(hook, "CRASH", requestId, prefixBytes, 1));

        String target = describeTarget(row.target(), key, value, requestId);
        String k = key;
        byte[] v = value;
        Optional<HttpResponse<String>> response = Mdl002Node.mayCrash(() -> switch (row.target()) {
            case MUTATION, ROTATION -> v == null ? main.delete(k, requestId) : main.put(k, v, requestId);
            case SCAN -> main.scan(SCAN_START, SCAN_END, SCAN_ALL_LIMIT, requestId);
            case FLUSH -> main.flush(requestId);
            case COMPACTION -> main.compact(requestId);
            case RECOVERY -> throw new IllegalStateException("recovery hooks use bootstrap rules");
        });
        check(response.isEmpty(), hook + ": " + target, "answered HTTP "
                + response.map(r -> r.statusCode() + " " + r.body()).orElse("")
                + ", so the hook was never reached");
        main.awaitHalt(hook + ": " + target);
        countCrash(hook);

        Disk after = Disk.read(main.dataDir());
        checkManifest(hook, row.manifest(), before, after);
        checkLeftovers(hook, row.leftover(), before, after);
        Long newSegment = checkWal(hook, row.wal(), before, after, activeSegment, prefixBytes, key, value);

        CandidateSet candidates = switch (row.mutation()) {
            case NONE, ABSENT -> CandidateSet.exactly(model);
            case EITHER -> CandidateSet.exactly(model).absentOrApplied(key, value);
            case APPLIED -> CandidateSet.exactly(model).applied(key, value);
        };
        return new Crash(hook, target, requestId, key, before, after, candidates,
                row.wal() == Wal.PREFIX ? 1 : 0, newSegment, activeSegment);
    }

    /** A recovery hook (CP1 §26.3)*/
    private void recoveryCycle(CrashRow row) throws Exception {
        String hook = row.hook();
        boolean tail = hook.equals(FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE);
        Crash first;
        if (tail) {
            first = crash(Mdl002Matrix.crashRow(FailpointNames.WAL_AFTER_RECORD_PREFIX));
        } else {
            quiesce(hook);
            main.kill();
            first = Crash.quiet("SIGKILL before " + hook, Disk.read(main.dataDir()), model);
        }

        Disk start = Disk.read(main.dataDir());
        String rules = bootstrapRules("mdl002-boot-" + cycle, hook);
        for (int attempt = 1; attempt <= 2; attempt++) {
            String what = hook + " (bootstrap start " + attempt + ")";
            main.startWithBootstrap(rules);
            main.awaitCrashBeforeReady(what);
            countCrash(hook);

            Disk disk = Disk.read(main.dataDir());
            check(disk.generation() == start.generation() && disk.referenced().equals(start.referenced()),
                    what, "MANIFEST.json changed during recovery: " + start.generation()
                            + " -> " + disk.generation());
            // Replay never appends: old segments only shrink to a repaired tail,
            // new ones hold just their header.
            for (Map.Entry<Long, Long> segment : disk.walSizes().entrySet()) {
                Long was = start.walSizes().get(segment.getKey());
                long size = segment.getValue();
                check(was == null ? size == SEGMENT_HEADER_BYTES : size <= was, what,
                        "WAL segment " + segment.getKey() + " has " + size + " bytes, before the start "
                                + (was == null ? "it did not exist" : was));
            }
            if (tail) {
                long repaired = first.before().walSizes().get(first.activeSegmentId());
                check(disk.walSizes().get(first.activeSegmentId()) == repaired, what,
                        "the torn segment is " + disk.walSizes().get(first.activeSegmentId())
                                + " bytes, expected exactly " + repaired + " after the repair");
            }
            log("CRASH", hook, "bootstrap start " + attempt, null, "none", first.candidates(), null, disk);
        }
        // The repair of the first start is durable: the clean start cuts nothing more.
        recover(new Crash(first.hook() + " then " + hook, first.target(), first.requestId(), first.key(),
                first.before(), first.after(), first.candidates(), 0, null, first.activeSegmentId()));
    }

    private void recover(Crash crash) throws Exception {
        String what = crash.hook() + ": recovery";
        main.startReady();
        JsonNode status = main.status();
        JsonNode stats = status.path("stats");
        check(stats.path("walAppendAttempts").asLong() == 0 && stats.path("walBytesWritten").asLong() == 0,
                what, "recovery appended to the WAL: walAppendAttempts " + stats.path("walAppendAttempts")
                        + ", walBytesWritten " + stats.path("walBytesWritten"));
        check(stats.path("walTornTailsTruncated").asLong() == crash.tornTails(), what,
                "walTornTailsTruncated " + stats.path("walTornTailsTruncated")
                        + ", expected " + crash.tornTails());
        long temporary = crash.after().tmpFiles().size();
        check(stats.path("temporaryFilesRemoved").asLong() >= temporary, what,
                "temporaryFilesRemoved " + stats.path("temporaryFilesRemoved") + ", but the crash left "
                        + crash.after().tmpFiles());
        long orphans = crash.after().unreferenced().size();
        check(stats.path("orphanFilesRemoved").asLong() >= orphans, what,
                "orphanFilesRemoved " + stats.path("orphanFilesRemoved") + ", but the crash left "
                        + crash.after().unreferenced());
        if (crash.newSegmentId() != null) {
            long active = status.path("activeMemtable").path("walSegmentId").asLong();
            check(active > crash.newSegmentId(), what, "the new active segment " + active
                    + " reuses or precedes the crashed segment " + crash.newSegmentId());
        }

        main.awaitQuiet(what);
        Disk disk = Disk.read(main.dataDir());
        check(disk.tmpFiles().isEmpty(), what, "lsm/tmp/ still holds " + disk.tmpFiles());
        check(disk.sstFiles().equals(disk.referenced()), what, "tables on disk " + disk.sstFiles()
                + " differ from the manifest " + disk.referenced());

        TreeMap<String, byte[]> observed = observe(main, what, crash.key());
        Optional<ReferenceModel> chosen = crash.candidates().match(observed);
        check(chosen.isPresent(), what, "the state " + CandidateSet.digest(observed)
                + " matches no candidate " + crash.candidates().digests() + ": " + describe(observed));
        model = chosen.get();
        log(phase, crash.hook(), crash.target(), crash.requestId(), "none", crash.candidates(),
                CandidateSet.digest(observed), disk);
    }

    // ===== IO_ERROR iterations =====

    /** One mapping of CP1 §26.1; not counted among the crash cycles (CP1 §29.6). */
    private void ioIteration(IoRow row) throws Exception {
        String hook = row.hook();
        JsonNode status = quiesce(hook);
        if (row.effect() != Mdl002Matrix.IoEffect.WRITE_PATH_FAILED) {
            status = ensureActiveNotEmpty(status);
        }
        long activeSegment = status.path("activeMemtable").path("walSegmentId").asLong();
        Disk before = Disk.read(main.dataDir());
        String requestId = nextId();
        String token = "mdl002-io-" + cycle;

        switch (row.effect()) {
            case WRITE_PATH_FAILED -> {
                String key = key(random.nextInt(KEY_SPACE));
                byte[] value = value();
                int prefixBytes = row.wal() == Wal.PREFIX
                        ? 1 + random.nextInt(FRAME_OVERHEAD_BYTES - 1) : 0;
                main.arm(token, rule(hook, "IO_ERROR", requestId, prefixBytes, 1));
                expectStorageIo(main.put(key, value, requestId), requestId, hook + ": PUT " + key);
                expectEngineFailed(hook, "STORAGE_IO");
                Disk after = Disk.read(main.dataDir());
                checkWal(hook, row.wal(), before, after, activeSegment, prefixBytes, key, value);
                main.kill();
                CandidateSet candidates = row.mutation() == Mdl002Matrix.Mutation.EITHER
                        ? CandidateSet.exactly(model).absentOrApplied(key, value)
                        : CandidateSet.exactly(model);
                recover(new Crash(hook + " IO_ERROR", "PUT " + key, requestId, key, before,
                        Disk.read(main.dataDir()), candidates, row.wal() == Wal.PREFIX ? 1 : 0,
                        null, activeSegment));
            }
            case MAINTENANCE_FAILED -> {
                long epoch = main.resetStats();
                // No filter and no trigger limit: the automatic flush the rotation
                // queued fails too and cannot publish before the checks (CP1-FLS-005).
                main.arm(token, rule(hook, "IO_ERROR", null, 0, 0));
                expectStorageIo(main.flush(requestId), requestId, hook + ": flush");
                JsonNode failed = awaitFlushAttempts(2, hook);
                check(failed.path("stats").path("resetEpoch").asLong() == epoch, hook,
                        "the counters were reset by someone else");
                check("READY".equals(failed.path("lifecycle").asText())
                                && failed.path("lastFatalError").isNull(), hook,
                        "a maintenance failure must not fail the engine: " + failed.path("lifecycle")
                                + " " + failed.path("lastFatalError"));
                check(failed.path("stats").path("flushPublications").asLong() == 0, hook,
                        "a flush was published although every attempt failed");
                checkManifest(hook, Manifest.OLD, before, Disk.read(main.dataDir()));
                checkState(observe(main, hook + " after the failure", null), hook + " after the failure");

                main.disarm(token);
                String retryId = nextId();
                HttpResponse<String> retry = main.flush(retryId);
                check(retry.statusCode() == 200, hook + ": retry " + retryId,
                        "HTTP " + retry.statusCode() + ": " + retry.body());
                JsonNode settled = main.awaitQuiet(hook + " retry");
                check(settled.path("manifest").path("generation").asLong() > before.generation(), hook,
                        "the retry did not publish: generation still " + before.generation());
                checkState(observe(main, hook + " after the retry", null), hook + " after the retry");
                log(phase, hook, "flush", requestId, "500 STORAGE_IO", CandidateSet.exactly(model),
                        null, Disk.read(main.dataDir()));
            }
            case MAINTENANCE_FAILED_ENGINE_FAILED -> {
                main.arm(token, rule(hook, "IO_ERROR", requestId, 0, 1));
                expectStorageIo(main.flush(requestId), requestId, hook + ": flush");
                expectEngineFailed(hook, null);
                main.kill();
                Disk after = Disk.read(main.dataDir());
                checkManifest(hook, Manifest.OLD_OR_NEW, before, after);
                recover(new Crash(hook + " IO_ERROR", "flush", requestId, null, before, after,
                        CandidateSet.exactly(model), 0, null, activeSegment));
            }
        }
        ioCoverage.add(hook);
    }

    private void expectStorageIo(HttpResponse<String> response, String requestId, String what)
            throws IOException {
        check(response.statusCode() == 500, what + " (" + requestId + ")",
                "HTTP " + response.statusCode() + ", expected 500: " + response.body());
        JsonNode error = MAPPER.readTree(response.body());
        check("STORAGE_IO".equals(error.path("code").asText())
                        && requestId.equals(error.path("requestId").asText()), what,
                "unexpected error envelope " + error);
    }

    /** refused (CP1 §22).*/
    private void expectEngineFailed(String hook, String code) throws Exception {
        JsonNode ready = main.awaitLifecycle("FAILED", hook);
        String actual = ready.path("lastFatalError").path("code").asText();
        check(code == null ? !actual.isEmpty() : code.equals(actual), hook,
                "lastFatalError " + ready.path("lastFatalError") + ", expected "
                        + (code == null ? "an explicit category" : code));
        String requestId = nextId();
        HttpResponse<String> read = main.get(key(0), requestId);
        check(read.statusCode() == 503
                        && "NODE_NOT_READY".equals(MAPPER.readTree(read.body()).path("code").asText()),
                hook + ": GET while FAILED (" + requestId + ")",
                "HTTP " + read.statusCode() + ": " + read.body());
    }

    private JsonNode awaitFlushAttempts(long attempts, String what) {
        JsonNode[] last = new JsonNode[1];
        Await.until(() -> {
            try {
                last[0] = main.status();
            } catch (Exception e) {
                throw new AssertionError(ID + ": " + what + ": storage status failed", e);
            }
            return last[0].path("stats").path("flushAttempts").asLong() >= attempts
                    && "IDLE".equals(last[0].path("maintenance").path("phase").asText());
        }, Duration.ofSeconds(20), ID + ": " + what + ": " + attempts + " failed flush attempts");
        return last[0];
    }

    // ===== Damaged clones =====

    private void corruptions() throws Exception {
        JsonNode status = quiesce("corruption source");
        if (status.path("sstables").isEmpty()) {
            ensureActiveNotEmpty(status);
            expectFlush("corruption source table");
        }
        // Frames the manifest does not cover, so recovery needs the damaged one.
        quiesce("corruption source");
        for (int i = 0; i < 3; i++) {
            confirmedPut(key(random.nextInt(KEY_SPACE)), value());
        }
        status = main.awaitQuiet("corruption source");
        long activeSegment = status.path("activeMemtable").path("walSegmentId").asLong();
        String table = status.path("sstables").path(0).path("file").asText();
        check(!table.isEmpty(), "corruption source", "no referenced table to damage");
        main.kill();

        for (Corruption corruption : Corruption.values()) {
            Path clone = Files.createTempDirectory("cp1-mdl-002-clone-");
            String what = "corruption " + corruption;
            try (Mdl002Node node = new Mdl002Node(what, clone, true, CLONE_PUBLIC_PORT, CLONE_ADMIN_PORT)) {
                VolumeTools.cloneVolume(main.dataDir(), clone);
                damage(corruption, clone, activeSegment, table);
                node.start();
                JsonNode ready = node.awaitStartupFailure(what);
                String code = ready.path("lastFatalError").path("code").asText();
                check(corruption.code == null ? !code.isEmpty() : corruption.code.equals(code), what,
                        "lastFatalError " + ready.path("lastFatalError") + ", expected "
                                + (corruption.code == null ? "an explicit category" : corruption.code));
                expectNotServed(node, what);
                logOutcome(corruption.name(), code);
            }
            corruptionCoverage.add(corruption.name());
        }
        // The source snapshot still starts healthy with the exact model.
        recover(Crash.quiet("healthy source after the damaged clones", Disk.read(main.dataDir()), model));
    }

    private static void damage(Corruption corruption, Path volume, long activeSegment, String table)
            throws IOException {
        Path lsm = volume.resolve("lsm");
        Path segment = lsm.resolve("wal").resolve(String.format("wal-%020d.log", activeSegment));
        switch (corruption) {
            case WAL_RECORD -> {
                // The stored CRC of the first frame: the frame stays complete, so
                // this is corruption, not a torn tail (CP1 §16.4).
                int frame = readInt(segment, SEGMENT_HEADER_BYTES);
                long crcByte = SEGMENT_HEADER_BYTES + (long) frame - 1;
                check(Files.size(segment) > crcByte, "corruption WAL_RECORD",
                        "the active segment holds no complete frame");
                flip(segment, crcByte);
            }
            case WAL_SEGMENT_HEADER -> flip(segment, 0);
            case MANIFEST -> {
                Path manifest = lsm.resolve("MANIFEST.json");
                CorruptionTools.truncateFile(manifest, Files.size(manifest) / 2);
            }
            case MISSING_TABLE -> Files.delete(lsm.resolve("sst").resolve(table));
            case TABLE_REGION -> flip(lsm.resolve("sst").resolve(table), FIRST_RECORD_OFFSET + 9);
        }
    }

    // ===== Startup protection =====

    /** CP1 §29.6: a second process must fail without disturbing the owner*/
    private void startupGuards() throws Exception {
        main.awaitQuiet("startup guards");
        Map<String, Long> filesBefore = listing(main.dataDir());
        String what = "guard " + StartupGuard.SECOND_PROCESS;
        try (Mdl002Node second = new Mdl002Node(what, main.dataDir(), false,
                SECOND_PUBLIC_PORT, SECOND_ADMIN_PORT)) {
            second.start();
            JsonNode ready = second.awaitStartupFailure(what);
            expectCode(ready, StartupGuard.SECOND_PROCESS.code, what);
            expectNotServed(second, what);
        }
        check(main.readiness().statusCode() == 200, what, "the owner is no longer ready");
        checkState(observe(main, what, null), what + ": the owner");
        check(listing(main.dataDir()).equals(filesBefore), what, "the owner's files changed");
        guardCoverage.add(StartupGuard.SECOND_PROCESS.name());
        logOutcome(StartupGuard.SECOND_PROCESS.name(), StartupGuard.SECOND_PROCESS.code);

        main.kill();
        for (StartupGuard guard : List.of(StartupGuard.MISSING_NODE_JSON, StartupGuard.OTHER_NODE_ID,
                StartupGuard.MISSING_MANIFEST)) {
            Path clone = Files.createTempDirectory("cp1-mdl-002-clone-");
            what = "guard " + guard;
            try (Mdl002Node node = new Mdl002Node(what, clone, true, CLONE_PUBLIC_PORT, CLONE_ADMIN_PORT)) {
                VolumeTools.cloneVolume(main.dataDir(), clone);
                Path nodeFile = clone.resolve("node.json");
                Path manifest = clone.resolve("lsm").resolve("MANIFEST.json");
                byte[] identity = Files.readAllBytes(nodeFile);
                switch (guard) {
                    case MISSING_NODE_JSON -> Files.delete(nodeFile);
                    case OTHER_NODE_ID -> node.withEnvironment("NODE_ID", "mdl002-other");
                    case MISSING_MANIFEST -> Files.delete(manifest);
                    default -> throw new IllegalStateException(guard.name());
                }
                node.start();
                JsonNode ready = node.awaitStartupFailure(what);
                expectCode(ready, guard.code, what);
                expectNotServed(node, what);
                // Refused before anything is created: no new identity, no empty manifest.
                switch (guard) {
                    case MISSING_NODE_JSON -> check(!Files.exists(nodeFile), what, "node.json was re-created");
                    case OTHER_NODE_ID -> check(Arrays.equals(identity, Files.readAllBytes(nodeFile)), what,
                            "node.json was rewritten");
                    case MISSING_MANIFEST -> check(!Files.exists(manifest), what,
                            "MANIFEST.json was re-created: the volume was initialized as empty");
                    default -> throw new IllegalStateException(guard.name());
                }
                logOutcome(guard.name(), guard.code);
            }
            guardCoverage.add(guard.name());
        }
    }

    private static void expectCode(JsonNode ready, String code, String what) {
        check(code.equals(ready.path("lastFatalError").path("code").asText()), what,
                "lastFatalError " + ready.path("lastFatalError") + ", expected " + code);
    }

    /** A node that refused its volume serves no data, not even a smaller model. */
    private void expectNotServed(Mdl002Node node, String what) throws Exception {
        String requestId = nextId();
        HttpResponse<String> response;
        try {
            response = node.get(key(0), requestId);
        } catch (IOException publicPortNotOpen) {
            // A node that never got an engine may not open the public port at all.
            return;
        }
        check(response.statusCode() == 503
                        && "NODE_NOT_READY".equals(MAPPER.readTree(response.body()).path("code").asText()),
                what + ": GET (" + requestId + ")", "HTTP " + response.statusCode() + ": " + response.body());
    }

    // ===== Coverage =====

    /** A missing hook, mapping or subcase is a failure, never a skip (CP1 §29.6). */
    private void checkCoverage() {
        for (CrashRow row : Mdl002Matrix.CRASH) {
            int needed = row.target() == Target.RECOVERY ? 2 : 1;
            check(crashCoverage.getOrDefault(row.hook(), 0) >= needed, "coverage",
                    row.hook() + " crashed " + crashCoverage.getOrDefault(row.hook(), 0)
                            + " times, needed " + needed);
        }
        check(crashCycles >= MIN_CRASH_CYCLES, "coverage",
                crashCycles + " crash cycles, needed " + MIN_CRASH_CYCLES);
        for (IoRow row : Mdl002Matrix.IO_ERROR) {
            check(ioCoverage.contains(row.hook()), "coverage", "IO_ERROR on " + row.hook() + " never ran");
        }
        for (Corruption corruption : Corruption.values()) {
            check(corruptionCoverage.contains(corruption.name()), "coverage", corruption + " never ran");
        }
        for (StartupGuard guard : StartupGuard.values()) {
            check(guardCoverage.contains(guard.name()), "coverage", guard + " never ran");
        }
    }

    private String coverageSummary() {
        return "CRASH " + crashCoverage.size() + "/" + Mdl002Matrix.CRASH.size() + " hooks in "
                + crashCycles + " cycles, IO_ERROR " + ioCoverage.size() + "/" + Mdl002Matrix.IO_ERROR.size()
                + ", corruption " + corruptionCoverage.size() + "/" + Corruption.values().length
                + ", startup guards " + guardCoverage.size() + "/" + StartupGuard.values().length
                + "; ms per phase " + phaseMillis;
    }

    // ===== Preparing a reachable state =====

    private JsonNode quiesce(String what) throws Exception {
        for (int round = 0; round < 6; round++) {
            JsonNode status = main.awaitQuiet(what);
            if (status.path("sstables").size() >= 4) {
                expectOk(main.compact(nextId()), what + ": settling compaction");
            } else if (status.path("activeMemtable").path("rotationRequired").asBoolean()) {
                expectOk(main.flush(nextId()), what + ": settling flush");
            } else {
                return status;
            }
        }
        throw new AssertionError(ID + " ORACLE " + what + ": maintenance never settled");
    }

    /** Confirmed {@code PUT}s until the next mutation must rotate (CP1 §10). */
    private JsonNode fillUntilRotationRequired(String what) throws Exception {
        for (int i = 0; i < MAX_FILL_PUTS; i++) {
            byte[] fill = new byte[FILL_VALUE_BYTES];
            random.nextBytes(fill);
            confirmedPut(key(random.nextInt(KEY_SPACE)), fill);
            JsonNode status = main.status();
            if (status.path("activeMemtable").path("rotationRequired").asBoolean()) {
                JsonNode frozen = status.path("immutableMemtable");
                check(frozen.isMissingNode() || frozen.isNull(), what,
                        "a frozen generation would make the target wait instead of rotating");
                return status;
            }
        }
        throw new AssertionError(ID + " ORACLE " + what + ": the active generation never filled");
    }

    private JsonNode ensureActiveNotEmpty(JsonNode status) throws Exception {
        if (status.path("activeMemtable").path("entryCount").asLong() > 0) {
            return status;
        }
        confirmedPut(key(random.nextInt(KEY_SPACE)), value());
        return main.status();
    }

    /** At least {@code min} and fewer than four published tables, nothing running. */
    private JsonNode ensureTables(int min, String what) throws Exception {
        for (int round = 0; round < 8; round++) {
            JsonNode status = quiesce(what);
            if (status.path("sstables").size() >= min) {
                return status;
            }
            ensureActiveNotEmpty(status);
            expectFlush(what + ": table for the compaction");
        }
        throw new AssertionError(ID + " ORACLE " + what + ": could not publish " + min + " tables");
    }

    private void expectFlush(String what) throws Exception {
        String requestId = nextId();
        HttpResponse<String> response = main.flush(requestId);
        expectOk(response, what + " (" + requestId + ")");
        check("PUBLISHED".equals(MAPPER.readTree(response.body()).path("result").asText()), what,
                "flush answered " + response.body() + ", expected PUBLISHED");
    }

    private static void expectOk(HttpResponse<String> response, String what) {
        check(response.statusCode() == 200, what, "HTTP " + response.statusCode() + ": " + response.body());
    }

    // ===== Physical oracle =====

    /** What is on disk; read only while nothing runs or after the process stopped. */
    private record Disk(long generation, Set<String> referenced, Set<String> sstFiles,
                        Set<String> tmpFiles, Map<Long, Long> walSizes) {

        static Disk read(Path dataDir) throws IOException {
            Path lsm = dataDir.resolve("lsm");
            JsonNode manifest = MAPPER.readTree(Files.readAllBytes(lsm.resolve("MANIFEST.json")));
            Set<String> referenced = new TreeSet<>();
            manifest.path("sstables").forEach(table -> referenced.add(table.path("file").asText()));
            Map<Long, Long> walSizes = new TreeMap<>();
            for (String name : names(lsm.resolve("wal"))) {
                if (name.matches("wal-\\d{20}\\.log")) {
                    walSizes.put(Long.parseLong(name.substring(4, 24)),
                            Files.size(lsm.resolve("wal").resolve(name)));
                }
            }
            return new Disk(manifest.path("generation").asLong(), referenced,
                    names(lsm.resolve("sst")), names(lsm.resolve("tmp")), walSizes);
        }

        Set<String> unreferenced() {
            Set<String> orphans = new TreeSet<>(sstFiles);
            orphans.removeAll(referenced);
            return orphans;
        }

        private static Set<String> names(Path dir) throws IOException {
            Set<String> names = new TreeSet<>();
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.forEach(file -> names.add(file.getFileName().toString()));
                }
            }
            return names;
        }
    }

    private static void checkManifest(String hook, Manifest expected, Disk before, Disk after) {
        boolean old = after.generation() == before.generation() && after.referenced().equals(before.referenced());
        boolean next = after.generation() == before.generation() + 1;
        boolean ok = switch (expected) {
            case OLD -> old;
            case NEW -> next;
            case OLD_OR_NEW -> old || next;
        };
        check(ok, hook, "MANIFEST.json on disk has generation " + after.generation() + " " + after.referenced()
                + ", expected " + expected + " against generation " + before.generation() + " "
                + before.referenced());
    }

    private static void checkLeftovers(String hook, Leftover expected, Disk before, Disk after) {
        Set<String> orphans = after.unreferenced();
        boolean tempTable = after.tmpFiles().stream().anyMatch(name -> name.endsWith(".sst.tmp"));
        boolean tempManifest = after.tmpFiles().stream()
                .anyMatch(name -> name.startsWith("MANIFEST-") && name.endsWith(".tmp"));
        boolean ok = switch (expected) {
            case NONE -> after.tmpFiles().isEmpty() && orphans.isEmpty();
            case TEMP_TABLE -> tempTable && orphans.isEmpty();
            case ORPHAN_TABLE -> !orphans.isEmpty();
            case TEMP_MANIFEST -> tempManifest && !orphans.isEmpty();
            case RETIRED_INPUTS -> orphans.size() >= 2 && before.referenced().containsAll(orphans);
            case ANY -> true;
        };
        check(ok, hook, "after the crash lsm/tmp/ holds " + after.tmpFiles() + " and unreferenced tables are "
                + orphans + ", expected " + expected);
    }

    /** @return the header-only segment the crash created, for {@link Wal#NEW_EMPTY_SEGMENT} */
    private static Long checkWal(String hook, Wal expected, Disk before, Disk after, long active,
                                 int prefixBytes, String key, byte[] value) {
        if (expected == Wal.NOT_CHECKED) {
            return null;
        }
        Long was = before.walSizes().get(active);
        Long now = after.walSizes().get(active);
        check(was != null && now != null, hook, "the active segment " + active + " is missing");
        long frame = key == null ? 0
                : FRAME_OVERHEAD_BYTES + key.length() + (value == null ? 0 : value.length);
        long expectedSize = switch (expected) {
            case UNCHANGED, NEW_EMPTY_SEGMENT -> was;
            case PREFIX -> was + prefixBytes;
            case FULL_FRAME -> was + frame;
            case NOT_CHECKED -> now;
        };
        check(now == expectedSize, hook, "the active segment " + active + " has " + now + " bytes, was "
                + was + ", expected " + expectedSize + " (" + expected + ")");
        if (expected != Wal.NEW_EMPTY_SEGMENT) {
            return null;
        }
        long newest = after.walSizes().keySet().stream().mapToLong(Long::longValue).max().orElse(active);
        check(newest > active && after.walSizes().get(newest) == SEGMENT_HEADER_BYTES, hook,
                "no new header-only segment after " + active + ": " + after.walSizes());
        return newest;
    }

    // ===== Observation =====

    private TreeMap<String, byte[]> observe(Mdl002Node node, String what, String targetKey)
            throws Exception {
        String scanId = nextId();
        HttpResponse<String> response = node.scan(SCAN_START, SCAN_END, SCAN_ALL_LIMIT, scanId);
        check(response.statusCode() == 200, what + ": full SCAN (" + scanId + ")",
                "HTTP " + response.statusCode() + ": " + response.body());
        TreeMap<String, byte[]> observed = items(MAPPER.readTree(response.body()), what);
        Set<String> keys = new TreeSet<>();
        if (targetKey != null) {
            keys.add(targetKey);
        }
        while (keys.size() < GET_SAMPLE) {
            keys.add(key(random.nextInt(KEY_SPACE)));
        }
        for (String key : keys) {
            String requestId = nextId();
            HttpResponse<String> get = node.get(key, requestId);
            byte[] scanned = observed.get(key);
            if (scanned == null) {
                check(get.statusCode() == 404, what + ": GET " + key + " (" + requestId + ")",
                        "the SCAN has no " + key + " but GET answered HTTP " + get.statusCode());
            } else {
                check(get.statusCode() == 200 && Arrays.equals(scanned, valueOf(MAPPER.readTree(get.body()))),
                        what + ": GET " + key + " (" + requestId + ")",
                        "GET and SCAN disagree: HTTP " + get.statusCode() + " " + get.body());
            }
        }
        return observed;
    }

    private void checkState(TreeMap<String, byte[]> observed, String what) {
        check(sameState(model.snapshot(), observed), what, "the state " + CandidateSet.digest(observed)
                + " differs from the model " + CandidateSet.digest(model.snapshot()));
    }

    /** The items of a scan response in order; keys must be strictly ascending (CP1 §12). */
    private static TreeMap<String, byte[]> items(JsonNode body, String what) {
        TreeMap<String, byte[]> items = new TreeMap<>();
        String previous = null;
        for (JsonNode item : body.path("items")) {
            String key = item.path("key").asText();
            check(previous == null || previous.compareTo(key) < 0, what,
                    "keys are not strictly ascending: " + previous + " then " + key);
            items.put(key, valueOf(item));
            previous = key;
        }
        check(body.path("count").asInt(-1) == items.size(), what,
                "count " + body.path("count") + " but " + items.size() + " items");
        return items;
    }

    private static byte[] valueOf(JsonNode node) {
        return Base64.getDecoder().decode(node.path("value").asText());
    }

    private static boolean sameState(Map<String, byte[]> expected, Map<String, byte[]> observed) {
        if (!expected.keySet().equals(observed.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            if (!Arrays.equals(entry.getValue(), observed.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Map<String, byte[]> state) {
        Map<String, Integer> lengths = new TreeMap<>();
        state.forEach((key, value) -> lengths.put(key, value.length));
        return "keys with value lengths " + lengths;
    }

    // ===== Campaign report (CP1 §29.6) =====

    private void countCrash(String hook) {
        crashCycles++;
        crashCoverage.merge(hook, 1, Integer::sum);
    }

    private void log(String kind, String hook, String target, String requestId, String response,
                     CandidateSet candidates, String observed, Disk disk) {
        ObjectNode line = MAPPER.createObjectNode();
        line.put("seed", seed);
        line.put("cycle", cycle);
        line.put("elapsedMs", (System.nanoTime() - startNanos) / 1_000_000);
        line.put("phase", kind);
        line.put("hook", hook);
        line.put("target", target);
        line.put("requestId", requestId);
        line.put("response", response);
        candidates.digests().forEach(line.putArray("candidates")::add);
        line.put("observed", observed);
        line.put("manifestGeneration", disk.generation());
        disk.referenced().forEach(line.putArray("sstables")::add);
        disk.walSizes().keySet().forEach(line.putArray("walSegments")::add);
        campaignLog.add(line.toString());
    }

    private void logOutcome(String subcase, String code) {
        ObjectNode line = MAPPER.createObjectNode();
        line.put("seed", seed);
        line.put("phase", phase);
        line.put("subcase", subcase);
        line.put("lifecycle", "FAILED");
        line.put("code", code);
        campaignLog.add(line.toString());
    }

    /** {@code reports/cp1-mdl-002-<seed>.jsonl}: every cycle and the final coverage table. */
    private Path writeLog() throws IOException {
        ObjectNode coverage = MAPPER.createObjectNode();
        coverage.put("seed", seed);
        coverage.put("crashCycles", crashCycles);
        ObjectNode durations = coverage.putObject("phaseMillis");
        phaseMillis.forEach(durations::put);
        ObjectNode crash = coverage.putObject("CRASH");
        crashCoverage.forEach(crash::put);
        ioCoverage.forEach(coverage.putArray("IO_ERROR")::add);
        corruptionCoverage.forEach(coverage.putArray("corruption")::add);
        guardCoverage.forEach(coverage.putArray("startupGuards")::add);
        List<String> lines = new ArrayList<>(campaignLog);
        lines.add(coverage.toString());
        Files.createDirectories(REPORTS);
        Path file = REPORTS.resolve("cp1-mdl-002-" + seed + ".jsonl");
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    private void close() throws IOException {
        if (main != null) {
            main.close();
        }
    }

    // ===== Helpers =====

    static String key(int index) {
        return String.format("mdl-%02d", index);
    }

    /** 0 to 96 random bytes; an empty value now and then, which must stay present. */
    private byte[] value() {
        byte[] value = new byte[random.nextInt(10) == 0 ? 0 : 1 + random.nextInt(96)];
        random.nextBytes(value);
        return value;
    }

    private String nextId() {
        return RequestIds.of(ID, seed, ++requests);
    }

    /** A rule of the failpoint API (PI §21.1); {@code maxTriggers} 0 means no limit. */
    static String rule(String hook, String action, String requestId, int prefixBytes, int maxTriggers) {
        ObjectNode rule = MAPPER.createObjectNode();
        rule.put("name", hook);
        rule.put("action", action);
        rule.put("afterHits", 0);
        rule.put("maxTriggers", maxTriggers);
        if (requestId != null) {
            rule.putObject("filters").put("requestId", requestId);
        }
        if (prefixBytes > 0) {
            rule.putObject("parameters").put("prefixBytes", prefixBytes);
        }
        return rule.toString();
    }

    /** One {@code CRASH} rule as a bootstrap array, with its own token (CP1 §26.3). */
    static String bootstrapRules(String token, String hook) {
        ObjectNode rule = MAPPER.createObjectNode();
        rule.put("token", token);
        rule.put("name", hook);
        rule.put("action", "CRASH");
        rule.put("afterHits", 0);
        rule.put("maxTriggers", 1);
        return MAPPER.createArrayNode().add(rule).toString();
    }

    private static String describeTarget(Target target, String key, byte[] value, String requestId) {
        return switch (target) {
            case MUTATION, ROTATION -> (value == null ? "DELETE " : "PUT ") + key;
            case SCAN -> "SCAN [" + SCAN_START + "," + SCAN_END + ")";
            case FLUSH -> "manual flush";
            case COMPACTION -> "manual compaction";
            case RECOVERY -> "bootstrap start";
        } + " (" + requestId + ")";
    }

    private static int readInt(Path file, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(4);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, offset + buffer.position()) < 0) {
                    throw new IOException(file + " ends before offset " + (offset + 4));
                }
            }
            return buffer.flip().getInt();
        }
    }

    private static void flip(Path file, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            if (channel.read(one, offset) != 1) {
                throw new IOException(file + " has no byte at offset " + offset);
            }
            VolumeTools.mutateSingleByte(file, offset, (byte) (one.get(0) ^ 0x01));
        }
    }

    /** Relative path to size of every file, to prove the owner's files did not change. */
    private static Map<String, Long> listing(Path root) throws IOException {
        Map<String, Long> files = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.size(file));
            }
        }
        return files;
    }

    static void check(boolean condition, String what, String problem) {
        if (!condition) {
            throw new AssertionError(ID + " ORACLE " + what + ": " + problem);
        }
    }
}