
package rs.raf.dlsm.sstable;

public record SSTableMeta(
        long tableId,
        String file,
        String minKey,
        String maxKey,
        long minSequence,
        long maxSequence,
        long entryCount,
        long sizeBytes
) {
    public SSTableMeta {
        if (tableId <= 0 || file == null || file.isBlank()) {
            throw new IllegalArgumentException("Invalid table identity");
        }
        if (minKey == null || maxKey == null
                || minKey.compareTo(maxKey) > 0) {
            throw new IllegalArgumentException("Invalid key range");
        }
        if (minSequence <= 0 || maxSequence < minSequence) {
            throw new IllegalArgumentException("Invalid sequence range");
        }
        if (entryCount <= 0 || sizeBytes <= 0) {
            throw new IllegalArgumentException("Invalid table size");
        }
    }

    public static String fileName(long tableId) {
        if (tableId <= 0) {
            throw new IllegalArgumentException("Invalid table ID");
        }
        return "sst-%020d.sst".formatted(tableId);
    }
}
