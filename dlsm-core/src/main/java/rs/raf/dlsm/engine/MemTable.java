package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;

import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * One in-memory generation of key versions, ordered by key (CP1 §10).
 *
 * <p>Only the newest version of each key is kept. Records must arrive in
 * strictly increasing sequence order (the WAL order), which both guarantees
 * that a newer version always replaces an older one and catches ordering
 * bugs early (I3). Tombstones are stored like any other version (I8).
 *
 * <p>Size uses the standardized logical formula
 * {@code 32 + keyBytes + valueBytes}, with 0 value bytes for tombstones and
 * empty values. It is a grading measure, not real heap usage.
 *
 * <p>{@code minSequence}/{@code maxSequence} describe the mutations applied
 * to this generation, including versions later replaced inside it, so the
 * range matches the WAL that protects the generation.
 *
 * <p>Not thread-safe: guarded by the engine lock (CP1 §13.1).
 */
public final class MemTable {

    public static final int ENTRY_OVERHEAD_BYTES = 32;

    private final long memtableGeneration;
    private final long maxBytes;
    private final NavigableMap<String, InternalRecord> records =
            new TreeMap<>(Keys::compare);

    private long logicalBytes;
    private long minSequence;
    private long maxSequence;

    /**
     * @param maxBytes {@code MEMTABLE_MAX_BYTES}; reaching it requests rotation
     */
    public MemTable(long memtableGeneration, long maxBytes) {
        if (memtableGeneration < 1) {
            throw new IllegalArgumentException("Generation must be positive");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.memtableGeneration = memtableGeneration;
        this.maxBytes = maxBytes;
    }

    /** Logical size of one stored version (CP1 §10). */
    public static long entryBytes(InternalRecord record) {
        // Keys are ASCII, so the character count equals the byte count.
        return ENTRY_OVERHEAD_BYTES + record.key().length() + record.valueLength();
    }

    /**
     * Stores a version, replacing the key's previous version if any.
     *
     * @throws IllegalStateException if the sequence does not increase;
     *                               the table is then left unchanged
     */
    public void apply(InternalRecord record) {
        Objects.requireNonNull(record, "record");

        if (!isEmpty() && record.sequence() <= maxSequence) {
            throw new IllegalStateException("Sequence " + record.sequence()
                    + " does not follow " + maxSequence);
        }

        InternalRecord previous = records.put(record.key(), record);
        if (previous != null) {
            logicalBytes -= entryBytes(previous);
        }
        logicalBytes = Math.addExact(logicalBytes, entryBytes(record));

        if (minSequence == 0) {
            minSequence = record.sequence();
        }
        maxSequence = record.sequence();
    }

    /** The key's newest version in this generation, possibly a tombstone. */
    public Optional<InternalRecord> get(String key) {
        return Optional.ofNullable(records.get(key));
    }

    /** Versions in key order starting at {@code startInclusive}, tombstones included. */
    public Iterator<InternalRecord> iterator(String startInclusive) {
        return Collections.unmodifiableCollection(
                records.tailMap(startInclusive, true).values()).iterator();
    }

    /** All versions in key order, tombstones included. */
    public Iterator<InternalRecord> iterator() {
        return Collections.unmodifiableCollection(records.values()).iterator();
    }

    public long generation() {
        return memtableGeneration;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** Stored versions, including tombstones and empty values (CP1 §24). */
    public int entryCount() {
        return records.size();
    }

    public long logicalBytes() {
        return logicalBytes;
    }

    public boolean isEmpty() {
        // A record cannot be removed, so "never applied" equals "empty".
        return maxSequence == 0;
    }

    /** First applied sequence; empty for an empty table. */
    public OptionalLong minSequence() {
        return isEmpty() ? OptionalLong.empty() : OptionalLong.of(minSequence);
    }

    /** Last applied sequence; empty for an empty table. */
    public OptionalLong maxSequence() {
        return isEmpty() ? OptionalLong.empty() : OptionalLong.of(maxSequence);
    }

    /**
     * True once the logical size reaches {@code MEMTABLE_MAX_BYTES}. The
     * mutation that crossed the limit still completes; the next one must
     * wait for rotation (CP1 §10).
     */
    public boolean rotationRequired() {
        return logicalBytes >= maxBytes;
    }
}
