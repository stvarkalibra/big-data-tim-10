package rs.raf.dlsm.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable snapshot of the engine's non-active sources: the frozen
 * generation waiting for flush, if any, and the published tables ordered by
 * table id. A new view replaces the old one atomically under the engine
 * write lock; a reader holding an old view keeps a consistent picture
 * (CP1 §20.4 step 8).
 *
 * <p>The active MemTable is not part of the view because it keeps changing.
 */
public record EngineView(ImmutableMemTable immutable, List<TableSource> tables) {

    public static final EngineView EMPTY = new EngineView(null, List.of());

    public EngineView {
        List<TableSource> sorted = new ArrayList<>(tables);
        sorted.sort(Comparator.comparingLong(TableSource::tableId));

        Set<Long> ids = new HashSet<>();
        for (TableSource table : sorted) {
            if (!ids.add(table.tableId())) {
                throw new IllegalArgumentException(
                        "Duplicate table id " + table.tableId());
            }
        }
        tables = List.copyOf(sorted);
    }

    public Optional<ImmutableMemTable> immutableMemTable() {
        return Optional.ofNullable(immutable);
    }

    public List<Long> tableIds() {
        return tables.stream().map(TableSource::tableId).toList();
    }

    public Optional<TableSource> table(long tableId) {
        return tables.stream().filter(t -> t.tableId() == tableId).findFirst();
    }

    /** Greatest sequence in the view (frozen generation or tables), or 0. */
    public long maxSequence() {
        long max = immutable == null ? 0 : immutable.maxSequence();
        for (TableSource table : tables) {
            max = Math.max(max, table.meta().maxSequence());
        }
        return max;
    }
}
