package rs.raf.dlsm.server.http;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.maintenance.MaintenanceWorker;
import rs.raf.dlsm.maintenance.ManualFlushResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class MaintenanceRoutes {

    private MaintenanceRoutes() {
    }

    public static void register(Router admin, Supplier<MaintenanceWorker> worker) {
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(worker, "worker");

        admin.add("POST", "/admin/v1/storage/flush", (request, ctx) -> {
            requireEmptyBody(request);
            MaintenanceWorker current = worker.get();
            if (current == null) {
                throw new StorageException(ErrorCode.NODE_NOT_READY, null, "Maintenance is not available");
            }
            return ApiResponse.ok(flushBody(current.flush(ctx)));
        });
    }

    static Map<String, Object> flushBody(ManualFlushResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", 1);
        body.put("status", "OK");
        body.put("operation", "FLUSH");
        body.put("result", result.result().name());
        body.put("targetSequence", result.targetSequence());
        body.put("publishedTableIds", result.publishedTableIds());
        body.put("manifestGeneration", result.manifestGeneration());
        body.put("flushedThroughSequence", result.flushedThroughSequence());
        return body;
    }

    private static void requireEmptyBody(ApiRequest request) throws ApiException {
        byte[] body = request.body();
        if (body.length != 0 && !new String(body, StandardCharsets.UTF_8).trim().equals("{}")) {
            throw ApiException.invalid("Expected an empty body or {}");
        }
    }
}
