package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A frozen MemTable generation waiting for flush (CP1 §10). It is readable
 * by GET and SCAN and is the stable, ordered input of the SSTable writer.
 *
 * <p>Created only by {@link MemTable#freeze}. The underlying table never
 * changes after freezing, so once this object is published to other
 * threads (e.g. under the engine lock) it can be read concurrently,
 * including by the flush worker outside the engine lock.
 *
 * <p>It stays protected by its WAL segments, which may only be deleted after
 * a manifest covering this generation is published (CP1 §19).
 */
public final class ImmutableMemTable {

    private final MemTable table;
    private final List<Long> walSegmentIds;

    ImmutableMemTable(MemTable table, List<Long> walSegmentIds) {
        this.table = Objects.requireNonNull(table, "table");
        this.walSegmentIds = sortedUniqueIds(walSegmentIds);
    }

    public long generation() {
        return table.generation();
    }

    /** WAL segments protecting this generation, ascending (CP1 §24). */
    public List<Long> walSegmentIds() {
        return walSegmentIds;
    }

    public int entryCount() {
        return table.entryCount();
    }

    public long logicalBytes() {
        return table.logicalBytes();
    }

    /** First applied sequence; a frozen generation is never empty. */
    public long minSequence() {
        return table.minSequence().orElseThrow();
    }

    /** Last applied sequence: the flush high-water mark of this generation. */
    public long maxSequence() {
        return table.maxSequence().orElseThrow();
    }

    /** The key's newest version in this generation, possibly a tombstone. */
    public Optional<InternalRecord> get(String key) {
        return table.get(key);
    }

    /** Versions in key order starting at {@code startInclusive}, tombstones included. */
    public Iterator<InternalRecord> iterator(String startInclusive) {
        return table.iterator(startInclusive);
    }

    /** All versions in key order, tombstones included: the flush input. */
    public Iterator<InternalRecord> iterator() {
        return table.iterator();
    }

    private static List<Long> sortedUniqueIds(List<Long> ids) {
        Objects.requireNonNull(ids, "walSegmentIds");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException(
                    "A frozen generation needs at least one WAL segment");
        }

        List<Long> sorted = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id == null || id < 1) {
                throw new IllegalArgumentException("Invalid WAL segment id");
            }
            sorted.add(id);
        }
        if (new HashSet<>(sorted).size() != sorted.size()) {
            throw new IllegalArgumentException("Duplicate WAL segment id");
        }

        sorted.sort(Long::compare);
        return List.copyOf(sorted);
    }
}
