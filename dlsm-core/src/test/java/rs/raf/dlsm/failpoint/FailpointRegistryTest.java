package rs.raf.dlsm.failpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.failpoint.FailpointRegistry.Rule;
import rs.raf.dlsm.failpoint.FailpointRegistry.State;
import rs.raf.dlsm.io.InjectedIOException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class FailpointRegistryTest {

    private static final String NAME = FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private static OpContext ctx(String requestId) {
        return OpContext.withTimeout(requestId, 5000);
    }

    private static Rule rule(String token, Action action, Map<String, String> parameters) {
        return new Rule(token, NAME, action, 0, 1, 10_000, parameters);
    }

    /** Bounded polling of visible state; the condition is the proof (PI §21.1). */
    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "condition never became true");
            Thread.onSpinWait();
        }
    }

    @Test
    void nothingHappensOutsideTestMode() {
        FailpointRegistry registry = new FailpointRegistry(false);
        registry.addRule(rule("t", Action.IO_ERROR, Map.of()));

        assertDoesNotThrow(() -> registry.hit(NAME, ctx("r")));
        assertEquals(State.ARMED, registry.stateOf("t"));
    }

    @Test
    void pauseIsReleasedImmediatelyAfterTriggered() throws Exception {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule("t", Action.PAUSE, Map.of()));

        Future<?> paused = pool.submit(() -> {
            registry.hit(NAME, ctx("r"));
            return null;
        });
        awaitTrue(() -> registry.stateOf("t") == State.TRIGGERED);

        // Released right after TRIGGERED becomes visible: never lost.
        assertTrue(registry.releaseRule("t"));
        paused.get(2, TimeUnit.SECONDS);
        assertEquals(State.RELEASED, registry.stateOf("t"));
    }

    @Test
    void releaseWithoutAPausedOperationDoesNothing() {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule("t", Action.PAUSE, Map.of()));

        assertFalse(registry.releaseRule("t"));
        assertFalse(registry.releaseRule("missing"));
        assertEquals(State.ARMED, registry.stateOf("t"));
    }

    @Test
    void pauseTimeoutContinuesWithADeterministicError() {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(new Rule("t", NAME, Action.PAUSE, 0, 1, 100, Map.of()));

        InjectedFailureException e = assertThrows(InjectedFailureException.class,
                () -> registry.hit(NAME, ctx("r")));
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.code());
        assertEquals(FailpointRegistry.PAUSE_TIMEOUT_CATEGORY, e.category());
        assertEquals(NAME, e.failpointName());
        assertEquals(State.EXHAUSTED, registry.stateOf("t"));

        StorageException storage = e.toStorageException();
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, storage.code());
        assertSame(e, storage.getCause());
    }

    @Test
    void ioErrorThrowsInjectedIOException() {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule("t", Action.IO_ERROR, Map.of()));

        assertThrows(InjectedIOException.class, () -> registry.hit(NAME, ctx("r")));
        assertEquals(State.TRIGGERED, registry.stateOf("t"));
    }

    @Test
    void returnErrorThrowsTheConfiguredInternalError() {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(new Rule("t", NAME, Action.RETURN_ERROR, 0, 1, 0, Map.of(),
                "CUSTOM_FAULT"));

        InjectedFailureException e = assertThrows(InjectedFailureException.class,
                () -> registry.hit(NAME, ctx("r")));
        assertEquals(ErrorCode.INTERNAL_ERROR, e.code());
        assertEquals("CUSTOM_FAULT", e.category());

        registry.addRule(rule("t2", Action.RETURN_ERROR, Map.of()));
        assertEquals(FailpointRegistry.DEFAULT_ERROR_CODE, assertThrows(
                InjectedFailureException.class, () -> registry.hit(NAME, ctx("r"))).category());
    }

    @Test
    void invalidRulesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Rule("t", NAME,
                Action.RETURN_ERROR, 0, 1, 0, Map.of(), "bad-code"));
        assertThrows(IllegalArgumentException.class, () -> new Rule("t", NAME,
                Action.PAUSE, -1, 1, 0, Map.of()));
    }

    @Test
    void requestIdFilterSkipsOtherRequestsAndBackgroundWork() throws Exception {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule("t", Action.IO_ERROR, Map.of("requestId", "put-001")));

        registry.hit(NAME, ctx("put-002"));
        registry.hit(NAME, OpContext.background());   // no request id
        registry.hit(NAME, null);
        assertEquals(State.ARMED, registry.stateOf("t"));

        assertThrows(InjectedIOException.class, () -> registry.hit(NAME, ctx("put-001")));
    }

    @Test
    void partitionFilterMatchesOnlyTheStandalonePartition() throws Exception {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule("other", Action.IO_ERROR, Map.of("partitionId", "7")));
        registry.hit(NAME, ctx("r"));
        assertEquals(State.ARMED, registry.stateOf("other"));

        registry.addRule(rule("zero", Action.IO_ERROR, Map.of("partitionId", "0")));
        assertThrows(InjectedIOException.class, () -> registry.hit(NAME, ctx("r")));
    }

    @Test
    void afterHitsAndMaxTriggersCountMatchingHits() throws Exception {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(new Rule("t", NAME, Action.IO_ERROR, 2, 1, 0, Map.of()));

        registry.hit(NAME, ctx("1"));
        registry.hit(NAME, ctx("2"));
        assertThrows(InjectedIOException.class, () -> registry.hit(NAME, ctx("3")));
        registry.hit(NAME, ctx("4"));
        assertEquals(State.EXHAUSTED, registry.stateOf("t"));
    }

    @Test
    void concurrentHitsNeverExceedMaxTriggers() throws Exception {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(new Rule("t", NAME, Action.IO_ERROR, 0, 1, 0, Map.of()));

        List<Future<Boolean>> hits = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            hits.add(pool.submit(() -> {
                try {
                    registry.hit(NAME, ctx("r"));
                    return false;
                } catch (InjectedIOException e) {
                    return true;
                }
            }));
        }
        int triggered = 0;
        for (Future<Boolean> hit : hits) {
            triggered += hit.get(5, TimeUnit.SECONDS) ? 1 : 0;
        }
        assertEquals(1, triggered);
    }

    @Test
    void clearReleasesPausedOperations() throws Exception {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule("t", Action.PAUSE, Map.of()));

        Future<?> paused = pool.submit(() -> {
            registry.hit(NAME, ctx("r"));
            return null;
        });
        awaitTrue(() -> registry.stateOf("t") == State.TRIGGERED);

        registry.clear();

        paused.get(2, TimeUnit.SECONDS);   // continued, well before the 10 s timeout
        assertEquals(State.UNKNOWN, registry.stateOf("t"));
        assertTrue(registry.getRulesByToken().isEmpty());
    }

    @Test
    void removeRuleReleasesItsPause() throws Exception {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule("t", Action.PAUSE, Map.of()));

        Future<?> paused = pool.submit(() -> {
            registry.hit(NAME, ctx("r"));
            return null;
        });
        awaitTrue(() -> registry.stateOf("t") == State.TRIGGERED);

        registry.removeRule("t");
        paused.get(2, TimeUnit.SECONDS);
        assertEquals(State.UNKNOWN, registry.stateOf("t"));
    }

    @Test
    void newRuleForTheSameNameReplacesTheOldOne() {
        FailpointRegistry registry = new FailpointRegistry(true);
        registry.addRule(rule("old", Action.IO_ERROR, Map.of()));
        registry.addRule(rule("new", Action.RETURN_ERROR, Map.of()));

        assertEquals(State.UNKNOWN, registry.stateOf("old"));
        assertThrows(InjectedFailureException.class, () -> registry.hit(NAME, ctx("r")));
    }

    @Test
    void catalogListsAll21NamesWithTheirActions() {
        assertEquals(21, FailpointCatalog.names().size());
        assertEquals(21, FailpointCatalog.asMap().size());

        long ioErrorHooks = FailpointCatalog.names().stream()
                .filter(n -> FailpointCatalog.supports(n, Action.IO_ERROR)).count();
        assertEquals(8, ioErrorHooks, "the eight IO_ERROR mappings of CP1 §26.1");

        for (String name : FailpointCatalog.names()) {
            assertTrue(FailpointCatalog.supports(name, Action.PAUSE), name);
            assertTrue(FailpointCatalog.supports(name, Action.CRASH), name);
        }
        assertFalse(FailpointCatalog.supports(NAME, Action.IO_ERROR));
        assertTrue(FailpointCatalog.supports(NAME, Action.RETURN_ERROR));
        assertTrue(FailpointCatalog.actions("no.such.failpoint").isEmpty());
    }
}
