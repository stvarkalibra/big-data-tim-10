
package rs.raf.dlsm.sstable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Streaming SSTable binary encoder.
 *
 * Does not create, open, close, force or rename files.
 * The caller owns the OutputStream and storage durability.
 *
 * Records are consumed once and data blocks are emitted
 * progressively. Only the current block, sparse index
 * and Bloom filter are retained in memory.
 *
 * FAILURE CONTRACT:
 * Encoding is successful only if encode() returns SSTableMeta.
 *
 * If encode() throws any exception, including IOException,
 * IllegalArgumentException or an exception from the supplied
 * iterator, the destination may contain a partial SSTable.
 *
 * Such output MUST be discarded. The caller MUST NOT validate
 * it as a finished table, rename it to the final table name
 * or publish it in the manifest.
 *
 * Encoding is not transactional and does not roll back bytes
 * already written to the caller-owned OutputStream.
 *
 * This class deliberately does not delete partial files:
 * file creation, cleanup, durability and publication belong
 * to the storage/finalization layer.
 */
public final class SSTableStreamEncoder {

    private static final int HEADER_MAGIC = 0x53535431;
    private static final int FOOTER_MAGIC = 0x46545231;
    private static final int VERSION = 1;
    private static final int FIXED_KEY_BYTES = 128;

    private SSTableStreamEncoder() {
    }

    /**
     * Writes one complete binary SSTable to the supplied stream.
     *
     * Preconditions:
     * - tableId and expectedEntries are positive;
     * - the iterator yields exactly expectedEntries records;
     * - keys are strictly increasing without duplicates;
     * - records obey BlockCodec validation rules.
     *
     * On success, returns metadata for exactly the bytes
     * emitted by this invocation.
     *
     * On failure, no metadata is returned. The caller must
     * discard ALL output from this invocation, even if the
     * stream contains a complete header or some data blocks.
     *
     * The method never closes or flushes the supplied stream.
     *
     * @throws IOException if the supplied stream fails
     * @throws IllegalArgumentException for invalid input
     * @throws RuntimeException if the record iterator or
     *                          an internal encoder operation fails
     */
    public static SSTableMeta encode(
            OutputStream output,
            long tableId,
            Iterator<BlockCodec.Record> records,
            long expectedEntries) throws IOException {

        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(records, "records");

        if (tableId <= 0 || expectedEntries <= 0) {
            throw new IllegalArgumentException(
                    "Invalid table ID or expected record count");
        }

        BloomFilter bloom = new BloomFilter(expectedEntries);
        SparseIndex index = new SparseIndex();

        long position = 0;

        ByteBuffer header = ByteBuffer.allocate(
                SSTableWriter.HEADER_BYTES
        ).order(ByteOrder.BIG_ENDIAN);

        header.putInt(HEADER_MAGIC);
        header.putInt(VERSION);
        header.putLong(tableId);

        output.write(header.array());
        position += SSTableWriter.HEADER_BYTES;

        final long dataOffset = position;

        List<BlockCodec.Record> currentBlock =
                new ArrayList<>();

        int currentPayload = 0;
        long count = 0;

        String previousKey = null;
        String minKey = null;
        String maxKey = null;

        long minSequence = Long.MAX_VALUE;
        long maxSequence = 0;

        while (records.hasNext()) {
            BlockCodec.Record record =
                    Objects.requireNonNull(
                            records.next(),
                            "Null SSTable record"
                    );

            if (previousKey != null
                    && previousKey.compareTo(record.key()) >= 0) {
                throw new IllegalArgumentException(
                        "SSTable keys must be strictly increasing");
            }

            if (count >= expectedEntries) {
                throw new IllegalArgumentException(
                        "More records than expected");
            }

            if (!currentBlock.isEmpty()
                    && !BlockCodec.fitsTarget(
                    currentPayload, record)) {

                position += emitBlock(
                        output,
                        index,
                        currentBlock,
                        position
                );

                currentBlock.clear();
                currentPayload = 0;
            }

            currentBlock.add(record);

            currentPayload = Math.addExact(
                    currentPayload,
                    BlockCodec.encodedRecordSize(record)
            );

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

            count++;
        }

        if (count != expectedEntries) {
            throw new IllegalArgumentException(
                    "Expected " + expectedEntries
                            + " records, received " + count);
        }

        if (!currentBlock.isEmpty()) {
            position += emitBlock(
                    output,
                    index,
                    currentBlock,
                    position
            );
        }

        final long dataLength = position - dataOffset;

        final long indexOffset = position;
        byte[] indexBytes = index.serialize();

        output.write(indexBytes);
        position += indexBytes.length;

        final long bloomOffset = position;
        byte[] bloomBytes = bloom.serialize();

        output.write(bloomBytes);
        position += bloomBytes.length;

        byte[] footer = footer(
                tableId,
                dataOffset,
                dataLength,
                indexOffset,
                indexBytes.length,
                bloomOffset,
                bloomBytes.length,
                minKey,
                maxKey,
                minSequence,
                maxSequence,
                count
        );

        output.write(footer);
        position += footer.length;

        // A successful return is the only indication that
        // binary encoding has completed.
        //
        // File validation, force and atomic publication are
        // separate responsibilities of the caller.
        return new SSTableMeta(
                tableId,
                SSTableMeta.fileName(tableId),
                minKey,
                maxKey,
                minSequence,
                maxSequence,
                count,
                position
        );
    }

    private static int emitBlock(
            OutputStream output,
            SparseIndex index,
            List<BlockCodec.Record> records,
            long offset) throws IOException {

        byte[] block = BlockCodec.encode(records);

        index.add(
                records.get(0).key(),
                offset,
                block.length
        );

        output.write(block);
        return block.length;
    }

    private static byte[] footer(
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

        ByteBuffer buffer = ByteBuffer.allocate(
                SSTableWriter.FOOTER_BYTES
        ).order(ByteOrder.BIG_ENDIAN);

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

        putKey(buffer, minKey);
        putKey(buffer, maxKey);

        while (buffer.position()
                < SSTableWriter.FOOTER_BYTES - Integer.BYTES) {
            buffer.put((byte) 0);
        }

        CRC32C crc = new CRC32C();

        crc.update(
                buffer.array(),
                0,
                SSTableWriter.FOOTER_BYTES - Integer.BYTES
        );

        buffer.putInt((int) crc.getValue());

        return buffer.array();
    }

    private static void putKey(
            ByteBuffer buffer,
            String key) {

        byte[] bytes = key.getBytes(
                StandardCharsets.US_ASCII);

        if (bytes.length < 1
                || bytes.length > FIXED_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid SSTable footer key");
        }

        buffer.putInt(bytes.length);
        buffer.put(bytes);
        buffer.put(
                new byte[FIXED_KEY_BYTES - bytes.length]
        );
    }
}
