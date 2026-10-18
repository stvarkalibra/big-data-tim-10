package rs.raf.dlsm.engine;

import java.util.List;
import java.util.Objects;

/**
 * What startup recovery hands the engine once its durable work is done
 * (CP1 §21.1 steps 10–14). The engine checks that nothing it is given could
 * make it reuse a sequence or a WAL segment.
 *
 * @param lastUsedSequence    {@code max(flushedThroughSequence, greatest
 *                            valid WAL sequence)}, or 0 for a new database
 *                            (step 11); the next mutation gets this + 1
 * @param recoveredGeneration WAL records above {@code flushedThroughSequence},
 *                            replayed into one frozen generation (step 10;
 *                            generation 1, CP1 §14.2), or null if none
 * @param tables              every table the manifest references, already
 *                            fully validated (steps 4, 14)
 * @param activeLog           the new, durable, empty WAL segment for the
 *                            active generation (step 12); its id is above
 *                            every recovered segment
 * @param walFactory          creates later segments on rotation
 */
public record RecoveredState(
        long lastUsedSequence,
        ImmutableMemTable recoveredGeneration,
        List<TableSource> tables,
        MutationLog activeLog,
        WalFactory walFactory) {

    public RecoveredState {
        if (lastUsedSequence < 0) {
            throw new IllegalArgumentException("lastUsedSequence must not be negative");
        }
        tables = List.copyOf(Objects.requireNonNull(tables, "tables"));
        Objects.requireNonNull(activeLog, "activeLog");
        Objects.requireNonNull(walFactory, "walFactory");
    }

    /** A brand-new, empty database (CP1 §14.2): next sequence 1, no tables. */
    public static RecoveredState empty(MutationLog activeLog, WalFactory walFactory) {
        return new RecoveredState(0, null, List.of(), activeLog, walFactory);
    }
}
