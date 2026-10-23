package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneNodeMaintenanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private StandaloneNode node;

    @TempDir
    Path dataDir;

    @BeforeEach
    void start() throws Exception {
        JsonLog log = new JsonLog("standalone",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        node = StandaloneNode.start(
                new ServerConfig("standalone", 0, 0, 1 << 20).withDataDir(dataDir), log,
                WindowsSafeStorageIO::new);
    }

    @AfterEach
    void stop() {
        node.close();
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("X-Request-Id", "maintenance-1")
                        .POST(body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void put(String key) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + node.publicPort() + "/v1/kv/" + key))
                        .header("X-Request-Id", "put-" + key)
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"QQ==\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void emptyNodeFlushIsANoOp() throws Exception {
        HttpResponse<String> response = post(node.adminPort(), "/admin/v1/storage/flush", null);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("NO_OP", body.get("result").asText());
        assertEquals(0, body.get("publishedTableIds").size());
    }

    @Test
    void flushPublishesTheWrittenDataAsATable() throws Exception {
        put("alpha");
        put("beta");

        HttpResponse<String> response = post(node.adminPort(), "/admin/v1/storage/flush", null);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("PUBLISHED", body.get("result").asText());
        assertEquals(2, body.get("targetSequence").asLong());
        assertEquals(2, body.get("flushedThroughSequence").asLong());
        assertEquals(1, body.get("publishedTableIds").size());
        long tableId = body.get("publishedTableIds").get(0).asLong();
        assertTrue(Files.isRegularFile(
                new DataLayout(dataDir).sstDir().resolve(SSTableMeta.fileName(tableId))));
    }

    @Test
    void emptyNodeCompactionIsANoOp() throws Exception {
        HttpResponse<String> response = post(node.adminPort(), "/admin/v1/storage/compact", "{}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("NO_OP", body.get("result").asText());
        assertEquals(0, body.get("inputTableIds").size());
        assertTrue(body.get("outputTableId").isNull());
        assertEquals(404, post(node.publicPort(), "/admin/v1/storage/compact", null).statusCode());
    }

    @Test
    void compactionReplacesTwoFlushedTables() throws Exception {
        put("alpha");
        assertEquals(200, post(node.adminPort(), "/admin/v1/storage/flush", null).statusCode());
        put("beta");
        assertEquals(200, post(node.adminPort(), "/admin/v1/storage/flush", null).statusCode());

        HttpResponse<String> response = post(node.adminPort(), "/admin/v1/storage/compact", null);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("PUBLISHED", body.get("result").asText());
        assertEquals(2, body.get("inputTableIds").size());
        long output = body.get("outputTableId").asLong();
        Path sst = new DataLayout(dataDir).sstDir();
        assertTrue(Files.isRegularFile(sst.resolve(SSTableMeta.fileName(output))));
        for (JsonNode input : body.get("inputTableIds")) {
            assertFalse(Files.exists(sst.resolve(SSTableMeta.fileName(input.asLong()))));
        }
    }

    @Test
    void flushRejectsInvalidBody() throws Exception {
        HttpResponse<String> response = post(node.adminPort(), "/admin/v1/storage/flush", "{\"x\":1}");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals("INVALID_REQUEST", MAPPER.readTree(response.body()).get("code").asText());
    }

    @Test
    void flushIsOnlyOnTheAdminPort() throws Exception {
        assertEquals(404, post(node.publicPort(), "/admin/v1/storage/flush", null).statusCode());
    }
}
