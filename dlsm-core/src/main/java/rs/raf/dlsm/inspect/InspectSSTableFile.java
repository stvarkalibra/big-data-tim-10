
package rs.raf.dlsm.inspect;

import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.sstable.SparseIndex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline, read-only SSTable inspector.
 *
 * Validates the complete file and then reads the header,
 * footer, sparse index and individual data blocks.
 *
 * The entire SSTable file is never loaded into one byte array.
 *
 * The report deliberately contains one metadata entry per
 * record, so report memory is still O(number of records).
 * Values themselves are not retained in the report.
 *
 * This class does not modify or repair SSTable files.
 */
public final class InspectSSTableFile {

    private static final long MAX_INDEX_BYTES =
            256L * 1024 * 1024;

    private static final int MAX_BLOCK_BYTES =
            262_144 + 1024;

    private InspectSSTableFile() {
    }

    public static Map<String, Object> inspect(
            Path file,
            SSTableMeta expected) throws IOException {

        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(expected, "expected");

        SSTableValidator.validateFully(file, expected);

        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.READ)) {

            long fileSize = channel.size();

            if (fileSize != expected.sizeBytes()) {
                fail(
                        SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                        "SSTable size changed after validation"
                );
            }

            ByteBuffer header = ByteBuffer.wrap(
                    readExactly(
                            channel,
                            0,
                            SSTableWriter.HEADER_BYTES
                    )
            ).order(ByteOrder.BIG_ENDIAN);

            int headerMagic = header.getInt();
            int version = header.getInt();
            long headerTableId = header.getLong();

            long footerStart =
                    fileSize - SSTableWriter.FOOTER_BYTES;

            ByteBuffer footer = ByteBuffer.wrap(
                    readExactly(
                            channel,
                            footerStart,
                            SSTableWriter.FOOTER_BYTES
                    )
            ).order(ByteOrder.BIG_ENDIAN);

            footer.position(16);

            long dataOffset = footer.getLong();
            long dataLength = footer.getLong();
            long indexOffset = footer.getLong();
            long indexLength = footer.getLong();
            long bloomOffset = footer.getLong();
            long bloomLength = footer.getLong();

            if (!regionWithin(
                    indexOffset, indexLength, footerStart)
                    || indexLength > MAX_INDEX_BYTES) {

                fail(
                        SSTableValidator.Category.INDEX,
                        "Invalid sparse index region"
                );
            }

            SparseIndex index;

            try {
                index = SparseIndex.deserialize(
                        readExactly(
                                channel,
                                indexOffset,
                                Math.toIntExact(indexLength)
                        )
                );
            } catch (IllegalArgumentException e) {
                throw new SSTableValidator.ValidationException(
                        SSTableValidator.Category.INDEX,
                        "Cannot decode SSTable sparse index",
                        e
                );
            }

            Map<String, Object> report =
                    new LinkedHashMap<>();

            report.put("format", "SST1");
            report.put("version", version);
            report.put("tableId", expected.tableId());
            report.put("file", expected.file());
            report.put("valid", true);

            report.put("headerCrcStatus", "NOT_PRESENT");
            report.put("footerCrcStatus", "VALID");
            report.put("indexCrcStatus", "VALID");
            report.put("bloomCrcStatus", "VALID");

            report.put(
                    "headerMagic",
                    String.format("0x%08X", headerMagic)
            );
            report.put("headerTableId", headerTableId);

            report.put("sizeBytes", expected.sizeBytes());
            report.put("entryCount", expected.entryCount());
            report.put("blockCount", index.size());

            report.put("minKey", expected.minKey());
            report.put("maxKey", expected.maxKey());
            report.put(
                    "minSequence",
                    expected.minSequence()
            );
            report.put(
                    "maxSequence",
                    expected.maxSequence()
            );

            Map<String, Object> regions =
                    new LinkedHashMap<>();

            regions.put(
                    "data",
                    region(dataOffset, dataLength)
            );
            regions.put(
                    "index",
                    region(indexOffset, indexLength)
            );
            regions.put(
                    "bloom",
                    region(bloomOffset, bloomLength)
            );
            regions.put(
                    "footer",
                    region(
                            footerStart,
                            SSTableWriter.FOOTER_BYTES
                    )
            );

            report.put("regions", regions);

            List<Map<String, Object>> indexEntries =
                    new ArrayList<>();

            List<Map<String, Object>> blocks =
                    new ArrayList<>();

            List<Map<String, Object>> records =
                    new ArrayList<>();

            int blockNumber = 0;

            for (SparseIndex.Entry entry : index.entries()) {

                Map<String, Object> indexItem =
                        new LinkedHashMap<>();

                indexItem.put(
                        "firstKey",
                        entry.firstKey()
                );
                indexItem.put("offset", entry.offset());
                indexItem.put("length", entry.length());

                indexEntries.add(indexItem);

                if (entry.length() <= 0
                        || entry.length() > MAX_BLOCK_BYTES
                        || !regionWithin(
                        entry.offset(),
                        entry.length(),
                        indexOffset)) {

                    fail(
                            SSTableValidator.Category.DATA_BLOCK,
                            "Invalid SSTable data block location"
                    );
                }

                List<BlockCodec.Record> decoded;

                try {
                    decoded = BlockCodec.decode(
                            readExactly(
                                    channel,
                                    entry.offset(),
                                    entry.length()
                            )
                    );
                } catch (IllegalArgumentException e) {
                    throw new SSTableValidator.ValidationException(
                            SSTableValidator.Category.DATA_BLOCK,
                            "Cannot decode SSTable data block",
                            e
                    );
                }

                if (decoded.isEmpty()
                        || !decoded.get(0).key().equals(
                        entry.firstKey())) {

                    fail(
                            SSTableValidator.Category.INDEX,
                            "Sparse index does not match block"
                    );
                }

                Map<String, Object> block =
                        new LinkedHashMap<>();

                block.put("blockNumber", blockNumber);
                block.put("offset", entry.offset());
                block.put("length", entry.length());
                block.put("firstKey", entry.firstKey());
                block.put(
                        "lastKey",
                        decoded.get(decoded.size() - 1).key()
                );
                block.put("recordCount", decoded.size());
                block.put("crcStatus", "VALID");

                blocks.add(block);

                for (BlockCodec.Record record : decoded) {

                    Map<String, Object> item =
                            new LinkedHashMap<>();

                    item.put("blockNumber", blockNumber);
                    item.put("key", record.key());
                    item.put(
                            "sequence",
                            record.sequence()
                    );
                    item.put(
                            "kind",
                            record.tombstone()
                                    ? "DELETE"
                                    : "PUT"
                    );
                    item.put(
                            "valueLength",
                            record.tombstone()
                                    ? null
                                    : record.value().length
                    );

                    records.add(item);
                }

                blockNumber++;
            }

            report.put("sparseIndex", indexEntries);
            report.put("blocks", blocks);
            report.put("records", records);

            return report;
        }
    }

    private static Map<String, Object> region(
            long offset,
            long length) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put("offset", offset);
        result.put("length", length);

        return result;
    }

    private static boolean regionWithin(
            long offset,
            long length,
            long exclusiveEnd) {

        return offset >= 0
                && length > 0
                && offset <= exclusiveEnd
                && length <= exclusiveEnd - offset;
    }

    private static byte[] readExactly(
            FileChannel channel,
            long offset,
            int length) throws IOException {

        if (offset < 0
                || length < 0
                || offset > channel.size()
                || (long) length > channel.size() - offset) {

            fail(
                    SSTableValidator.Category.TRUNCATED_REGION,
                    "SSTable region is unavailable"
            );
        }

        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = offset;

        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position);

            if (count < 0) {
                fail(
                        SSTableValidator.Category.TRUNCATED_REGION,
                        "Unexpected end of SSTable"
                );
            }

            if (count == 0) {
                throw new IOException(
                        "SSTable read made no progress"
                );
            }

            position += count;
        }

        return buffer.array();
    }

    private static void fail(
            SSTableValidator.Category category,
            String message) {

        throw new SSTableValidator.ValidationException(
                category,
                message,
                null
        );
    }
}
