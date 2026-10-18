package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.engine.OpContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HealthRoutesTest {

    private final AtomicReference<Lifecycle> lifecycle =
            new AtomicReference<>(Lifecycle.RECOVERING);

    private ApiResponse call(String path) throws Exception {
        Router router = new Router();
        HealthRoutes.register(router, "standalone", lifecycle::get);

        Router.Found found = assertInstanceOf(Router.Found.class,
                router.match("GET", path));
        return found.handler().handle(
                ApiRequest.of("GET", path, Map.of(), null, null),
                OpContext.withTimeout("h-1", 5000));
    }

    @Test
    void lifecycleNamesMatchSharedSpec() {
        assertEquals(
                List.of("STARTING", "RECOVERING", "JOINING", "READY",
                        "DEGRADED", "FAILED", "STOPPING"),
                Arrays.stream(Lifecycle.values()).map(Enum::name).toList());
    }

    @Test
    void liveIsOkEvenWhileRecovering() throws Exception {
        ApiResponse r = call("/health/live");

        assertEquals(200, r.status());
        assertEquals("OK", r.body().get("status"));
        assertEquals("RECOVERING", r.body().get("lifecycle"));
        assertEquals("standalone", r.body().get("nodeId"));
        assertEquals("h-1", r.body().get("requestId"));
    }

    @Test
    void readyIsOkOnlyWhenReady() throws Exception {
        lifecycle.set(Lifecycle.READY);
        ApiResponse r = call("/health/ready");

        assertEquals(200, r.status());
        assertEquals("READY", r.body().get("lifecycle"));
    }

    @Test
    void notReadyStatesReturn503WithLifecycle() throws Exception {
        for (Lifecycle state : List.of(
                Lifecycle.STARTING, Lifecycle.RECOVERING, Lifecycle.FAILED,
                Lifecycle.STOPPING)) {
            lifecycle.set(state);
            ApiResponse r = call("/health/ready");

            assertEquals(503, r.status(), state.name());
            assertEquals("ERROR", r.body().get("status"));
            assertEquals("NODE_NOT_READY", r.body().get("code"));
            assertEquals(true, r.body().get("retryable"));
            assertEquals(state.name(), r.body().get("lifecycle"));
        }
    }
}
