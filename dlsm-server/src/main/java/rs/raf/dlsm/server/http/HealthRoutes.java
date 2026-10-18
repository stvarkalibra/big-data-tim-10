package rs.raf.dlsm.server.http;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.Lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Liveness and readiness on the admin port (PI §20.1):
 * <ul>
 *   <li>{@code GET /health/live} is {@code 200} whenever the process and
 *       the admin server run, including during recovery;</li>
 *   <li>{@code GET /health/ready} is {@code 200} only in {@code READY},
 *       otherwise {@code 503 NODE_NOT_READY} with the current lifecycle.</li>
 * </ul>
 */
public final class HealthRoutes {

    private HealthRoutes() {
    }

    public static void register(Router admin, String nodeId,
                                Supplier<Lifecycle> lifecycle) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(lifecycle, "lifecycle");

        admin.add("GET", "/health/live", (request, ctx) ->
                ApiResponse.ok(okBody(ctx.requestId(), nodeId, lifecycle.get())));

        admin.add("GET", "/health/ready", (request, ctx) -> {
            Lifecycle current = lifecycle.get();
            if (current == Lifecycle.READY) {
                return ApiResponse.ok(okBody(ctx.requestId(), nodeId, current));
            }

            Map<String, Object> body = ErrorEnvelope.body(
                    ErrorCode.NODE_NOT_READY, "Node is not ready",
                    ctx.requestId(), null);
            body.put("nodeId", nodeId);
            body.put("lifecycle", current.name());
            return ApiResponse.of(
                    ErrorEnvelope.httpStatus(ErrorCode.NODE_NOT_READY), body);
        });
    }

    private static Map<String, Object> okBody(String requestId, String nodeId,
                                              Lifecycle lifecycle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("requestId", requestId);
        body.put("nodeId", nodeId);
        body.put("lifecycle", lifecycle.name());
        return body;
    }
}
