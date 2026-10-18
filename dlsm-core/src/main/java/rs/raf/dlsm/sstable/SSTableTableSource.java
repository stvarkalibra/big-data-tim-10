
package rs.raf.dlsm.sstable;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.engine.TableSource;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class SSTableTableSource implements TableSource {

    private final SSTableReader reader;
    private final byte[] file;

    private SSTableTableSource(
            SSTableReader reader,
            byte[] file) {
        this.reader = Objects.requireNonNull(reader);
        this.file = file;
    }

    public static SSTableTableSource open(
            byte[] data,
            SSTableMeta expected) throws StorageException {

        if (data == null || expected == null) {
            throw new StorageException(
                    ErrorCode.STORAGE_IO,
                    "TRUNCATED_REGION",
                    "Missing SSTable data or metadata"
            );
        }

        try {
            byte[] copy = data.clone();
            SSTableReader reader =
                    SSTableReader.open(copy, expected);

            return new SSTableTableSource(reader, copy);

        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw storageFailure(
                    "SSTable open failed",
                    "DATA_BLOCK",
                    e
            );
        }
    }

    @Override
    public SSTableMeta meta() {
        return reader.meta();
    }

    @Override
    public Optional<InternalRecord> get(String key)
            throws StorageException {

        Objects.requireNonNull(key, "key");

        try {
            return reader.get(key).map(
                    SSTableTableSource::convert
            );
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw storageFailure(
                    "SSTable lookup failed",
                    "DATA_BLOCK",
                    e
            );
        }
    }

    @Override
    public Iterator<InternalRecord> iterator(
            String startInclusive) throws StorageException {

        Objects.requireNonNull(
                startInclusive, "startInclusive");

        final SSTableIterator source;

        try {
            source = new SSTableIterator(
                    file, reader.meta(), startInclusive
            );
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw storageFailure(
                    "SSTable iterator initialization failed",
                    "INDEX",
                    e
            );
        }

        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                try {
                    return source.hasNext();
                } catch (IllegalArgumentException
                         | IndexOutOfBoundsException e) {
                    throw new UncheckedStorageException(
                            storageFailure(
                                    "SSTable iteration failed",
                                    "DATA_BLOCK",
                                    e
                            )
                    );
                }
            }

            @Override
            public InternalRecord next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                try {
                    return convert(source.next());
                } catch (IllegalArgumentException
                         | IndexOutOfBoundsException e) {
                    throw new UncheckedStorageException(
                            storageFailure(
                                    "SSTable iteration failed",
                                    "DATA_BLOCK",
                                    e
                            )
                    );
                }
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException(
                        "SSTable is immutable"
                );
            }
        };
    }

    private static InternalRecord convert(
            BlockCodec.Record record) {

        Kind kind = Kind.fromCode(record.kind());

        return new InternalRecord(
                record.key(),
                record.sequence(),
                kind,
                record.value()
        );
    }

    private static StorageException storageFailure(
            String message,
            String category,
            Exception cause) {

        return new StorageException(
                ErrorCode.STORAGE_IO,
                category,
                message,
                cause
        );
    }
}
