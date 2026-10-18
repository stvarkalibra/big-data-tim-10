
package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.stats.StatusBuilder;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StorageStatusRoutesTest {

    private final StorageStats stats = new StorageStats();
    private final AtomicInteger snapshotsTaken = new AtomicInteger();

    private StatusBuilder.Snapshot snapshot() {
        snapshotsTaken.incrementAndGet();

        return new StatusBuilder.Snapshot(
                "standalone",
                "READY",
                "SERVING",
                false,
                0,
                null,
                12L,
                new StatusBuilder.Memtable(
                        2,
                        2L,
                        null,
                        2,
                        100,
                        10L,
                        11L,
                        false
                ),
                null,
                List.of(),
                new StatusBuilder.Manifest(
                        1,
                        2,
                        9
                ),
                List.of(
                        new StatusBuilder.Table(
                                1,
                                "sst-00000000000000000001.sst",
                                "alpha",
                                "omega",
                                1,
                                9,
                                9,
                                1024
                        )
                ),
                new StatusBuilder.Maintenance(
                        "NONE",
                        "IDLE",
                        null,
                        List.of()
                ),
                stats.snapshot()
        );
    }

    private Router router() {
        Router router = new Router();

        StorageStatusRoutes.register(
                router,
                this::snapshot,
                () -> 11L,
                () -> 11L,
                stats
        );

        return router;
    }

    private ApiResponse call(
            Router router,
            String method,
            String path,
            String body) throws Exception {

        Router.Found found =
                assertInstanceOf(
                        Router.Found.class,
                        router.match(method, path)
                );

        byte[] bytes = body == null
                ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);

        ApiRequest request = ApiRequest.of(
                method,
                path,
                Map.of(),
                null,
                bytes
        );

        return found.handler().handle(
                request,
                OpContext.withTimeout("status-test", 5000)
        );
    }

    @Test
    void storageStatusReturnsFullSnapshot() throws Exception {
        Router router = router();

        ApiResponse response = call(
                router,
                "GET",
                "/admin/v1/storage/status",
                null
        );

        assertEquals(200, response.status());
        assertEquals("standalone",
                response.body().get("nodeId"));
        assertEquals("READY",
                response.body().get("lifecycle"));
        assertEquals("SERVING",
                response.body().get("engineMode"));
        assertNotNull(response.body().get("manifest"));
        assertNotNull(response.body().get("sstables"));
        assertNotNull(response.body().get("stats"));
        assertEquals(1, snapshotsTaken.get());
    }

    @Test
    void standaloneStatusReturnsSyntheticPartition()
            throws Exception {

        Router router = router();

        ApiResponse response = call(
                router,
                "GET",
                "/admin/v1/status",
                null
        );

        assertEquals(200, response.status());
        assertEquals(0,
                response.body().get("metadataRevision"));
        assertNull(response.body().get("metadataLeaderId"));
        assertNotNull(
                response.body().get("standaloneStorage"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> partitions =
                (List<Map<String, Object>>)
                        response.body().get("partitions");

        assertEquals(1, partitions.size());

        Map<String, Object> partition = partitions.get(0);

        assertEquals(0, partition.get("partitionId"));
        assertEquals("PRIMARY", partition.get("role"));
        assertEquals(0, partition.get("epoch"));
        assertEquals(true, partition.get("ready"));
        assertEquals(11L,
                partition.get("lastAcceptedSequence"));
        assertEquals(11L,
                partition.get("lastDurableSequence"));
        assertEquals(1, snapshotsTaken.get());
    }

    @Test
    void resetClearsCountersAndIncrementsEpoch()
            throws Exception {

        Router router = router();

        stats.increment("bloomPositiveProbes");
        stats.add("sstableDataBlocksRead", 3);

        ApiResponse response = call(
                router,
                "POST",
                "/admin/v1/storage/stats/reset",
                null
        );

        assertEquals(200, response.status());
        assertEquals(1L, response.body().get("resetEpoch"));
        assertEquals(0L,
                stats.snapshot().get("bloomPositiveProbes"));
        assertEquals(0L,
                stats.snapshot().get("sstableDataBlocksRead"));
        assertEquals(1L,
                stats.snapshot().get("resetEpoch"));
        assertEquals(0, snapshotsTaken.get());
    }

    @Test
    void resetAcceptsEmptyJsonObject() throws Exception {
        Router router = router();

        ApiResponse response = call(
                router,
                "POST",
                "/admin/v1/storage/stats/reset",
                "  {}  "
        );

        assertEquals(200, response.status());
        assertEquals(1L, response.body().get("resetEpoch"));
    }

    @Test
    void resetRejectsNonEmptyJsonObject() {
        Router router = router();

        assertThrows(
                ApiException.class,
                () -> call(
                        router,
                        "POST",
                        "/admin/v1/storage/stats/reset",
                        "{\"unexpected\":true}"
                )
        );

        assertEquals(0L, stats.snapshot().get("resetEpoch"));
    }

    @Test
    void resetRejectsInvalidJson() {
        Router router = router();

        assertThrows(
                ApiException.class,
                () -> call(
                        router,
                        "POST",
                        "/admin/v1/storage/stats/reset",
                        "{invalid"
                )
        );

        assertEquals(0L, stats.snapshot().get("resetEpoch"));
    }

    @Test
    void statusReadsFreshStatistics() throws Exception {
        Router router = router();

        stats.increment("bloomPositiveProbes");

        ApiResponse first = call(
                router,
                "GET",
                "/admin/v1/storage/status",
                null
        );

        stats.increment("bloomPositiveProbes");

        ApiResponse second = call(
                router,
                "GET",
                "/admin/v1/storage/status",
                null
        );

        assertEquals(1L, counter(first, "bloomPositiveProbes"));
        assertEquals(2L, counter(second, "bloomPositiveProbes"));
        assertEquals(2, snapshotsTaken.get());
    }

    @Test
    void eachMethodIsRegisteredSeparately() {
        Router router = router();

        assertInstanceOf(
                Router.Found.class,
                router.match(
                        "GET",
                        "/admin/v1/storage/status"
                )
        );

        assertInstanceOf(
                Router.MethodNotAllowed.class,
                router.match(
                        "POST",
                        "/admin/v1/storage/status"
                )
        );

        assertInstanceOf(
                Router.MethodNotAllowed.class,
                router.match(
                        "GET",
                        "/admin/v1/storage/stats/reset"
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static long counter(
            ApiResponse response,
            String name) {

        Map<String, Long> counters =
                (Map<String, Long>) response.body().get("stats");

        return counters.get(name);
    }
}
