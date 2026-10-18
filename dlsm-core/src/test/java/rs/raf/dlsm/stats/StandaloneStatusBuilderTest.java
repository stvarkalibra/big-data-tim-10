
package rs.raf.dlsm.stats;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneStatusBuilderTest {

    private StatusBuilder.Snapshot snapshot(
            String lifecycle,
            boolean backpressure,
            int tableCount) {

        String[] counterNames = {
                "resetEpoch",
                "walAppendAttempts",
                "walAppendsCompleted",
                "walForceAttempts",
                "walForcesSucceeded",
                "walBytesWritten",
                "flushAttempts",
                "flushPublications",
                "compactionAttempts",
                "compactionPublications",
                "sstableDataBlocksRead",
                "sstableDataBytesRead",
                "bloomNegativeSkips",
                "bloomPositiveProbes",
                "rangeScanBlocksRead",
                "orphanFilesRemoved",
                "temporaryFilesRemoved",
                "walRecordsReplayed",
                "walTornTailsTruncated"
        };

        Map<String, Long> stats = new HashMap<>();

        for (String name : counterNames) {
            stats.put(name, 0L);
        }

        var tables = new ArrayList<StatusBuilder.Table>();

        for (int i = 1; i <= tableCount; i++) {
            tables.add(new StatusBuilder.Table(
                    i,
                    "sst-%020d.sst".formatted(i),
                    "alpha",
                    "omega",
                    1,
                    40,
                    31,
                    1024
            ));
        }

        return new StatusBuilder.Snapshot(
                "standalone",
                lifecycle,
                backpressure ? "BACKPRESSURED"
                        : "SERVING",
                backpressure,
                backpressure ? 2 : 0,
                null,
                48L,
                new StatusBuilder.Memtable(
                        6, 6L, null,
                        5, 211, 41L, 47L, false
                ),
                null,
                List.of(),
                new StatusBuilder.Manifest(8, 12, 40),
                tables,
                new StatusBuilder.Maintenance(
                        "NONE", "IDLE", null, List.of()
                ),
                stats
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> partition(
            Map<String, Object> status) {

        return ((List<Map<String, Object>>)
                status.get("partitions")).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> storage(
            Map<String, Object> status) {

        return (Map<String, Object>)
                status.get("standaloneStorage");
    }

    @Test
    void createsRequiredTopLevelFields() {
        var status = StandaloneStatusBuilder.build(
                snapshot("READY", false, 1), 47, 47);

        assertEquals(1, status.get("protocolVersion"));
        assertEquals("standalone", status.get("nodeId"));
        assertEquals("STORAGE", status.get("nodeRole"));
        assertEquals("READY", status.get("lifecycle"));
        assertEquals(0, status.get("metadataRevision"));
        assertNull(status.get("metadataLeaderId"));
    }

    @Test
    void createsSyntheticPrimaryPartition() {
        var status = StandaloneStatusBuilder.build(
                snapshot("READY", false, 1), 47, 47);

        var partition = partition(status);

        assertEquals(0, partition.get("partitionId"));
        assertEquals("PRIMARY", partition.get("role"));
        assertEquals(0, partition.get("epoch"));
        assertEquals(true, partition.get("ready"));
        assertEquals(47L, partition.get("lastAcceptedSequence"));
        assertEquals(47L, partition.get("lastDurableSequence"));
    }

    @Test
    void createsStandaloneStorageSummary() {
        var status = StandaloneStatusBuilder.build(
                snapshot("READY", false, 2), 47, 47);

        var storage = storage(status);

        assertEquals("SERVING", storage.get("engineMode"));
        assertEquals(8L, storage.get("manifestGeneration"));
        assertEquals(40L, storage.get("flushedThroughSequence"));
        assertEquals(6L, storage.get("activeWalSegmentId"));
        assertEquals(2, storage.get("sstableCount"));
        assertEquals(false, storage.get("writeBackpressure"));
    }

    @Test
    void emptyDatabaseAllowsZeroSequences() {
        var status = StandaloneStatusBuilder.build(
                snapshot("READY", false, 0), 0, 0);

        assertEquals(0L,
                partition(status).get("lastAcceptedSequence"));
        assertEquals(0L,
                partition(status).get("lastDurableSequence"));
        assertEquals(0,
                storage(status).get("sstableCount"));
    }

    @Test
    void notReadyLifecycleMakesPartitionNotReady() {
        var status = StandaloneStatusBuilder.build(
                snapshot("STARTING", false, 0), 0, 0);

        assertEquals(false, partition(status).get("ready"));
    }

    @Test
    void reportsBackpressure() {
        var status = StandaloneStatusBuilder.build(
                snapshot("READY", true, 1), 47, 47);

        assertEquals("BACKPRESSURED",
                storage(status).get("engineMode"));
        assertEquals(true,
                storage(status).get("writeBackpressure"));
    }

    @Test
    void rejectsDurableSequenceAboveAcceptedWhenReady() {
        assertThrows(IllegalArgumentException.class,
                () -> StandaloneStatusBuilder.build(
                        snapshot("READY", false, 0),
                        40, 41));
    }

    @Test
    void allowsDurableSequenceAboveAcceptedWhenFailed() {
        var status = StandaloneStatusBuilder.build(
                snapshot("FAILED", false, 0),
                40, 41
        );

        var partition = partition(status);

        assertEquals("FAILED", status.get("lifecycle"));
        assertEquals(false, partition.get("ready"));
        assertEquals(40L,
                partition.get("lastAcceptedSequence"));
        assertEquals(41L,
                partition.get("lastDurableSequence"));
    }

    @Test
    void allowsDurableSequenceAboveAcceptedDuringRecovery() {
        var status = StandaloneStatusBuilder.build(
                snapshot("RECOVERING", false, 0),
                40, 41
        );

        assertEquals(false, partition(status).get("ready"));
        assertEquals(41L,
                partition(status).get("lastDurableSequence"));
    }

    @Test
    void rejectsNegativeAcceptedSequence() {
        assertThrows(IllegalArgumentException.class,
                () -> StandaloneStatusBuilder.build(
                        snapshot("READY", false, 0),
                        -1, 0));
    }

    @Test
    void rejectsNegativeDurableSequence() {
        assertThrows(IllegalArgumentException.class,
                () -> StandaloneStatusBuilder.build(
                        snapshot("READY", false, 0),
                        0, -1));
    }
}
