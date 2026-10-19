
package rs.raf.dlsm.sstable;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Converts engine records into SSTable block records.
 *
 * The adapter is lazy: it consumes only the record requested
 * by the downstream SSTable encoder.
 *
 * It does not sort, deduplicate, merge or buffer records.
 * The caller must supply strictly increasing keys.
 *
 * Tombstones and empty PUT values remain distinct.
 */
public final class SSTableRecordAdapter
        implements Iterator<BlockCodec.Record> {

    private final Iterator<InternalRecord> source;

    public SSTableRecordAdapter(
            Iterator<InternalRecord> source) {

        this.source = Objects.requireNonNull(
                source,
                "source"
        );
    }

    public static Iterator<BlockCodec.Record> adapt(
            Iterator<InternalRecord> source) {

        return new SSTableRecordAdapter(source);
    }

    @Override
    public boolean hasNext() {
        return source.hasNext();
    }

    @Override
    public BlockCodec.Record next() {
        if (!source.hasNext()) {
            throw new NoSuchElementException();
        }

        InternalRecord record = Objects.requireNonNull(
                source.next(),
                "Null internal record"
        );

        byte kind = switch (record.kind()) {
            case PUT -> BlockCodec.PUT;
            case DELETE -> BlockCodec.DELETE;
        };

        byte[] value = record.kind() == Kind.DELETE
                ? null
                : record.value();

        return new BlockCodec.Record(
                record.key(),
                record.sequence(),
                kind,
                value
        );
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException(
                "SSTable record adapter is read-only"
        );
    }
}
