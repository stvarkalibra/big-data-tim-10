package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.failpoint.FailpointRegistry.Rule;
import rs.raf.dlsm.io.InjectedIOException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailpointRoutesTest {

    private static final String WAL_SYNC = FailpointNames.WAL_AFTER_APPEND_BEFORE_SYNC;

    private final FailpointRegistry registry = new FailpointRegistry(true);
    private final Router router = new Router();

    FailpointRoutesTest() {
        FailpointRoutes.register(router, registry);
    }

    private ApiResponse call(String method, String path, String body) throws Exception {
        Router.Found found = assertInstanceOf(Router.Found.class, router.match(method, path));
        return found.handler().handle(
                ApiRequest.of(method, path, found.pathParams(), null,
                        body == null ? null : body.getBytes(StandardCharsets.UTF_8)),
                OpContext.withTimeout("fp-1", 5000));
    }

    @FunctionalInterface
    private interface Call {
        void run() throws Exception;
    }

    private static void rejected(Call call) {
        ApiException e = assertThrows(ApiException.class, call::run);
        assertEquals("INVALID_REQUEST", e.code().name());
    }

    private static String rule(String name, String action) {
        return "{\"name\":\"" + name + "\",\"action\":\"" + action + "\"}";
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogListsEveryNameWithItsActions() throws Exception {
        ApiResponse r = call("GET", "/admin/v1/failpoints/catalog", null);

        assertEquals(200, r.status());
        List<Map<String, Object>> failpoints =
                (List<Map<String, Object>>) r.body().get("failpoints");
        assertEquals(21, failpoints.size());
        assertEquals(FailpointNames.WAL_BEFORE_APPEND, failpoints.get(0).get("name"));
        assertEquals(List.of("PAUSE", "CRASH", "IO_ERROR"), failpoints.get(0).get("actions"));
    }

    @Test
    void putArmsTheRuleFromTheSpecExample() throws Exception {
        // The request body of PI §21.1, verbatim.
        ApiResponse r = call("PUT", "/admin/v1/failpoints/tok-1", """
                {"name": "wal.after-append.before-sync", "action": "PAUSE",
                 "afterHits": 0, "maxTriggers": 1,
                 "filters": {"requestId": "put-001", "partitionId": 7},
                 "errorCode": "INJECTED_IO_ERROR", "pauseTimeoutMs": 30000}
                """);

        assertEquals(200, r.status());
        assertEquals("tok-1", r.body().get("token"));
        assertEquals("ARMED", r.body().get("state"));
        assertEquals("fp-1", r.body().get("requestId"));

        Rule armed = registry.getRulesByToken().get("tok-1");
        assertEquals(WAL_SYNC, armed.getName());
        assertEquals(Action.PAUSE, armed.getAction());
        assertEquals(30_000, armed.getPauseTimeoutMs());
        assertEquals(Map.of("requestId", "put-001", "partitionId", "7"), armed.getParameters());
    }

    @Test
    void armedRuleFiresThroughTheRegistry() throws Exception {
        call("PUT", "/admin/v1/failpoints/tok-1", rule(WAL_SYNC, "IO_ERROR"));

        assertThrows(InjectedIOException.class,
                () -> registry.hit(WAL_SYNC, OpContext.withTimeout("r", 5000)));
        assertEquals("TRIGGERED",
                call("GET", "/admin/v1/failpoints/tok-1", null).body().get("state"));
    }

    @Test
    void unknownTokenIsUnknown() throws Exception {
        ApiResponse r = call("GET", "/admin/v1/failpoints/never-armed", null);

        assertEquals(200, r.status());
        assertEquals("UNKNOWN", r.body().get("state"));
    }

    @Test
    void releaseWithoutAPauseReportsFalse() throws Exception {
        call("PUT", "/admin/v1/failpoints/tok-1", rule(WAL_SYNC, "PAUSE"));

        ApiResponse r = call("POST", "/admin/v1/failpoints/tok-1/release", null);
        assertEquals(200, r.status());
        assertEquals(false, r.body().get("released"));
        assertEquals("ARMED", r.body().get("state"));
    }

    @Test
    void deleteRemovesOneRuleAndDeleteAllRemovesEverything() throws Exception {
        call("PUT", "/admin/v1/failpoints/a", rule(WAL_SYNC, "IO_ERROR"));
        call("PUT", "/admin/v1/failpoints/b", rule(FailpointNames.WAL_BEFORE_APPEND, "IO_ERROR"));

        assertEquals(200, call("DELETE", "/admin/v1/failpoints/a", null).status());
        assertEquals("UNKNOWN", call("GET", "/admin/v1/failpoints/a", null).body().get("state"));
        assertEquals("ARMED", call("GET", "/admin/v1/failpoints/b", null).body().get("state"));

        assertEquals(200, call("DELETE", "/admin/v1/failpoints", null).status());
        assertTrue(registry.getRulesByToken().isEmpty());
    }

    @Test
    void invalidRulesAreRejectedWithoutArming() {
        String path = "/admin/v1/failpoints/tok-1";
        rejected(() -> call("PUT", path, null));
        rejected(() -> call("PUT", path, "{\"action\":\"PAUSE\"}"));
        rejected(() -> call("PUT", path, rule("no.such.failpoint", "PAUSE")));
        rejected(() -> call("PUT", path, rule(WAL_SYNC, "EXPLODE")));
        // wal.after-append.before-sync is an I/O hook: it offers IO_ERROR, not RETURN_ERROR.
        rejected(() -> call("PUT", path, rule(WAL_SYNC, "RETURN_ERROR")));
        rejected(() -> call("PUT", path,
                "{\"name\":\"" + WAL_SYNC + "\",\"action\":\"PAUSE\",\"afterHits\":-1}"));
        rejected(() -> call("PUT", path,
                "{\"name\":\"" + WAL_SYNC + "\",\"action\":\"PAUSE\",\"maxTriggers\":\"1\"}"));
        rejected(() -> call("PUT", path,
                "{\"name\":\"" + WAL_SYNC + "\",\"action\":\"PAUSE\",\"filters\":[]}"));
        rejected(() -> call("PUT", path, "{\"name\":\"" + WAL_SYNC
                + "\",\"action\":\"PAUSE\",\"filters\":{\"requestId\":\"a b\"}}"));
        rejected(() -> call("PUT", path, "{\"name\":\"" + FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE
                + "\",\"action\":\"RETURN_ERROR\",\"errorCode\":\"lower-case\"}"));

        assertTrue(registry.getRulesByToken().isEmpty());
    }

    @Test
    void invalidAndReservedTokensAreRejected() {
        rejected(() -> call("GET", "/admin/v1/failpoints/bad%20token", null));
        rejected(() -> call("GET", "/admin/v1/failpoints/" + "x".repeat(65), null));
        // GET .../catalog is the catalog, so "catalog" can never name a rule.
        rejected(() -> call("PUT", "/admin/v1/failpoints/catalog", rule(WAL_SYNC, "PAUSE")));
    }
}
