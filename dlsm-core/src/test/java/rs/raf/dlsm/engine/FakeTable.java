package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory stand-in for a published SSTable in engine tests. Counts calls
 * and can simulate a corrupt data block on get or during iteration.
 */
final class FakeTable implements TableSource {

    private final SSTableMeta meta;
    private final NavigableMap<String, InternalRecord> records = new TreeMap<>();

    int gets;
    int iterators;
    boolean corruptGet;
    /** Throw after yielding this many records; -1 means never. */
    int corruptAfter = -1;

    FakeTable(long tableId, InternalRecord... contents) {
        long minSeq = Long.MAX_VALUE;
        long maxSeq = 0;
        for (InternalRecord record : contents) {
            if (records.put(record.key(), record) != null) {
                throw new IllegalArgumentException("Duplicate key " + record.key());
            }
            minSeq = Math.min(minSeq, record.sequence());
            maxSeq = Math.max(maxSeq, record.sequence());
        }
        meta = new SSTableMeta(tableId, SSTableMeta.fileName(tableId),
                records.firstKey(), records.lastKey(), minSeq, maxSeq,
                records.size(), 1);
    }

    /** A table holding exactly the records of a frozen generation, like a flush. */
    static FakeTable flushOf(long tableId, ImmutableMemTable frozen) {
        List<InternalRecord> all = new ArrayList<>();
        frozen.iterator().forEachRemaining(all::add);
        return new FakeTable(tableId, all.toArray(new InternalRecord[0]));
    }

    static StorageException corruption() {
        return new StorageException(ErrorCode.STORAGE_IO, "DATA_BLOCK",
                "Data block checksum mismatch");
    }

    @Override
    public SSTableMeta meta() {
        return meta;
    }

    @Override
    public Optional<InternalRecord> get(String key) throws StorageException {
        gets++;
        if (corruptGet) {
            throw corruption();
        }
        return Optional.ofNullable(records.get(key));
    }

    @Override
    public Iterator<InternalRecord> iterator(String startInclusive) {
        iterators++;
        Iterator<InternalRecord> delegate =
                records.tailMap(startInclusive, true).values().iterator();
        return new Iterator<>() {
            int yielded;

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public InternalRecord next() {
                if (corruptAfter >= 0 && yielded >= corruptAfter) {
                    throw new UncheckedStorageException(corruption());
                }
                yielded++;
                return delegate.next();
            }
        };
    }
}
