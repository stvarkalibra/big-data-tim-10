package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Everything a flush needs from one frozen generation (CP1 §19.1 step 2):
 * its ordered records, the WAL segments that protect it and the sequence
 * that becomes the new {@code flushedThroughSequence}.
 *
 * <p>The generation never changes, so the flush may stream it without
 * holding the engine lock.
 */
public record FlushInput(ImmutableMemTable frozen) {

    public FlushInput {
        Objects.requireNonNull(frozen, "frozen");
    }

    public long memtableGeneration() {
        return frozen.generation();
    }

    /** Records in key order, tombstones included: the SSTable writer's input. */
    public Iterator<InternalRecord> records() {
        return frozen.iterator();
    }

    /** {@code expectedEntries} for the SSTable writer. */
    public int entryCount() {
        return frozen.entryCount();
    }

    /** The new {@code flushedThroughSequence} once this generation is published. */
    public long maxSequence() {
        return frozen.maxSequence();
    }

    /** Segments that may be deleted only after the covering manifest is durable. */
    public List<Long> walSegmentIds() {
        return frozen.walSegmentIds();
    }
}
