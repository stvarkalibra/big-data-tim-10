
package rs.raf.dlsm.server.http;

import rs.raf.dlsm.stats.StandaloneStatusBuilder;
import rs.raf.dlsm.stats.StatusBuilder;
import rs.raf.dlsm.stats.StorageStats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Admin HTTP routes for CP1 storage status and statistics.
 *
 * The snapshot supplier must capture a coherent engine view.
 * The engine owner is responsible for taking the engine read lock
 * while producing that snapshot.
 *
 * The same StorageStats instance must be shared by the engine,
 * WAL, SSTable readers and maintenance workers.
 */
public final class StorageStatusRoutes {

    private StorageStatusRoutes() {
    }

    public static void register(
            Router admin,
            Supplier<StatusBuilder.Snapshot> snapshots,
            Supplier<Long> acceptedSequence,
            Supplier<Long> durableSequence,
            StorageStats stats) {

        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(
                acceptedSequence, "acceptedSequence");
        Objects.requireNonNull(
                durableSequence, "durableSequence");
        Objects.requireNonNull(stats, "stats");

        admin.add(
                "GET",
                "/admin/v1/storage/status",
                (request, ctx) -> {
                    StatusBuilder.Snapshot snapshot =
                            Objects.requireNonNull(
                                    snapshots.get(),
                                    "Engine status snapshot"
                            );

                    return ApiResponse.ok(
                            StatusBuilder.build(snapshot));
                }
        );

        admin.add(
                "GET",
                "/admin/v1/status",
                (request, ctx) -> {
                    StatusBuilder.Snapshot snapshot =
                            Objects.requireNonNull(
                                    snapshots.get(),
                                    "Engine status snapshot"
                            );

                    long accepted = Objects.requireNonNull(
                            acceptedSequence.get(),
                            "Last accepted sequence"
                    );

                    long durable = Objects.requireNonNull(
                            durableSequence.get(),
                            "Last durable sequence"
                    );

                    return ApiResponse.ok(
                            StandaloneStatusBuilder.build(
                                    snapshot,
                                    accepted,
                                    durable
                            )
                    );
                }
        );

        admin.add(
                "POST",
                "/admin/v1/storage/stats/reset",
                (request, ctx) -> {
                    if (request.body().length != 0
                            && !isEmptyJsonObject(
                            request.body())) {
                        throw ApiException.invalid(
                                "Expected an empty body or {}");
                    }

                    long epoch = stats.reset();

                    Map<String, Object> result =
                            new LinkedHashMap<>();

                    result.put("resetEpoch", epoch);

                    return ApiResponse.ok(result);
                }
        );
    }

    private static boolean isEmptyJsonObject(
            byte[] bytes) {

        String text = new String(
                bytes,
                java.nio.charset.StandardCharsets.UTF_8
        );

        return text.trim().equals("{}");
    }
}
