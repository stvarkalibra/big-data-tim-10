package rs.raf.dlsm.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.stats.StandaloneStatusBuilder;
import rs.raf.dlsm.stats.StatusBuilder;
import rs.raf.dlsm.stats.StorageStats;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The engine status rendered through the status module, as the endpoints will. */
class EngineStatusMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static OpContext ctx() {
        return OpContext.withTimeout("map", 5000);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Placeholder parts of the other modules, shaped as CP1 §24 requires. */
    private static StatusBuilder.Snapshot snapshot(EngineStatus status) {
        return status.toSnapshot("standalone",
                List.of(new StatusBuilder.WalSegment(2, "ACTIVE", 3L, 3L, 100)),
                new StatusBuilder.Manifest(1, 2, 2),
                new StatusBuilder.Maintenance("NONE", "IDLE", null, List.of()),
                new StorageStats().snapshot());
    }

    private static JsonNode storageStatusJson(EngineStatus status) {
        return MAPPER.valueToTree(StatusBuilder.build(snapshot(status)));
    }

    /** Engine with generation 1 flushed as table 1 and generation 2 active. */
    private static Engine engineWithOneTable() throws Exception {
        Engine engine = TestEngines.withoutFlusher(EngineConfig.defaults());
        engine.put("alpha", bytes("A"), ctx());
        engine.delete("beta", ctx());
        engine.rotateIfActiveContainsUpTo(2, ctx());
        FlushInput input = engine.oldestImmutable().orElseThrow();
        engine.underEngineWriteLock(ctx(), () -> {
            engine.installFlushResult(input.memtableGeneration(),
                    FakeTable.flushOf(1, input.frozen()));
            return null;
        });
        engine.put("gamma", bytes("G"), ctx());
        return engine;
    }

    @Test
    void storageStatusHasTheEngineFieldsOfCp1Section24() throws Exception {
        JsonNode json = storageStatusJson(engineWithOneTable().status(ctx()));

        assertEquals("standalone", json.get("nodeId").asText());
        assertEquals("READY", json.get("lifecycle").asText());
        assertEquals("SERVING", json.get("engineMode").asText());
        assertFalse(json.get("writeBackpressure").asBoolean());
        assertEquals(0, json.get("backpressuredWriters").asInt());
        assertTrue(json.get("lastFatalError").isNull());
        assertEquals(4, json.get("nextSequence").asLong());

        JsonNode active = json.get("activeMemtable");
        assertEquals(2, active.get("generation").asLong());
        assertEquals(2, active.get("walSegmentId").asLong());
        assertEquals(1, active.get("entryCount").asLong());
        assertEquals(32 + 5 + 1, active.get("logicalBytes").asLong());
        assertEquals(3, active.get("minSequence").asLong());
        assertEquals(3, active.get("maxSequence").asLong());
        assertFalse(active.get("rotationRequired").asBoolean());
        assertFalse(active.has("walSegmentIds"), "only the frozen generation lists segments");
        assertTrue(json.get("immutableMemtable").isNull());

        JsonNode table = json.get("sstables").get(0);
        assertEquals(1, table.get("tableId").asLong());
        assertEquals("sst-00000000000000000001.sst", table.get("file").asText());
        assertEquals("alpha", table.get("minKey").asText());
        assertEquals("beta", table.get("maxKey").asText());
        assertEquals(2, table.get("entryCount").asLong(), "tombstones count as entries");
        // Internal consistency (CP1 §24): sstableCount equals the array length.
        assertEquals(json.get("sstables").size(), json.get("manifest").get("sstableCount").asInt());
    }

    @Test
    void frozenGenerationListsItsSegments() throws Exception {
        Engine engine = TestEngines.withoutFlusher(EngineConfig.defaults());
        engine.put("a", bytes("1"), ctx());
        engine.rotateIfActiveContainsUpTo(1, ctx());

        JsonNode frozen = storageStatusJson(engine.status(ctx())).get("immutableMemtable");
        assertEquals(1, frozen.get("generation").asLong());
        assertEquals("[1]", frozen.get("walSegmentIds").toString());
        assertFalse(frozen.has("walSegmentId"));
        assertFalse(frozen.has("rotationRequired"));
    }

    @Test
    void emptyActiveGenerationHasNullSequences() throws Exception {
        JsonNode active = storageStatusJson(
                TestEngines.withoutFlusher(EngineConfig.defaults()).status(ctx()))
                .get("activeMemtable");

        assertTrue(active.get("minSequence").isNull());
        assertTrue(active.get("maxSequence").isNull());
    }

    @Test
    void failedEngineReportsItsFirstFatalError() throws Exception {
        Engine engine = TestEngines.withoutFlusher(EngineConfig.defaults());
        engine.reportFatalError(new StorageException(ErrorCode.STORAGE_IO, "CHECKSUM",
                "Checksum mismatch at offset 60"));

        JsonNode json = storageStatusJson(engine.status(ctx()));
        assertEquals("FAILED", json.get("lifecycle").asText());
        assertEquals("FAILED", json.get("engineMode").asText());
        assertEquals("CHECKSUM", json.get("lastFatalError").get("code").asText());
        assertEquals("Checksum mismatch at offset 60",
                json.get("lastFatalError").get("message").asText());
    }

    @Test
    void sharedStatusCarriesAcceptedAndDurableSequences() throws Exception {
        EngineStatus status = engineWithOneTable().status(ctx());
        Map<String, Object> shared = StandaloneStatusBuilder.build(snapshot(status),
                status.lastAcceptedSequence(), status.lastDurableSequence());
        JsonNode json = MAPPER.valueToTree(shared);

        JsonNode partition = json.get("partitions").get(0);
        assertEquals(0, partition.get("partitionId").asInt());
        assertEquals("PRIMARY", partition.get("role").asText());
        assertTrue(partition.get("ready").asBoolean());
        assertEquals(3, partition.get("lastAcceptedSequence").asLong());
        assertEquals(3, partition.get("lastDurableSequence").asLong());

        JsonNode storage = json.get("standaloneStorage");
        assertEquals("SERVING", storage.get("engineMode").asText());
        assertEquals(2, storage.get("activeWalSegmentId").asLong());
        assertEquals(1, storage.get("sstableCount").asInt());
        assertFalse(storage.get("writeBackpressure").asBoolean());
    }
}
