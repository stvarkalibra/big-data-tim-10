
package rs.raf.dlsm.sstable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;

public final class SSTableReader {

    private static final int HEADER_MAGIC = 0x53535431;
    private static final int FOOTER_MAGIC = 0x46545231;
    private static final int VERSION = 1;
    private static final int KEY_BYTES = 128;

    private final byte[] data;
    private final SSTableMeta meta;
    private final SparseIndex index;
    private final BloomFilter bloom;

    private final long dataOffset;
    private final long dataLength;

    private SSTableReader(
            byte[] data,
            SSTableMeta meta,
            SparseIndex index,
            BloomFilter bloom,
            long dataOffset,
            long dataLength) {

        this.data = data;
        this.meta = meta;
        this.index = index;
        this.bloom = bloom;
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
    }

    public static SSTableReader open(
            byte[] input, SSTableMeta expected) {

        if (input == null || expected == null) {
            throw new IllegalArgumentException("Null SSTable input");
        }

        byte[] data = input.clone();

        if (data.length <
                SSTableWriter.HEADER_BYTES
                        + SSTableWriter.FOOTER_BYTES) {
            throw new IllegalArgumentException("Truncated SSTable");
        }

        ByteBuffer header = ByteBuffer.wrap(
                        data, 0, SSTableWriter.HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);

        if (header.getInt() != HEADER_MAGIC ||
                header.getInt() != VERSION ||
                header.getLong() != expected.tableId()) {
            throw new IllegalArgumentException(
                    "Invalid SSTable header");
        }

        int footerStart =
                data.length - SSTableWriter.FOOTER_BYTES;

        CRC32C crc = new CRC32C();
        crc.update(data, footerStart,
                SSTableWriter.FOOTER_BYTES - 4);

        long storedCrc = Integer.toUnsignedLong(
                ByteBuffer.wrap(data, data.length - 4, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt());

        if (crc.getValue() != storedCrc) {
            throw new IllegalArgumentException(
                    "Invalid SSTable footer checksum");
        }

        ByteBuffer footer = ByteBuffer.wrap(
                        data, footerStart, SSTableWriter.FOOTER_BYTES)
                .slice()
                .order(ByteOrder.BIG_ENDIAN);

        if (footer.getInt() != FOOTER_MAGIC ||
                footer.getInt() != VERSION ||
                footer.getLong() != expected.tableId()) {
            throw new IllegalArgumentException(
                    "Invalid SSTable footer identity");
        }

        long dataOffset = footer.getLong();
        long dataLength = footer.getLong();
        long indexOffset = footer.getLong();
        long indexLength = footer.getLong();
        long bloomOffset = footer.getLong();
        long bloomLength = footer.getLong();

        long minSequence = footer.getLong();
        long maxSequence = footer.getLong();
        long entryCount = footer.getLong();

        String minKey = readFixedKey(footer);
        String maxKey = readFixedKey(footer);

        while (footer.position() <
                SSTableWriter.FOOTER_BYTES - 4) {
            if (footer.get() != 0) {
                throw new IllegalArgumentException(
                        "Invalid reserved footer bytes");
            }
        }

        if (dataOffset != SSTableWriter.HEADER_BYTES ||
                dataLength <= 0 ||
                indexLength <= 0 ||
                bloomLength <= 0 ||
                !adjacent(dataOffset, dataLength, indexOffset) ||
                !adjacent(indexOffset, indexLength, bloomOffset) ||
                !adjacent(bloomOffset, bloomLength, footerStart)) {
            throw new IllegalArgumentException(
                    "Invalid SSTable region boundaries");
        }

        if (data.length != expected.sizeBytes() ||
                entryCount != expected.entryCount() ||
                minSequence != expected.minSequence() ||
                maxSequence != expected.maxSequence() ||
                !minKey.equals(expected.minKey()) ||
                !maxKey.equals(expected.maxKey())) {
            throw new IllegalArgumentException(
                    "SSTable metadata mismatch");
        }

        byte[] indexBytes = region(data, indexOffset, indexLength);
        byte[] bloomBytes = region(data, bloomOffset, bloomLength);

        SparseIndex index = SparseIndex.deserialize(indexBytes);
        BloomFilter bloom = BloomFilter.deserialize(bloomBytes);

        if (index.isEmpty() ||
                !index.entries().get(0).firstKey().equals(minKey)) {
            throw new IllegalArgumentException(
                    "Invalid sparse index");
        }

        SSTableReader reader = new SSTableReader(
                data, expected, index, bloom, dataOffset, dataLength);

        reader.validateFully();

        return reader;
    }

    public Optional<BlockCodec.Record> get(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Null key");
        }

        if (key.compareTo(meta.minKey()) < 0 ||
                key.compareTo(meta.maxKey()) > 0) {
            return Optional.empty();
        }

        if (!bloom.mightContain(key)) {
            return Optional.empty();
        }

        SparseIndex.Entry entry = index.findBlock(key);

        if (entry == null) {
            throw new IllegalArgumentException(
                    "Missing index entry");
        }

        List<BlockCodec.Record> records = readBlock(entry);

        int low = 0;
        int high = records.size() - 1;

        while (low <= high) {
            int mid = low + (high - low) / 2;
            BlockCodec.Record record = records.get(mid);

            int comparison = record.key().compareTo(key);

            if (comparison == 0) {
                return Optional.of(record);
            }

            if (comparison < 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return Optional.empty();
    }

    public List<BlockCodec.Record> scan(String startInclusive) {
        if (startInclusive == null) {
            throw new IllegalArgumentException("Null start key");
        }

        List<BlockCodec.Record> result = new ArrayList<>();

        SparseIndex.Entry candidate = index.findBlock(startInclusive);
        int startIndex;

        if (candidate == null) {
            startIndex = 0;
        } else {
            startIndex = index.entries().indexOf(candidate);
        }

        for (int i = startIndex; i < index.size(); i++) {
            for (BlockCodec.Record record :
                    readBlock(index.entries().get(i))) {

                if (record.key().compareTo(startInclusive) >= 0) {
                    result.add(record);
                }
            }
        }

        return List.copyOf(result);
    }

    public void validateFully() {
        long expectedOffset = dataOffset;
        long count = 0;
        long minSeq = Long.MAX_VALUE;
        long maxSeq = 0;

        String firstKey = null;
        String previousKey = null;
        String lastKey = null;

        for (SparseIndex.Entry entry : index.entries()) {
            if (entry.offset() != expectedOffset) {
                throw new IllegalArgumentException(
                        "Non-contiguous SSTable blocks");
            }

            List<BlockCodec.Record> records = readBlock(entry);

            if (records.isEmpty() ||
                    !records.get(0).key().equals(entry.firstKey())) {
                throw new IllegalArgumentException(
                        "Sparse index does not match data block");
            }

            for (BlockCodec.Record record : records) {
                if (previousKey != null &&
                        previousKey.compareTo(record.key()) >= 0) {
                    throw new IllegalArgumentException(
                            "SSTable keys not strictly increasing");
                }

                if (!bloom.mightContain(record.key())) {
                    throw new IllegalArgumentException(
                            "Bloom filter false negative");
                }

                if (firstKey == null) {
                    firstKey = record.key();
                }

                lastKey = record.key();
                previousKey = record.key();

                minSeq = Math.min(minSeq, record.sequence());
                maxSeq = Math.max(maxSeq, record.sequence());
                count++;
            }

            expectedOffset += entry.length();
        }

        if (expectedOffset != dataOffset + dataLength ||
                count != meta.entryCount() ||
                minSeq != meta.minSequence() ||
                maxSeq != meta.maxSequence() ||
                !meta.minKey().equals(firstKey) ||
                !meta.maxKey().equals(lastKey)) {
            throw new IllegalArgumentException(
                    "SSTable validation failed");
        }
    }

    private List<BlockCodec.Record> readBlock(
            SparseIndex.Entry entry) {

        long start = entry.offset();
        long length = entry.length();
        long end = start + length;

        if (start < dataOffset ||
                length <= 0 ||
                end < start ||
                end > dataOffset + dataLength) {
            throw new IllegalArgumentException(
                    "Block outside data region");
        }

        return BlockCodec.decode(region(data, start, length));
    }

    private static boolean adjacent(
            long start, long length, long next) {

        return start >= 0 &&
                length > 0 &&
                start <= Long.MAX_VALUE - length &&
                start + length == next;
    }

    private static byte[] region(
            byte[] data, long offset, long length) {

        if (offset < 0 || length <= 0 ||
                offset > data.length ||
                length > data.length - offset) {
            throw new IllegalArgumentException(
                    "Invalid region");
        }

        return Arrays.copyOfRange(
                data,
                Math.toIntExact(offset),
                Math.toIntExact(offset + length));
    }

    private static String readFixedKey(ByteBuffer buffer) {
        int length = buffer.getInt();

        if (length < 1 || length > KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid footer key length");
        }

        byte[] key = new byte[KEY_BYTES];
        buffer.get(key);

        for (int i = length; i < KEY_BYTES; i++) {
            if (key[i] != 0) {
                throw new IllegalArgumentException(
                        "Invalid footer key padding");
            }
        }

        String result = new String(
                key, 0, length, StandardCharsets.US_ASCII);

        if (!result.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException(
                    "Invalid footer key");
        }

        return result;
    }

    public SSTableMeta meta() {
        return meta;
    }

    public int blockCount() {
        return index.size();
    }
}
