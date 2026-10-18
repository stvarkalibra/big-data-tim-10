
package rs.raf.dlsm.sstable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

public final class SSTableValidator {

    private static final int HEADER_MAGIC = 0x53535431;
    private static final int FOOTER_MAGIC = 0x46545231;
    private static final int VERSION = 1;
    private static final int KEY_BYTES = 128;
    private static final int MAX_BLOCK_BYTES = 262_144 + 1024;
    private static final long MAX_INDEX_BYTES = 256L * 1024 * 1024;
    private static final long MAX_BLOOM_BYTES = 256L * 1024 * 1024;

    public enum Category {
        HEADER,
        DATA_BLOCK,
        INDEX,
        BLOOM,
        FOOTER,
        TRUNCATED_REGION,
        MANIFEST_TABLE_MISMATCH
    }

    public static final class ValidationException
            extends IllegalArgumentException {

        private final Category category;

        public ValidationException(
                Category category, String message, Throwable cause) {
            super(message, cause);
            this.category = Objects.requireNonNull(category);
        }

        public Category category() {
            return category;
        }
    }

    private record Layout(
            long dataOffset,
            long dataLength,
            long indexOffset,
            long indexLength,
            long bloomOffset,
            long bloomLength
    ) {
    }

    private SSTableValidator() {
    }

    public static void validateFully(
            Path file, SSTableMeta expected) {

        if (expected == null) {
            fail(Category.MANIFEST_TABLE_MISMATCH,
                    "Missing expected SSTable metadata");
        }

        if (file == null) {
            fail(Category.TRUNCATED_REGION,
                    "Missing SSTable path");
        }

        try (FileChannel channel =
                     FileChannel.open(file, StandardOpenOption.READ)) {

            long fileSize = channel.size();

            if (fileSize < SSTableWriter.HEADER_BYTES
                    + SSTableWriter.FOOTER_BYTES) {
                fail(Category.TRUNCATED_REGION,
                        "SSTable is truncated");
            }

            if (fileSize != expected.sizeBytes()) {
                fail(Category.MANIFEST_TABLE_MISMATCH,
                        "SSTable size differs from manifest");
            }

            validateHeader(channel, expected);

            Layout layout = validateFooter(
                    channel, fileSize, expected);

            SparseIndex index;
            BloomFilter bloom;

            try {
                index = SparseIndex.deserialize(
                        readRegion(channel,
                                layout.indexOffset(),
                                layout.indexLength(),
                                MAX_INDEX_BYTES,
                                Category.INDEX));
            } catch (ValidationException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ValidationException(
                        Category.INDEX,
                        "Invalid sparse index", e);
            }

            try {
                bloom = BloomFilter.deserialize(
                        readRegion(channel,
                                layout.bloomOffset(),
                                layout.bloomLength(),
                                MAX_BLOOM_BYTES,
                                Category.BLOOM));
            } catch (ValidationException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ValidationException(
                        Category.BLOOM,
                        "Invalid Bloom filter", e);
            }

            validateBlocks(
                    channel, expected, layout, index, bloom);

        } catch (ValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new ValidationException(
                    Category.TRUNCATED_REGION,
                    "Cannot read SSTable: " + e.getMessage(), e);
        }
    }

    public static boolean isValid(
            Path file, SSTableMeta expected) {
        try {
            validateFully(file, expected);
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    private static void validateHeader(
            FileChannel channel, SSTableMeta expected)
            throws IOException {

        ByteBuffer header = ByteBuffer.wrap(
                        readExactly(channel, 0,
                                SSTableWriter.HEADER_BYTES))
                .order(ByteOrder.BIG_ENDIAN);

        if (header.getInt() != HEADER_MAGIC
                || header.getInt() != VERSION
                || header.getLong() != expected.tableId()) {
            fail(Category.HEADER, "Invalid SSTable header");
        }
    }

    private static Layout validateFooter(
            FileChannel channel,
            long fileSize,
            SSTableMeta expected) throws IOException {

        long footerStart =
                fileSize - SSTableWriter.FOOTER_BYTES;

        byte[] bytes = readExactly(
                channel, footerStart, SSTableWriter.FOOTER_BYTES);

        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);

        long storedCrc = Integer.toUnsignedLong(
                ByteBuffer.wrap(bytes, bytes.length - 4, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt());

        if (crc.getValue() != storedCrc) {
            fail(Category.FOOTER, "Footer CRC mismatch");
        }

        ByteBuffer footer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN);

        if (footer.getInt() != FOOTER_MAGIC
                || footer.getInt() != VERSION
                || footer.getLong() != expected.tableId()) {
            fail(Category.FOOTER, "Invalid footer identity");
        }

        long dataOffset = footer.getLong();
        long dataLength = footer.getLong();
        long indexOffset = footer.getLong();
        long indexLength = footer.getLong();
        long bloomOffset = footer.getLong();
        long bloomLength = footer.getLong();

        long minSeq = footer.getLong();
        long maxSeq = footer.getLong();
        long entryCount = footer.getLong();

        String minKey = readKey(footer);
        String maxKey = readKey(footer);

        while (footer.position()
                < SSTableWriter.FOOTER_BYTES - 4) {
            if (footer.get() != 0) {
                fail(Category.FOOTER,
                        "Invalid reserved footer bytes");
            }
        }

        if (minSeq != expected.minSequence()
                || maxSeq != expected.maxSequence()
                || entryCount != expected.entryCount()
                || !minKey.equals(expected.minKey())
                || !maxKey.equals(expected.maxKey())) {
            fail(Category.MANIFEST_TABLE_MISMATCH,
                    "Footer differs from manifest metadata");
        }

        if (dataOffset != SSTableWriter.HEADER_BYTES
                || !adjacent(dataOffset, dataLength, indexOffset)
                || !adjacent(indexOffset, indexLength, bloomOffset)
                || !adjacent(bloomOffset, bloomLength, footerStart)) {
            fail(Category.TRUNCATED_REGION,
                    "Invalid SSTable region boundaries");
        }

        return new Layout(
                dataOffset, dataLength,
                indexOffset, indexLength,
                bloomOffset, bloomLength);
    }

    private static void validateBlocks(
            FileChannel channel,
            SSTableMeta expected,
            Layout layout,
            SparseIndex index,
            BloomFilter bloom) throws IOException {

        if (index.isEmpty()) {
            fail(Category.INDEX, "Empty sparse index");
        }

        long nextOffset = layout.dataOffset();
        long count = 0;
        long minSeq = Long.MAX_VALUE;
        long maxSeq = 0;

        String firstKey = null;
        String previousKey = null;
        String lastKey = null;

        for (SparseIndex.Entry entry : index.entries()) {
            if (entry.offset() != nextOffset) {
                fail(Category.INDEX,
                        "Non-contiguous or misordered index blocks");
            }

            byte[] block = readRegion(
                    channel,
                    entry.offset(),
                    entry.length(),
                    MAX_BLOCK_BYTES,
                    Category.DATA_BLOCK);

            List<BlockCodec.Record> records;

            try {
                records = BlockCodec.decode(block);
            } catch (RuntimeException e) {
                throw new ValidationException(
                        Category.DATA_BLOCK,
                        "Invalid data block at offset "
                                + entry.offset(), e);
            }

            if (records.isEmpty()
                    || !records.get(0).key().equals(entry.firstKey())) {
                fail(Category.INDEX,
                        "Sparse index does not match data block");
            }

            for (BlockCodec.Record record : records) {
                if (previousKey != null
                        && previousKey.compareTo(record.key()) >= 0) {
                    fail(Category.DATA_BLOCK,
                            "Keys are not strictly increasing");
                }

                if (!bloom.mightContain(record.key())) {
                    fail(Category.BLOOM,
                            "Bloom false negative for stored key");
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

            nextOffset += entry.length();
        }

        if (nextOffset != layout.dataOffset() + layout.dataLength()) {
            fail(Category.INDEX,
                    "Data blocks do not cover data region");
        }

        if (count != expected.entryCount()
                || minSeq != expected.minSequence()
                || maxSeq != expected.maxSequence()
                || !Objects.equals(firstKey, expected.minKey())
                || !Objects.equals(lastKey, expected.maxKey())) {
            fail(Category.MANIFEST_TABLE_MISMATCH,
                    "Data content differs from manifest metadata");
        }
    }

    private static byte[] readRegion(
            FileChannel channel,
            long offset,
            long length,
            long maxLength,
            Category category) throws IOException {

        if (offset < 0 || length <= 0
                || length > maxLength
                || offset > channel.size()
                || length > channel.size() - offset) {
            fail(category, "Invalid region offset or length");
        }

        return readExactly(
                channel, offset, Math.toIntExact(length));
    }

    private static byte[] readExactly(
            FileChannel channel, long offset, int length)
            throws IOException {

        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = offset;

        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);

            if (read < 0) {
                fail(Category.TRUNCATED_REGION,
                        "Unexpected end of SSTable");
            }

            if (read == 0) {
                throw new IOException("Read made no progress");
            }

            position += read;
        }

        return buffer.array();
    }

    private static String readKey(ByteBuffer buffer) {
        int length = buffer.getInt();

        if (length < 1 || length > KEY_BYTES) {
            fail(Category.FOOTER, "Invalid footer key length");
        }

        byte[] padded = new byte[KEY_BYTES];
        buffer.get(padded);

        for (int i = length; i < KEY_BYTES; i++) {
            if (padded[i] != 0) {
                fail(Category.FOOTER,
                        "Invalid footer key padding");
            }
        }

        String key = new String(
                padded, 0, length, StandardCharsets.US_ASCII);

        if (!key.matches("[A-Za-z0-9._~-]+")) {
            fail(Category.FOOTER, "Invalid footer key");
        }

        return key;
    }

    private static boolean adjacent(
            long start, long length, long next) {

        return start >= 0
                && length > 0
                && start <= Long.MAX_VALUE - length
                && start + length == next;
    }

    private static void fail(
            Category category, String message) {

        throw new ValidationException(category, message, null);
    }
}
