package rs.raf.dlsm.server.http;

/**
 * Partition context echoed in public responses. CP1 has no partitioning,
 * so every routed request uses the fixed placeholder {@link #STANDALONE}
 * (PI §18.2): these are not consensus metadata.
 */
public record PartitionRef(int partitionId, long epoch) {

    public static final PartitionRef STANDALONE = new PartitionRef(0, 0);

    public PartitionRef {
        if (partitionId < 0 || epoch < 0) {
            throw new IllegalArgumentException("Invalid partition context");
        }
    }
}
