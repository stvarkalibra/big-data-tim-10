package rs.raf.dlsm.maintenance;

import java.util.List;
import java.util.Objects;

public record ManualFlushResult(
        Result result,
        long targetSequence,
        List<Long> publishedTableIds,
        long manifestGeneration,
        long flushedThroughSequence
) {

    public enum Result { PUBLISHED, NO_OP }

    public ManualFlushResult {
        Objects.requireNonNull(result, "result");
        publishedTableIds = List.copyOf(publishedTableIds);
        if ((result == Result.NO_OP) != publishedTableIds.isEmpty()) {
            throw new IllegalArgumentException("PUBLISHED needs table IDs and NO_OP needs none");
        }
    }
}
