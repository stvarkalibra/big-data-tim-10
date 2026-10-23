package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.InMemoryWal;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.RecoveredState;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.maintenance.CompactionJob;
import rs.raf.dlsm.maintenance.FlushJob;
import rs.raf.dlsm.maintenance.MaintenanceProgress;
import rs.raf.dlsm.maintenance.MaintenanceWorker;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.stats.StorageStats;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceRoutesTest {

    @TempDir
    Path root;

    private Engine engine;
    private MaintenanceWorker worker;

    private static OpContext ctx() {
        return OpContext.withTimeout("maintenance-routes-test", 5000);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private void startEngine() throws Exception {
        DataLayout layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        NoOpFailpoints failpoints = new NoOpFailpoints();
        DefaultStorageIO io = new DefaultStorageIO(failpoints);
        StorageStats stats = new StorageStats();
        ManifestManager manifests = new ManifestManager(layout, io);
        manifests.publishInitialManifest(ctx());
        TableIdAllocator tableIds = TableIdAllocator.fromDisk(manifests.current(), layout.sstDir(), layout.tmpDir());
        JsonLog log = new JsonLog("test",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), Clock.systemUTC());

        MaintenanceProgress progress = new MaintenanceProgress();
        InMemoryWal wal = new InMemoryWal();
        engine = Engine.open(new EngineConfig(EngineConfig.DEFAULT_MEMTABLE_MAX_BYTES, failpoints),
                new RecoveredState(0, null, List.of(), wal.createSegment(ctx()), wal),
                host -> worker = new MaintenanceWorker(host, manifests,
                        new FlushJob(layout, io, failpoints, stats, manifests, tableIds, host, progress),
                        new CompactionJob(layout, io, failpoints, stats, manifests, tableIds, host, progress), log));
    }

    private Router router() {
        Router router = new Router();
        MaintenanceRoutes.register(router, () -> worker);
        return router;
    }

    private ApiResponse flush(Router router, String body) throws Exception {
        Router.Found found = assertInstanceOf(Router.Found.class,
                router.match("POST", "/admin/v1/storage/flush"));
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return found.handler().handle(
                ApiRequest.of("POST", "/admin/v1/storage/flush", Map.of(), null, bytes), ctx());
    }

    @Test
    void onlyPostIsRouted() {
        Router.MethodNotAllowed notAllowed = assertInstanceOf(Router.MethodNotAllowed.class,
                router().match("GET", "/admin/v1/storage/flush"));

        assertEquals(Set.of("POST"), notAllowed.allowedMethods());
    }

    @Test
    void notReadyWithoutWorker() {
        StorageException e = assertThrows(StorageException.class, () -> flush(router(), null));

        assertEquals(ErrorCode.NODE_NOT_READY, e.code());
    }

    @Test
    void noOpOnEmptyEngineHasExactFields() throws Exception {
        startEngine();

        ApiResponse response = flush(router(), null);

        assertEquals(200, response.status());
        assertEquals(List.of("protocolVersion", "status", "operation", "result", "targetSequence",
                "publishedTableIds", "manifestGeneration", "flushedThroughSequence"),
                List.copyOf(response.body().keySet()));
        assertEquals(1, response.body().get("protocolVersion"));
        assertEquals("OK", response.body().get("status"));
        assertEquals("FLUSH", response.body().get("operation"));
        assertEquals("NO_OP", response.body().get("result"));
        assertEquals(0L, response.body().get("targetSequence"));
        assertEquals(List.of(), response.body().get("publishedTableIds"));
        assertEquals(0L, response.body().get("manifestGeneration"));
        assertEquals(0L, response.body().get("flushedThroughSequence"));
    }

    @Test
    void publishedFlushReportsTablesAndManifest() throws Exception {
        startEngine();
        engine.put("alpha", "A".getBytes(StandardCharsets.UTF_8), ctx());
        engine.put("beta", "B".getBytes(StandardCharsets.UTF_8), ctx());

        ApiResponse response = flush(router(), "{}");

        assertEquals(200, response.status());
        assertEquals("PUBLISHED", response.body().get("result"));
        assertEquals(2L, response.body().get("targetSequence"));
        assertEquals(List.of(1L), response.body().get("publishedTableIds"));
        assertEquals(1L, response.body().get("manifestGeneration"));
        assertEquals(2L, response.body().get("flushedThroughSequence"));
    }

    @Test
    void acceptsEmptyObjectWithWhitespace() throws Exception {
        startEngine();

        assertEquals(200, flush(router(), "  {}  ").status());
        assertEquals(200, flush(router(), "\n{}\n").status());
    }

    @Test
    void rejectsNonEmptyBody() throws Exception {
        startEngine();
        Router router = router();

        for (String body : List.of("{\"x\":1}", "[]", "null", "flush", "{} {}")) {
            ApiException e = assertThrows(ApiException.class, () -> flush(router, body), body);
            assertEquals(ErrorCode.INVALID_REQUEST, e.code());
        }
    }
}
