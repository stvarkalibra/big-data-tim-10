package rs.raf.dlsm.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import rs.raf.dlsm.failpoint.FailpointCatalog;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.failpoint.FailpointRegistry.Rule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FailpointRoutes {

    private static final String CATALOG = "catalog";

    private FailpointRoutes() {
    }

    public static void register(Router admin, FailpointRegistry registry) {
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(registry, "registry");

        // Registered before "{token}", so the literal route wins.
        admin.add("GET", "/admin/v1/failpoints/catalog", (request, ctx) -> {
            List<Map<String, Object>> failpoints = new ArrayList<>();
            for (Map.Entry<String, List<Action>> entry : FailpointCatalog.asMap().entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getKey());
                item.put("actions", entry.getValue().stream().map(Enum::name).toList());
                failpoints.add(item);
            }
            Map<String, Object> body = ok(ctx.requestId());
            body.put("failpoints", failpoints);
            return ApiResponse.ok(body);
        });

        admin.add("PUT", "/admin/v1/failpoints/{token}", (request, ctx) -> {
            String token = token(request);
            Rule rule = parseRule(token, Json.readObject(request.body()));
            registry.addRule(rule);
            return ApiResponse.ok(state(ctx.requestId(), token, rule.getName(),
                    registry.stateOf(token)));
        });

        admin.add("GET", "/admin/v1/failpoints/{token}", (request, ctx) -> {
            String token = token(request);
            Rule rule = registry.getRulesByToken().get(token);
            return ApiResponse.ok(state(ctx.requestId(), token,
                    rule == null ? null : rule.getName(),
                    registry.stateOf(token)));
        });

        admin.add("POST", "/admin/v1/failpoints/{token}/release", (request, ctx) -> {
            String token = token(request);
            boolean released = registry.releaseRule(token);
            Map<String, Object> body = state(ctx.requestId(), token, null,
                    registry.stateOf(token));
            body.put("released", released);
            return ApiResponse.ok(body);
        });

        admin.add("DELETE", "/admin/v1/failpoints/{token}", (request, ctx) -> {
            String token = token(request);
            registry.removeRule(token);
            Map<String, Object> body = ok(ctx.requestId());
            body.put("token", token);
            return ApiResponse.ok(body);
        });

        admin.add("DELETE", "/admin/v1/failpoints", (request, ctx) -> {
            registry.clear();
            return ApiResponse.ok(ok(ctx.requestId()));
        });
    }

    private static String token(ApiRequest request) throws ApiException {
        String token = request.pathParam("token");
        if (!isValidToken(token)) {
            throw ApiException.invalid("Invalid failpoint token");
        }
        return token;
    }

    /**
     * Same alphabet and length as request ids (PI §17.2); "catalog" is
     * reserved, since GET /admin/v1/failpoints/catalog is the catalog. A
     * bootstrap rule needs a valid token too, so its state can be read here.
     */
    public static boolean isValidToken(String token) {
        return RequestContext.isValidRequestId(token) && !CATALOG.equals(token);
    }

    /**
     * One rule of the failpoint API (PI §21.1); the bootstrap rules of
     * {@code DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64} use the same format (PI §21.2).
     *
     * @throws ApiException {@code INVALID_REQUEST} for an unknown failpoint or
     *                      action, or a bad field
     */
    public static Rule parseRule(String token, ObjectNode body) throws ApiException {
        String name = requiredText(body, "name");
        if (!FailpointCatalog.contains(name)) {
            throw ApiException.invalid("Unknown failpoint: " + name);
        }

        Action action;
        try {
            action = Action.valueOf(requiredText(body, "action"));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("Unknown failpoint action");
        }
        if (!FailpointCatalog.supports(name, action)) {
            throw ApiException.invalid("Action " + action + " is not supported by " + name);
        }

        int afterHits = optionalInt(body, "afterHits", 0);
        int maxTriggers = optionalInt(body, "maxTriggers", 1);
        int pauseTimeoutMs = optionalInt(body, "pauseTimeoutMs", 0);

        Map<String, String> parameters = new HashMap<>();
        JsonNode filters = optionalObject(body, "filters");
        if (filters != null) {
            if (filters.has("requestId")) {
                String requestId = requiredText((ObjectNode) filters, "requestId");
                if (!RequestContext.isValidRequestId(requestId)) {
                    throw ApiException.invalid("Invalid filters.requestId");
                }
                parameters.put("requestId", requestId);
            }
            if (filters.has("partitionId")) {
                parameters.put("partitionId",
                        String.valueOf(optionalInt((ObjectNode) filters, "partitionId", 0)));
            }
        }
        JsonNode actionParameters = optionalObject(body, "parameters");
        if (actionParameters != null && actionParameters.has("prefixBytes")) {
            parameters.put("prefixBytes", String.valueOf(
                    optionalInt((ObjectNode) actionParameters, "prefixBytes", 0)));
        }

        String errorCode = null;
        if (body.has("errorCode") && !body.get("errorCode").isNull()) {
            errorCode = requiredText(body, "errorCode");
        }

        try {
            return new Rule(token, name, action, afterHits, maxTriggers, pauseTimeoutMs,
                    parameters, errorCode);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    private static String requiredText(ObjectNode body, String field) throws ApiException {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual() || node.textValue().isEmpty()) {
            throw ApiException.invalid("Field " + field + " must be a non-empty string");
        }
        return node.textValue();
    }

    /** A non-negative integer field, or {@code fallback} when absent. */
    private static int optionalInt(ObjectNode body, String field, int fallback)
            throws ApiException {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isInt() || node.intValue() < 0) {
            throw ApiException.invalid("Field " + field + " must be a non-negative integer");
        }
        return node.intValue();
    }

    private static JsonNode optionalObject(ObjectNode body, String field) throws ApiException {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw ApiException.invalid("Field " + field + " must be an object");
        }
        return node;
    }

    private static Map<String, Object> ok(String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("requestId", requestId);
        return body;
    }

    private static Map<String, Object> state(String requestId, String token, String name,
                                             FailpointRegistry.State state) {
        Map<String, Object> body = ok(requestId);
        body.put("token", token);
        if (name != null) {
            body.put("name", name);
        }
        body.put("state", state.name());
        return body;
    }
}
