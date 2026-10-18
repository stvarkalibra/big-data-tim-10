package rs.raf.dlsm.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;
import rs.raf.dlsm.engine.GetResult;
import rs.raf.dlsm.engine.LiveScan;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.ScanResult;
import rs.raf.dlsm.engine.StorageEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The public key-value API on port 8080 (PI §18.3–§18.6).
 *
 * <p>Every request is fully validated here before the engine is called, so
 * a rejected request has no storage side effect (CP1 §9 step 1). Once the
 * key is known to be valid, responses and errors carry the standalone
 * partition context {@code partitionId=0, epoch=0} (PI §18.2).
 */
public final class KvRoutes {

    private static final PartitionRef PARTITION = PartitionRef.STANDALONE;

    private KvRoutes() {
    }

    public static void register(Router router, StorageEngine engine) {
        Objects.requireNonNull(engine, "engine");

        router.add("PUT", "/v1/kv/{key}", (request, ctx) -> {
            String key = key(request);
            byte[] value = value(request);
            engine.put(key, value, ctx);
            return ApiResponse.ok(okBody(ctx));
        });

        router.add("GET", "/v1/kv/{key}", (request, ctx) -> {
            String key = key(request);
            GetResult result = engine.get(key, ctx);
            if (!result.isFound()) {
                return ApiResponse.error(ErrorCode.NOT_FOUND, "Key not found",
                        ctx.requestId(), PARTITION);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "OK");
            body.put("requestId", ctx.requestId());
            body.put("key", key);
            body.put("value", Base64Values.encode(result.value()));
            body.put("partitionId", PARTITION.partitionId());
            body.put("epoch", PARTITION.epoch());
            return ApiResponse.ok(body);
        });

        router.add("DELETE", "/v1/kv/{key}", (request, ctx) -> {
            engine.delete(key(request), ctx);
            return ApiResponse.ok(okBody(ctx));
        });

        router.add("GET", "/v1/scan", (request, ctx) -> {
            String start = scanKey(request, "start");
            String end = scanKey(request, "end");
            int limit = limit(request);
            if (Keys.compare(start, end) >= 0) {
                throw ApiException.invalid("start must be less than end");
            }

            ScanResult result = engine.scan(start, end, limit, ctx);

            List<Map<String, Object>> items = new ArrayList<>(result.items().size());
            for (InternalRecord record : result.items()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", record.key());
                item.put("value", Base64Values.encode(record.value()));
                items.add(item);
            }

            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("partitionId", PARTITION.partitionId());
            observed.put("epoch", PARTITION.epoch());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "OK");
            body.put("requestId", ctx.requestId());
            body.put("items", items);
            body.put("count", items.size());
            body.put("observedPartitions", List.of(observed));
            return ApiResponse.ok(body);
        });
    }

    /** Shared success body of PUT and DELETE (PI §18.3, §18.5). */
    private static Map<String, Object> okBody(OpContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("requestId", ctx.requestId());
        body.put("partitionId", PARTITION.partitionId());
        body.put("epoch", PARTITION.epoch());
        return body;
    }

    private static String key(ApiRequest request) throws ApiException {
        String key = request.pathParam("key");
        if (!Keys.isValid(key)) {
            throw ApiException.invalid("Invalid key");
        }
        return key;
    }

    private static byte[] value(ApiRequest request) throws ApiException {
        ObjectNode body;
        try {
            body = Json.readObject(request.body());
        } catch (ApiException e) {
            throw new ApiException(e.code(), e.getMessage(), PARTITION);
        }

        JsonNode node = body.get("value");
        if (node == null || !node.isTextual()) {
            // null is not another spelling of the empty value (PI §18.1).
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Field value must be a Base64 string", PARTITION);
        }

        String text = node.textValue();
        if (text.length() > Base64Values.maxEncodedLength(InternalRecord.MAX_VALUE_BYTES)) {
            throw new ApiException(ErrorCode.VALUE_TOO_LARGE, "Value too large", PARTITION);
        }

        byte[] value = Base64Values.decodeStrict(text);
        if (value == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Field value is not standard padded Base64", PARTITION);
        }
        if (value.length > InternalRecord.MAX_VALUE_BYTES) {
            throw new ApiException(ErrorCode.VALUE_TOO_LARGE, "Value too large", PARTITION);
        }
        return value;
    }

    private static String scanKey(ApiRequest request, String name) throws ApiException {
        String key = request.queryParam(name).orElseThrow(
                () -> ApiException.invalid("Missing query parameter: " + name));
        if (!Keys.isValid(key)) {
            throw ApiException.invalid("Invalid query parameter: " + name);
        }
        return key;
    }

    private static int limit(ApiRequest request) throws ApiException {
        String text = request.queryParam("limit").orElseThrow(
                () -> ApiException.invalid("Missing query parameter: limit"));
        // Plain digits only; at most 4 digits keeps parsing overflow-free.
        if (text.isEmpty() || text.length() > 4
                || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw ApiException.invalid("Invalid query parameter: limit");
        }
        int limit = Integer.parseInt(text);
        if (limit < 1 || limit > LiveScan.MAX_LIMIT) {
            throw ApiException.invalid("limit must be 1.." + LiveScan.MAX_LIMIT);
        }
        return limit;
    }
}
