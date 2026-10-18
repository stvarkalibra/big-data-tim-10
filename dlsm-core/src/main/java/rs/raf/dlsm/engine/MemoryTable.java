package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A "published table" kept in memory: the result of an in-memory flush for
 * the M1 node, which has no SSTable files yet. It holds exactly the records
 * of one frozen generation and never changes, like a real SSTable (I5).
 *
 * <p>Its metadata uses the file name the table would have on disk; nothing
 * is written there. {@code sizeBytes} is the logical size of its entries.
 */
public final class MemoryTable implements TableSource {

    private final SSTableMeta meta;
    private final NavigableMap<String, InternalRecord> records;

    private MemoryTable(SSTableMeta meta, NavigableMap<String, InternalRecord> records) {
        this.meta = meta;
        this.records = records;
    }

    /** A table holding exactly the records of {@code input}, tombstones included. */
    public static MemoryTable of(long tableId, FlushInput input) {
        Objects.requireNonNull(input, "input");

        NavigableMap<String, InternalRecord> records = new TreeMap<>(Keys::compare);
        long minSequence = Long.MAX_VALUE;
        long maxSequence = 0;
        long logicalBytes = 0;
        for (Iterator<InternalRecord> it = input.records(); it.hasNext(); ) {
            InternalRecord record = it.next();
            records.put(record.key(), record);
            minSequence = Math.min(minSequence, record.sequence());
            maxSequence = Math.max(maxSequence, record.sequence());
            logicalBytes += MemTable.entryBytes(record);
        }

        SSTableMeta meta = new SSTableMeta(tableId, SSTableMeta.fileName(tableId),
                records.firstKey(), records.lastKey(), minSequence, maxSequence,
                records.size(), logicalBytes);
        return new MemoryTable(meta, Collections.unmodifiableNavigableMap(records));
    }

    @Override
    public SSTableMeta meta() {
        return meta;
    }

    @Override
    public Optional<InternalRecord> get(String key) {
        return Optional.ofNullable(records.get(key));
    }

    @Override
    public Iterator<InternalRecord> iterator(String startInclusive) {
        return records.tailMap(startInclusive, true).values().iterator();
    }
}
