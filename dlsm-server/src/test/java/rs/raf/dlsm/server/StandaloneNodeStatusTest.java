package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.log.JsonLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The storage status endpoints on a running standalone node (CP1 §24). */
class StandaloneNodeStatusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private StandaloneNode node;
    private int requestNumber;

    @TempDir
    Path dataDir;

    private void start(long memtableMaxBytes) throws Exception {
        JsonLog log = new JsonLog("standalone",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        node = StandaloneNode.start(
                new ServerConfig("standalone", 0, 0, memtableMaxBytes).withDataDir(dataDir), log,
                WindowsSafeStorageIO::new);
    }

    @AfterEach
    void stop() {
        if (node != null) {
            node.close();
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                        .header("X-Request-Id", "status-" + (++requestNumber))
                        .method(method, body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode admin(String method, String path) throws Exception {
        HttpResponse<String> r = send(node.adminPort(), method, path, null);
        assertEquals(200, r.statusCode(), r.body());
        return MAPPER.readTree(r.body());
    }

    private void put(String key, String value) throws Exception {
        String base64 = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, send(node.publicPort(), "PUT", "/v1/kv/" + key,
                "{\"value\":\"" + base64 + "\"}").statusCode());
    }

    @Test
    void freshNodeReportsAnEmptyReadyEngine() throws Exception {
        start(1 << 20);
        JsonNode s = admin("GET", "/admin/v1/storage/status");

        assertEquals("standalone", s.get("nodeId").asText());
        assertEquals("READY", s.get("lifecycle").asText());
        assertEquals("SERVING", s.get("engineMode").asText());
        assertFalse(s.get("writeBackpressure").asBoolean());
        assertTrue(s.get("lastFatalError").isNull());
        assertEquals(1, s.get("nextSequence").asLong());
        assertEquals(1, s.get("activeMemtable").get("generation").asLong());
        assertEquals(1, s.get("activeMemtable").get("walSegmentId").asLong());
        assertTrue(s.get("immutableMemtable").isNull());
        assertEquals("ACTIVE", s.get("walSegments").get(0).get("role").asText());
        assertEquals(0, s.get("manifest").get("generation").asLong());
        assertEquals(0, s.get("manifest").get("sstableCount").asInt());
        assertEquals("IDLE", s.get("maintenance").get("phase").asText());
        assertTrue(s.get("stats").has("walForcesSucceeded"));
        assertTrue(s.get("stats").has("resetEpoch"));
    }

    @Test
    void cp1Api001StatusOracleOverHttp() throws Exception {
        start(1 << 20);
        put("alpha", "A");
        put("alpha", "B");
        put("alpha", "");

        JsonNode storage = admin("GET", "/admin/v1/storage/status");
        assertEquals(4, storage.get("nextSequence").asLong());
        assertEquals(1, storage.get("activeMemtable").get("entryCount").asLong());

        JsonNode shared = admin("GET", "/admin/v1/status");
        JsonNode partition = shared.get("partitions").get(0);
        assertEquals(0, partition.get("partitionId").asInt());
        assertEquals(0, partition.get("epoch").asInt());
        assertTrue(partition.get("ready").asBoolean());
        assertEquals(3, partition.get("lastAcceptedSequence").asLong());
        assertEquals(3, partition.get("lastDurableSequence").asLong());
        assertEquals(1, shared.get("standaloneStorage").get("activeWalSegmentId").asLong());
        assertTrue(shared.get("metadataLeaderId").isNull());
    }

    @Test
    void statsResetAdvancesTheEpoch() throws Exception {
        start(1 << 20);

        JsonNode reset = admin("POST", "/admin/v1/storage/stats/reset");
        assertEquals(1, reset.get("resetEpoch").asLong());
        assertEquals(1, admin("GET", "/admin/v1/storage/status")
                .get("stats").get("resetEpoch").asLong());
    }

    @Test
    void flushedTablesAppearConsistently() throws Exception {
        // Tiny generations: writes rotate and the maintenance worker publishes tables.
        start(256);
        for (int i = 0; i < 40; i++) {
            put("key" + i, "value" + i);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonNode s = admin("GET", "/admin/v1/storage/status");
        while (s.get("sstables").isEmpty()) {
            assertTrue(System.nanoTime() - deadline < 0, "no table was ever published");
            Thread.sleep(1);
            s = admin("GET", "/admin/v1/storage/status");
        }

        // CP1 §24: sstableCount equals the length of the sstables array.
        assertEquals(s.get("sstables").size(), s.get("manifest").get("sstableCount").asInt());
        assertTrue(s.get("manifest").get("flushedThroughSequence").asLong() > 0);
        assertTrue(s.get("activeMemtable").get("walSegmentId").asLong() > 1, "segments rotated");
        long previous = 0;
        for (JsonNode table : s.get("sstables")) {
            assertTrue(table.get("tableId").asLong() > previous, "ascending table ids");
            previous = table.get("tableId").asLong();
        }
    }

    @Test
    void statusIsOnlyOnTheAdminPort() throws Exception {
        start(1 << 20);

        assertEquals(404, send(node.publicPort(), "GET", "/admin/v1/storage/status", null)
                .statusCode());
    }
}
