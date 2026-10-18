package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * K-way merge of key-ordered sources (CP1 §12, §20.2).
 *
 * <p>Each source yields records in strictly increasing key order, at most
 * one per key (a MemTable generation or an SSTable). The merge yields
 * exactly one record per key: the version with the greatest sequence among
 * all sources, regardless of which source it came from (I3). Tombstones are
 * yielded too, because compaction must keep them (I8); SCAN filters them
 * afterwards.
 *
 * <p>The heap holds at most one current record per source. All versions of
 * the smallest key are removed as one group before the winner is chosen,
 * and every source that contributed is advanced, so no key is seen twice.
 * Sources are advanced lazily, just before the next group is needed, so a
 * caller that stops early (SCAN {@code limit}) never reads further ahead.
 *
 * <p>Optional bounds: records below {@code startInclusive} are skipped, and
 * a source is no longer read once it reaches {@code endExclusive}.
 */
public final class MergingIterator implements Iterator<InternalRecord> {

    private record Head(InternalRecord record, int source) {
    }

    private final List<Iterator<InternalRecord>> sources;
    private final String startInclusive;
    private final String endExclusive;
    private final String[] lastKeys;
    private final PriorityQueue<Head> heap = new PriorityQueue<>(
            (a, b) -> Keys.compare(a.record.key(), b.record.key()));
    private final List<Integer> toAdvance = new ArrayList<>();

    private InternalRecord next;

    /** Unbounded merge, e.g. for compaction. */
    public MergingIterator(List<? extends Iterator<InternalRecord>> sources) {
        this(sources, null, null);
    }

    /**
     * @param startInclusive lower bound, or {@code null} for none
     * @param endExclusive   upper bound, never yielded, or {@code null} for none
     */
    public MergingIterator(List<? extends Iterator<InternalRecord>> sources,
                           String startInclusive, String endExclusive) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.startInclusive = startInclusive;
        this.endExclusive = endExclusive;
        this.lastKeys = new String[this.sources.size()];

        for (int i = 0; i < this.sources.size(); i++) {
            pull(i);
        }
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }

        for (int source : toAdvance) {
            pull(source);
        }
        toAdvance.clear();

        Head first = heap.poll();
        if (first == null) {
            return false;
        }

        InternalRecord winner = first.record;
        toAdvance.add(first.source);

        while (!heap.isEmpty()
                && heap.peek().record.key().equals(winner.key())) {
            Head same = heap.poll();
            toAdvance.add(same.source);
            winner = newer(winner, same.record);
        }

        next = winner;
        return true;
    }

    @Override
    public InternalRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        InternalRecord result = next;
        next = null;
        return result;
    }

    /** Puts the source's next in-range record into the heap, if any. */
    private void pull(int source) {
        Iterator<InternalRecord> it = sources.get(source);

        while (it.hasNext()) {
            InternalRecord record = Objects.requireNonNull(it.next(), "record");
            String key = record.key();

            String last = lastKeys[source];
            if (last != null && Keys.compare(last, key) >= 0) {
                throw new IllegalStateException("Source " + source
                        + " is not strictly ordered at key " + key);
            }
            lastKeys[source] = key;

            if (startInclusive != null && Keys.compare(key, startInclusive) < 0) {
                continue;
            }
            if (endExclusive != null && Keys.compare(key, endExclusive) >= 0) {
                return; // sorted source: nothing further can be in range
            }

            heap.add(new Head(record, source));
            return;
        }
    }

    private static InternalRecord newer(InternalRecord a, InternalRecord b) {
        if (a.sequence() != b.sequence()) {
            return b.sequence() > a.sequence() ? b : a;
        }
        if (!a.equals(b)) {
            // One sequence identifies one mutation; two different versions
            // with the same sequence mean the stored state is inconsistent.
            throw new IllegalStateException("Conflicting versions of key "
                    + a.key() + " at sequence " + a.sequence());
        }
        return a;
    }
}
