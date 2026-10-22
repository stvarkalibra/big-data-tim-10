package rs.raf.dlsm.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.NoOpFailpoints;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The engine's five failpoints (CP1 §26.1) and the scenarios built on them. */
class EngineFailpointsTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScriptedFailpoints failpoints = new ScriptedFailpoints();

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private static OpContext ctx(String requestId) {
        return OpContext.withTimeout(requestId, 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Engine engine(long memtableMaxBytes) {
        return TestEngines.withoutFlusher(new EngineConfig(memtableMaxBytes, failpoints));
    }

    private static String get(Engine engine, String key) throws StorageException {
        GetResult r = engine.get(key, ctx("read"));
        return r.isFound() ? new String(r.value(), StandardCharsets.UTF_8) : null;
    }

    private static List<String> keys(ScanResult result) {
        List<String> out = new ArrayList<>();
        for (InternalRecord r : result.items()) {
            out.add(r.key());
        }
        return out;
    }

    @Test
    void defaultConfigHasNoFailpoints() {
        assertInstanceOf(NoOpFailpoints.class, EngineConfig.defaults().failpoints());
    }

    @Test
    void allFiveFailpointsAreHitWhereTheSpecPutsThem() throws Exception {
        Engine engine = engine(70);                 // two "kN"/"v" entries fill a generation
        engine.put("k1", bytes("v"), ctx("p1"));
        engine.put("k2", bytes("v"), ctx("p2"));
        failpoints.hits.clear();

        engine.put("k3", bytes("v"), ctx("p3"));    // rotates first, then writes
        engine.scan("a", "z", 10, ctx("s1"));

        assertEquals(List.of(
                "rotation.after-new-wal-sync.before-switch|p3",
                "rotation.after-switch.before-flush|p3",
                "wal.after-sync.before-memtable|p3",
                "memtable.after-apply.before-response|p3",
                "scan.after-snapshot.before-merge|s1"), failpoints.hits);
    }

    @Test
    void cp1Api004ConcurrentMutationsOfOneKey() throws Exception {
        // PAUSE after A's WAL force, filtered to request A; B is invoked only
        // once A is TRIGGERED (CP1 §29 API-004).
        Engine engine = engine(1 << 20);
        failpoints.pause(FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE, "CP1-API-004-A");

        Future<MutationResult> a = pool.submit(
                () -> engine.put("alpha", bytes("A"), ctx("CP1-API-004-A")));
        assertTrue(failpoints.awaitTriggered(FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE, 5000));

        Future<MutationResult> b = pool.submit(
                () -> engine.put("alpha", bytes("B"), ctx("CP1-API-004-B")));

        // A is durable but not visible yet: readers wait instead of seeing it (I2).
        StorageException blocked = assertThrows(StorageException.class,
                () -> engine.get("alpha", OpContext.withTimeout("peek", 100)));
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, blocked.code());
        assertFalse(b.isDone(), "B cannot overtake A");

        failpoints.release(FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE);

        long seqA = a.get(5, TimeUnit.SECONDS).sequence();
        long seqB = b.get(5, TimeUnit.SECONDS).sequence();
        assertTrue(seqB > seqA, "A was linearized first");
        assertEquals("B", get(engine, "alpha"));
    }

    @Test
    void cp1Api005ScanConcurrentWithMutations() throws Exception {
        Engine engine = engine(1 << 20);
        engine.put("alpha", bytes("A"), ctx("setup-1"));
        engine.put("gamma", bytes("G"), ctx("setup-2"));
        failpoints.pause(FailpointNames.SCAN_AFTER_SNAPSHOT_BEFORE_MERGE, "CP1-API-005-scan");

        Future<ScanResult> scan = pool.submit(
                () -> engine.scan("alpha", "omega", 100, ctx("CP1-API-005-scan")));
        assertTrue(failpoints.awaitTriggered(FailpointNames.SCAN_AFTER_SNAPSHOT_BEFORE_MERGE, 5000));

        Future<MutationResult> putBeta = pool.submit(
                () -> engine.put("beta", bytes("B"), ctx("CP1-API-005-put")));
        Future<MutationResult> deleteGamma = pool.submit(
                () -> engine.delete("gamma", ctx("CP1-API-005-del")));

        // Writers may block on the snapshot; the test releases the scan
        // without waiting for them (CP1 §29 API-005).
        failpoints.release(FailpointNames.SCAN_AFTER_SNAPSHOT_BEFORE_MERGE);

        assertEquals(List.of("alpha", "gamma"), keys(scan.get(5, TimeUnit.SECONDS)),
                "the paused scan sees its own snapshot");
        putBeta.get(5, TimeUnit.SECONDS);
        deleteGamma.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("alpha", "beta"),
                keys(engine.scan("alpha", "omega", 100, ctx("CP1-API-005-again"))));
    }

    @Test
    void pausedRotationKeepsTheOldPairUntilReleased() throws Exception {
        Engine engine = engine(70);
        engine.put("k1", bytes("v"), ctx("p1"));
        engine.put("k2", bytes("v"), ctx("p2"));
        failpoints.pause(FailpointNames.ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH, null);

        Future<MutationResult> k3 = pool.submit(() -> engine.put("k3", bytes("v"), ctx("p3")));
        assertTrue(failpoints.awaitTriggered(
                FailpointNames.ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH, 5000));
        assertNull(engine.currentView().immutable(), "not switched yet");

        failpoints.release(FailpointNames.ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH);
        k3.get(5, TimeUnit.SECONDS);
        assertEquals(1, engine.currentView().immutable().generation());
    }

    @Test
    void injectedErrorAfterForceFailsTheEngine() throws Exception {
        Engine engine = engine(1 << 20);
        failpoints.ioError(FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE);

        StorageException e = assertThrows(StorageException.class,
                () -> engine.put("alpha", bytes("A"), ctx("p1")));
        assertEquals(ErrorCode.INTERNAL_ERROR, e.code());
        assertEquals(EngineMode.FAILED, engine.mode());
        assertTrue(engine.lastFatalError().orElseThrow().getMessage()
                .contains("wal.after-sync.before-memtable"));
    }

    @Test
    void injectedErrorDuringRotationFailsTheEngineBeforeTheSwitch() throws Exception {
        Engine engine = engine(70);
        engine.put("k1", bytes("v"), ctx("p1"));
        engine.put("k2", bytes("v"), ctx("p2"));
        failpoints.ioError(FailpointNames.ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH);

        assertThrows(StorageException.class, () -> engine.put("k3", bytes("v"), ctx("p3")));
        assertEquals(EngineMode.FAILED, engine.mode());
        assertNull(engine.currentView().immutable(), "the pair was never switched");
    }

    // ---------- with the team's FailpointRegistry ----------

    private static FailpointRegistry.Rule ioErrorRule(String name) {
        return new FailpointRegistry.Rule("t-" + name, name,
                FailpointRegistry.Action.IO_ERROR, 0, 1, 0, Map.of());
    }

    @Test
    void registryRulesAreIgnoredOutsideTestMode() throws Exception {
        FailpointRegistry registry = new FailpointRegistry(false);
        registry.addRule(ioErrorRule(FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE));
        Engine engine = TestEngines.withoutFlusher(new EngineConfig(1 << 20, registry));

        assertEquals(1, engine.put("alpha", bytes("A"), ctx("p1")).sequence());
        assertEquals(EngineMode.SERVING, engine.mode());
    }

    @Test
    void registryRulesApplyInTestMode() {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(ioErrorRule(FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE));
        Engine engine = TestEngines.withoutFlusher(new EngineConfig(1 << 20, registry));

        assertThrows(StorageException.class, () -> engine.put("alpha", bytes("A"), ctx("p1")));
        assertEquals(EngineMode.FAILED, engine.mode());
    }

    // ---------- RETURN_ERROR and PAUSE timeouts (PI §21.1) ----------

    private Engine engineWith(FailpointRegistry.Rule rule, long memtableMaxBytes) {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule);
        return TestEngines.withoutFlusher(new EngineConfig(memtableMaxBytes, registry));
    }

    private static FailpointRegistry.Rule returnError(String name) {
        return new FailpointRegistry.Rule("t-" + name, name,
                FailpointRegistry.Action.RETURN_ERROR, 0, 1, 0, Map.of(), "TEST_FAULT");
    }

    private static StorageException injected(ThrowingCall call) {
        StorageException e = assertThrows(StorageException.class, call::run);
        assertEquals(ErrorCode.INTERNAL_ERROR, e.code());
        assertEquals("TEST_FAULT", e.category());
        return e;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void returnErrorAfterForceFailsTheEngine() {
        Engine engine = engineWith(returnError(FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE), 1 << 20);

        injected(() -> engine.put("alpha", bytes("A"), ctx("p1")));

        // Durable but not applied: only WAL replay may continue from here.
        assertEquals(EngineMode.FAILED, engine.mode());
        assertEquals("TEST_FAULT", engine.lastFatalError().orElseThrow().category());
    }

    @Test
    void returnErrorAfterApplyFailsOnlyThatRequest() throws Exception {
        Engine engine = engineWith(
                returnError(FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE), 1 << 20);

        injected(() -> engine.put("alpha", bytes("A"), ctx("p1")));

        // Durable and applied before the hook: the client outcome is unknown,
        // the mutation stays, and the engine keeps serving.
        assertEquals(EngineMode.SERVING, engine.mode());
        assertEquals("A", get(engine, "alpha"));
        assertEquals(2, engine.put("beta", bytes("B"), ctx("p2")).sequence());
    }

    @Test
    void pauseTimeoutContinuesWithDeadlineExceeded() throws Exception {
        Engine engine = engineWith(new FailpointRegistry.Rule("t",
                FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE,
                FailpointRegistry.Action.PAUSE, 0, 1, 50, Map.of()), 1 << 20);

        StorageException e = assertThrows(StorageException.class,
                () -> engine.put("alpha", bytes("A"), ctx("p1")));
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.code());
        assertEquals(FailpointRegistry.PAUSE_TIMEOUT_CATEGORY, e.category());
        assertEquals(EngineMode.SERVING, engine.mode());
        assertEquals("A", get(engine, "alpha"));
    }

    @Test
    void returnErrorBeforeSwitchKeepsTheOldPair() throws Exception {
        Engine engine = engineWith(
                returnError(FailpointNames.ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH), 70);
        engine.put("k1", bytes("v"), ctx("p1"));
        engine.put("k2", bytes("v"), ctx("p2"));

        injected(() -> engine.put("k3", bytes("v"), ctx("p3")));

        assertEquals(EngineMode.SERVING, engine.mode());
        assertNull(engine.currentView().immutable(), "the pair was never switched");
        assertNull(get(engine, "k3"), "the mutation failed before its sequence");

        // The rule fired once; the next writer rotates normally.
        engine.put("k3", bytes("v"), ctx("p4"));
        assertEquals(1, engine.currentView().immutable().generation());
    }

    @Test
    void returnErrorAfterSwitchStillRequestsTheFlush() throws Exception {
        Engine engine = engineWith(
                returnError(FailpointNames.ROTATION_AFTER_SWITCH_BEFORE_FLUSH), 70);
        AtomicInteger flushRequests = new AtomicInteger();
        engine.setFlushScheduler(new FlushScheduler() {
            @Override
            public void flushNeeded() {
                flushRequests.incrementAndGet();
            }

            @Override
            public void checkAutoCompaction() {
            }
        });
        engine.put("k1", bytes("v"), ctx("p1"));
        engine.put("k2", bytes("v"), ctx("p2"));

        injected(() -> engine.put("k3", bytes("v"), ctx("p3")));

        // The switch stands and the frozen generation still goes to flush.
        assertEquals(1, engine.currentView().immutable().generation());
        assertEquals(1, flushRequests.get());
        assertEquals(EngineMode.SERVING, engine.mode());
        assertNull(get(engine, "k3"));
        assertEquals("v", get(engine, "k1"));
    }

    @Test
    void returnErrorInScanFailsOnlyThatScan() throws Exception {
        Engine engine = engineWith(
                returnError(FailpointNames.SCAN_AFTER_SNAPSHOT_BEFORE_MERGE), 1 << 20);
        engine.put("alpha", bytes("A"), ctx("p1"));

        injected(() -> engine.scan("a", "z", 10, ctx("s1")));

        assertEquals(EngineMode.SERVING, engine.mode());
        assertEquals(List.of("alpha"), keys(engine.scan("a", "z", 10, ctx("s2"))));
    }
}
