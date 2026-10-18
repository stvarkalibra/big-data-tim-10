
package rs.raf.dlsm.sstable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32C;

public final class SSTableWriter {

    public static final int HEADER_BYTES = 16;
    public static final int FOOTER_BYTES = 384;

    private static final int HEADER_MAGIC = 0x53535431;
    private static final int FOOTER_MAGIC = 0x46545231;
    private static final int VERSION = 1;
    private static final int KEY_FIELD_BYTES = 128;

    private SSTableWriter() {
    }

    public record Result(SSTableMeta meta, byte[] bytes) {

        public Result {
            if (meta == null || bytes == null) {
                throw new IllegalArgumentException("Null result");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public static Result build(
            long tableId,
            Iterator<BlockCodec.Record> records,
            long expectedEntries) {

        if (tableId <= 0 || records == null || expectedEntries <= 0) {
            throw new IllegalArgumentException(
                    "Invalid builder arguments");
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);

        header.putInt(HEADER_MAGIC);
        header.putInt(VERSION);
        header.putLong(tableId);
        output.writeBytes(header.array());

        SparseIndex index = new SparseIndex();
        BloomFilter bloom = new BloomFilter(expectedEntries);

        List<BlockCodec.Record> currentBlock =
                new ArrayList<>();

        int currentPayloadBytes = 0;

        String minKey = null;
        String maxKey = null;
        String previousKey = null;

        long minSequence = Long.MAX_VALUE;
        long maxSequence = 0;
        long entryCount = 0;

        long dataOffset = output.size();

        while (records.hasNext()) {
            BlockCodec.Record record = records.next();

            if (record == null) {
                throw new IllegalArgumentException("Null record");
            }

            if (previousKey != null
                    && previousKey.compareTo(record.key()) >= 0) {
                throw new IllegalArgumentException(
                        "Duplicate or unsorted SSTable key");
            }

            if (entryCount >= expectedEntries) {
                throw new IllegalArgumentException(
                        "More records than expected");
            }

            if (!currentBlock.isEmpty()
                    && !BlockCodec.fitsTarget(
                    currentPayloadBytes, record)) {

                writeBlock(output, index, currentBlock);
                currentBlock.clear();
                currentPayloadBytes = 0;
            }

            currentBlock.add(record);

            currentPayloadBytes +=
                    BlockCodec.encodedRecordSize(record);

            bloom.add(record.key());

            if (minKey == null) {
                minKey = record.key();
            }

            maxKey = record.key();
            previousKey = record.key();

            minSequence = Math.min(
                    minSequence, record.sequence());

            maxSequence = Math.max(
                    maxSequence, record.sequence());

            entryCount++;
        }

        if (entryCount != expectedEntries) {
            throw new IllegalArgumentException(
                    "Expected " + expectedEntries
                            + " records but received "
                            + entryCount);
        }

        if (!currentBlock.isEmpty()) {
            writeBlock(output, index, currentBlock);
        }

        long dataLength = output.size() - dataOffset;

        long indexOffset = output.size();
        byte[] indexBytes = index.serialize();
        output.writeBytes(indexBytes);
        long indexLength = indexBytes.length;

        long bloomOffset = output.size();
        byte[] bloomBytes = bloom.serialize();
        output.writeBytes(bloomBytes);
        long bloomLength = bloomBytes.length;

        byte[] footer = createFooter(
                tableId,
                dataOffset,
                dataLength,
                indexOffset,
                indexLength,
                bloomOffset,
                bloomLength,
                minKey,
                maxKey,
                minSequence,
                maxSequence,
                entryCount
        );

        output.writeBytes(footer);

        byte[] resultBytes = output.toByteArray();

        SSTableMeta meta = new SSTableMeta(
                tableId,
                SSTableMeta.fileName(tableId),
                minKey,
                maxKey,
                minSequence,
                maxSequence,
                entryCount,
                resultBytes.length
        );

        // Mandatory writer self-check:
        // Parse and fully validate the produced binary SSTable
        // using the independent production reader.
        try {
            SSTableReader.open(resultBytes, meta);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "SSTable writer self-check failed"
                            + " for table " + tableId,
                    e
            );
        }

        return new Result(meta, resultBytes);
    }

    private static void writeBlock(
            ByteArrayOutputStream output,
            SparseIndex index,
            List<BlockCodec.Record> records) {

        byte[] block = BlockCodec.encode(records);

        index.add(
                records.get(0).key(),
                output.size(),
                block.length
        );

        output.writeBytes(block);
    }

    private static byte[] createFooter(
            long tableId,
            long dataOffset,
            long dataLength,
            long indexOffset,
            long indexLength,
            long bloomOffset,
            long bloomLength,
            String minKey,
            String maxKey,
            long minSequence,
            long maxSequence,
            long entryCount) {

        ByteBuffer buffer = ByteBuffer.allocate(FOOTER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(FOOTER_MAGIC);
        buffer.putInt(VERSION);
        buffer.putLong(tableId);

        buffer.putLong(dataOffset);
        buffer.putLong(dataLength);

        buffer.putLong(indexOffset);
        buffer.putLong(indexLength);

        buffer.putLong(bloomOffset);
        buffer.putLong(bloomLength);

        buffer.putLong(minSequence);
        buffer.putLong(maxSequence);
        buffer.putLong(entryCount);

        putFixedKey(buffer, minKey);
        putFixedKey(buffer, maxKey);

        while (buffer.position() < FOOTER_BYTES - 4) {
            buffer.put((byte) 0);
        }

        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, FOOTER_BYTES - 4);

        buffer.putInt((int) crc.getValue());

        return buffer.array();
    }

    private static void putFixedKey(
            ByteBuffer buffer,
            String key) {

        byte[] bytes =
                key.getBytes(StandardCharsets.US_ASCII);

        if (bytes.length < 1
                || bytes.length > KEY_FIELD_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid footer key");
        }

        buffer.putInt(bytes.length);
        buffer.put(bytes);
        buffer.put(new byte[KEY_FIELD_BYTES - bytes.length]);
    }
}
