
package rs.raf.dlsm.stats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class StandaloneStatusBuilder {

    private StandaloneStatusBuilder() {
    }

    public static Map<String, Object> build(
            StatusBuilder.Snapshot snapshot,
            long lastAcceptedSequence,
            long lastDurableSequence) {

        Objects.requireNonNull(snapshot, "snapshot");

        if (lastAcceptedSequence < 0
                || lastDurableSequence < 0
                || lastDurableSequence > lastAcceptedSequence) {
            throw new IllegalArgumentException(
                    "Invalid accepted/durable sequence");
        }

        boolean ready = "READY".equals(snapshot.lifecycle());

        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("partitionId", 0);
        partition.put("role", "PRIMARY");
        partition.put("epoch", 0);
        partition.put("ready", ready);
        partition.put("lastAcceptedSequence", lastAcceptedSequence);
        partition.put("lastDurableSequence", lastDurableSequence);

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("engineMode", snapshot.engineMode());
        storage.put("manifestGeneration",
                snapshot.manifest().generation());
        storage.put("flushedThroughSequence",
                snapshot.manifest().flushedThroughSequence());
        storage.put("activeWalSegmentId",
                snapshot.activeMemtable().walSegmentId());
        storage.put("sstableCount", snapshot.sstables().size());
        storage.put("writeBackpressure",
                snapshot.writeBackpressure());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", 1);
        result.put("nodeId", snapshot.nodeId());
        result.put("nodeRole", "STORAGE");
        result.put("lifecycle", snapshot.lifecycle());
        result.put("metadataRevision", 0);
        result.put("metadataLeaderId", null);
        result.put("partitions", List.of(partition));
        result.put("standaloneStorage", storage);

        return result;
    }
}
