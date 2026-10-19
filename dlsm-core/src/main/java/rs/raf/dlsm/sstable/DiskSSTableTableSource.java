
package rs.raf.dlsm.sstable;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Kind;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.engine.TableSource;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class DiskSSTableTableSource implements TableSource {

    private static final int MAX_BLOCK_BYTES = 262_144 + 1024;
    private static final long MAX_REGION_BYTES = 256L * 1024 * 1024;

    private final Path file;
    private final SSTableMeta meta;
    private final SparseIndex index;
    private final BloomFilter bloom;
    private final StorageStats stats;

    private DiskSSTableTableSource(
            Path file,
            SSTableMeta meta,
            SparseIndex index,
            BloomFilter bloom,
            StorageStats stats) {

        this.file = Objects.requireNonNull(file);
        this.meta = Objects.requireNonNull(meta);
        this.index = Objects.requireNonNull(index);
        this.bloom = Objects.requireNonNull(bloom);
        this.stats = Objects.requireNonNull(stats);
    }

    public static DiskSSTableTableSource open(
            Path file,
            SSTableMeta expected) throws StorageException {

        return open(file, expected, new StorageStats());
    }

    public static DiskSSTableTableSource open(
            Path file,
            SSTableMeta expected,
            StorageStats stats) throws StorageException {

        if (file == null || expected == null || stats == null) {
            throw failure(
                    "MANIFEST_TABLE_MISMATCH",
                    "Missing SSTable path, metadata or statistics",
                    null
            );
        }

        try {
            SSTableValidator.validateFully(file, expected);
        } catch (SSTableValidator.ValidationException e) {
            throw failure(
                    e.category().name(),
                    "SSTable validation failed",
                    e
            );
        }

        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.READ)) {

            long size = channel.size();
            long footerStart = size - SSTableWriter.FOOTER_BYTES;

            ByteBuffer footer = ByteBuffer.wrap(
                    read(channel, footerStart,
                            SSTableWriter.FOOTER_BYTES,
                            "FOOTER")
            ).order(ByteOrder.BIG_ENDIAN);

            footer.position(32);

            long indexOffset = footer.getLong();
            long indexLength = footer.getLong();
            long bloomOffset = footer.getLong();
            long bloomLength = footer.getLong();

            SparseIndex index;

            try {
                index = SparseIndex.deserialize(
                        readRegion(channel,
                                indexOffset,
                                indexLength,
                                "INDEX"));
            } catch (IllegalArgumentException e) {
                throw failure(
                        "INDEX",
                        "Cannot decode SSTable index",
                        e
                );
            }

            BloomFilter bloom;

            try {
                bloom = BloomFilter.deserialize(
                        readRegion(channel,
                                bloomOffset,
                                bloomLength,
                                "BLOOM"));
            } catch (IllegalArgumentException e) {
                throw failure(
                        "BLOOM",
                        "Cannot decode SSTable Bloom filter",
                        e
                );
            }

            return new DiskSSTableTableSource(
                    file, expected, index, bloom, stats);

        } catch (IOException e) {
            throw failure(
                    "STORAGE_IO",
                    "Cannot open SSTable for reading",
                    e
            );
        }
    }

    @Override
    public SSTableMeta meta() {
        return meta;
    }

    @Override
    public Optional<InternalRecord> get(String key)
            throws StorageException {

        Objects.requireNonNull(key, "key");

        if (key.compareTo(meta.minKey()) < 0
                || key.compareTo(meta.maxKey()) > 0) {
            return Optional.empty();
        }

        if (!bloom.mightContain(key)) {
            stats.increment("bloomNegativeSkips");
            return Optional.empty();
        }

        stats.increment("bloomPositiveProbes");

        SparseIndex.Entry entry = index.findBlock(key);

        if (entry == null) {
            return Optional.empty();
        }

        List<BlockCodec.Record> records =
                loadBlock(entry, false);

        for (BlockCodec.Record record : records) {
            int comparison = record.key().compareTo(key);

            if (comparison == 0) {
                return Optional.of(convert(record));
            }

            if (comparison > 0) {
                break;
            }
        }

        return Optional.empty();
    }

    /**
     * Returns records starting from startInclusive.
     *
     * The first candidate block is located using a binary
     * search over sparse-index first keys: O(log B),
     * where B is the number of data blocks.
     *
     * No data block before the selected candidate is read.
     */
    @Override
    public Iterator<InternalRecord> iterator(
            String startInclusive) throws StorageException {

        Objects.requireNonNull(startInclusive, "startInclusive");

        List<SparseIndex.Entry> entries = index.entries();

        if (entries.isEmpty()) {
            throw failure(
                    "INDEX",
                    "SSTable index is empty",
                    null
            );
        }

        if (startInclusive.compareTo(meta.maxKey()) > 0) {
            return List.<InternalRecord>of().iterator();
        }

        // The greatest index firstKey <= startInclusive,
        // or block zero if startInclusive precedes all blocks.
        int firstBlock = findStartBlock(
                entries, startInclusive);

        return new Iterator<>() {

            private int blockPosition = firstBlock;
            private int recordPosition;
            private List<BlockCodec.Record> current = List.of();
            private InternalRecord next;
            private boolean searchingStart = true;

            private void advance() {
                if (next != null) {
                    return;
                }

                while (true) {
                    if (recordPosition < current.size()) {
                        BlockCodec.Record record =
                                current.get(recordPosition++);

                        if (searchingStart
                                && record.key().compareTo(
                                startInclusive) < 0) {
                            continue;
                        }

                        searchingStart = false;
                        next = convert(record);
                        return;
                    }

                    if (blockPosition >= entries.size()) {
                        return;
                    }

                    try {
                        current = loadBlock(
                                entries.get(blockPosition++),
                                true
                        );
                    } catch (StorageException e) {
                        throw new UncheckedStorageException(e);
                    }

                    recordPosition = 0;
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return next != null;
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
        };
    }

    /**
     * Finds the position of the rightmost sparse-index entry
     * whose firstKey is <= key.
     *
     * If key precedes the first entry, returns zero.
     *
     * Assumes the validated SSTable index is strictly sorted.
     */
    private static int findStartBlock(
            List<SparseIndex.Entry> entries,
            String key) {

        int low = 0;
        int high = entries.size() - 1;
        int result = 0;

        while (low <= high) {
            int middle = low + (high - low) / 2;

            int comparison =
                    entries.get(middle)
                            .firstKey()
                            .compareTo(key);

            if (comparison <= 0) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return result;
    }

    private List<BlockCodec.Record> loadBlock(
            SparseIndex.Entry entry,
            boolean rangeScan) throws StorageException {

        if (entry.length() <= 0
                || entry.length() > MAX_BLOCK_BYTES) {
            throw failure(
                    "DATA_BLOCK",
                    "Invalid SSTable block size",
                    null
            );
        }

        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.READ)) {

            byte[] bytes = read(
                    channel,
                    entry.offset(),
                    entry.length(),
                    "DATA_BLOCK"
            );

            stats.increment("sstableDataBlocksRead");
            stats.add("sstableDataBytesRead", bytes.length);

            if (rangeScan) {
                stats.increment("rangeScanBlocksRead");
            }

            List<BlockCodec.Record> records;

            try {
                records = BlockCodec.decode(bytes);
            } catch (IllegalArgumentException e) {
                throw failure(
                        "DATA_BLOCK",
                        "Invalid SSTable data block",
                        e
                );
            }

            if (records.isEmpty()
                    || !records.get(0).key()
                    .equals(entry.firstKey())) {
                throw failure(
                        "INDEX",
                        "SSTable index does not match block",
                        null
                );
            }

            return records;

        } catch (IOException e) {
            throw failure(
                    "STORAGE_IO",
                    "Cannot read SSTable data block",
                    e
            );
        }
    }

    private static byte[] readRegion(
            FileChannel channel,
            long offset,
            long length,
            String category)
            throws StorageException, IOException {

        if (length <= 0
                || length > MAX_REGION_BYTES
                || offset < 0
                || offset > channel.size()
                || length > channel.size() - offset) {
            throw failure(
                    category,
                    "Invalid SSTable region",
                    null
            );
        }

        return read(
                channel,
                offset,
                Math.toIntExact(length),
                category
        );
    }

    private static byte[] read(
            FileChannel channel,
            long offset,
            int length,
            String category)
            throws StorageException, IOException {

        if (offset < 0
                || length < 0
                || offset > channel.size()
                || (long) length > channel.size() - offset) {
            throw failure(
                    category,
                    "Truncated SSTable region",
                    null
            );
        }

        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = offset;

        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position);

            if (count <= 0) {
                throw failure(
                        category,
                        "Incomplete SSTable read",
                        null
                );
            }

            position += count;
        }

        return buffer.array();
    }

    private static InternalRecord convert(
            BlockCodec.Record record) {

        return new InternalRecord(
                record.key(),
                record.sequence(),
                Kind.fromCode(record.kind()),
                record.value()
        );
    }

    private static StorageException failure(
            String category,
            String message,
            Throwable cause) {

        return new StorageException(
                ErrorCode.STORAGE_IO,
                category,
                message,
                cause
        );
    }
}
