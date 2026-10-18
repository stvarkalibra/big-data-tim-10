
package rs.raf.dlsm.stats;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatusBuilderTest {

    private Map<String, Long> stats() {
        String[] names = {
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

        Map<String, Long> result = new HashMap<>();

        for (String name : names) {
            result.put(name, 0L);
        }

        return result;
    }

    private StatusBuilder.Snapshot snapshot(
            List<StatusBuilder.Table> tables,
            List<StatusBuilder.WalSegment> walSegments,
            Map<String, Long> stats) {

        return new StatusBuilder.Snapshot(
                "standalone",
                "READY",
                "SERVING",
                false,
                0,
                null,
                11L,
                new StatusBuilder.Memtable(
                        2, 2L, null,
                        0, 0, null, null, false
                ),
                null,
                walSegments,
                new StatusBuilder.Manifest(3, 10, 5),
                tables,
                new StatusBuilder.Maintenance(
                        "NONE", "IDLE", null, List.of()
                ),
                stats
        );
    }

    private StatusBuilder.Table table(long id) {
        return new StatusBuilder.Table(
                id,
                "sst-%020d.sst".formatted(id),
                "anna",
                "zika",
                1,
                5,
                3,
                1024
        );
    }

    @Test
    void buildsRequiredStatusFields() {
        var result = StatusBuilder.build(
                snapshot(List.of(), List.of(), stats()));

        assertEquals(1, result.get("protocolVersion"));
        assertEquals("standalone", result.get("nodeId"));
        assertEquals("READY", result.get("lifecycle"));
        assertEquals("SERVING", result.get("engineMode"));
        assertEquals(false, result.get("writeBackpressure"));
        assertNull(result.get("lastFatalError"));
        assertEquals(11L, result.get("nextSequence"));
        assertNull(result.get("immutableMemtable"));
    }

    @Test
    void computesSstableCountFromActualTables() {
        var result = StatusBuilder.build(
                snapshot(
                        List.of(table(5), table(2)),
                        List.of(),
                        stats()));

        @SuppressWarnings("unchecked")
        var manifest =
                (Map<String, Object>) result.get("manifest");

        @SuppressWarnings("unchecked")
        var tables =
                (List<Map<String, Object>>) result.get("sstables");

        assertEquals(2, manifest.get("sstableCount"));
        assertEquals(2, tables.size());
        assertEquals(2L, tables.get(0).get("tableId"));
        assertEquals(5L, tables.get(1).get("tableId"));
    }

    @Test
    void sortsWalSegmentsByNumericId() {
        var wal20 = new StatusBuilder.WalSegment(
                20, "IMMUTABLE", 1L, 5L, 100);

        var wal3 = new StatusBuilder.WalSegment(
                3, "ACTIVE", null, null, 0);

        var result = StatusBuilder.build(
                snapshot(List.of(), List.of(wal20, wal3), stats()));

        @SuppressWarnings("unchecked")
        var segments =
                (List<Map<String, Object>>) result.get("walSegments");

        assertEquals(3L, segments.get(0).get("segmentId"));
        assertEquals(20L, segments.get(1).get("segmentId"));
    }

    @Test
    void includesAllStatistics() {
        var counters = stats();
        counters.put("resetEpoch", 3L);
        counters.put("bloomNegativeSkips", 12L);

        var result = StatusBuilder.build(
                snapshot(List.of(), List.of(), counters));

        @SuppressWarnings("unchecked")
        var actual =
                (Map<String, Object>) result.get("stats");

        assertEquals(3L, actual.get("resetEpoch"));
        assertEquals(12L, actual.get("bloomNegativeSkips"));
        assertEquals(19, actual.size());
    }

    @Test
    void rejectsMissingCounters() {
        var counters = stats();
        counters.remove("walBytesWritten");

        assertThrows(IllegalArgumentException.class,
                () -> snapshot(List.of(), List.of(), counters));
    }

    @Test
    void rejectsNegativeCounter() {
        var counters = stats();
        counters.put("walBytesWritten", -1L);

        assertThrows(IllegalArgumentException.class,
                () -> snapshot(List.of(), List.of(), counters));
    }

    @Test
    void rejectsDuplicateTableIds() {
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(
                        List.of(table(5), table(5)),
                        List.of(),
                        stats()));
    }

    @Test
    void rejectsInvalidEngineMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new StatusBuilder.Snapshot(
                        "standalone", "READY", "UNKNOWN",
                        false, 0, null, 1L,
                        new StatusBuilder.Memtable(
                                1, 1L, null, 0, 0,
                                null, null, false),
                        null,
                        List.of(),
                        new StatusBuilder.Manifest(1, 1, 0),
                        List.of(),
                        new StatusBuilder.Maintenance(
                                "NONE", "IDLE", null, List.of()),
                        stats()
                ));
    }
}
