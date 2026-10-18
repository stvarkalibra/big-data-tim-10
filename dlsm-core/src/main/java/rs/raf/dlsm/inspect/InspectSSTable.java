
package rs.raf.dlsm.inspect;

import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableReader;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.sstable.SparseIndex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InspectSSTable {

    private InspectSSTable() {
    }

    public static Map<String, Object> inspect(
            byte[] file, SSTableMeta expected) {

        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(expected, "expected");

        try {
            return inspectValidated(file, expected);
        } catch (SSTableValidator.ValidationException e) {
            throw e;
        } catch (IllegalArgumentException
                 | IndexOutOfBoundsException e) {

            throw new SSTableValidator.ValidationException(
                    SSTableValidator.Category.DATA_BLOCK,
                    "SSTable inspection failed: " + e.getMessage(),
                    e
            );
        }
    }

    private static Map<String, Object> inspectValidated(
            byte[] file, SSTableMeta expected) {

        SSTableReader reader =
                SSTableReader.open(file, expected);

        int footerStart =
                file.length - SSTableWriter.FOOTER_BYTES;

        ByteBuffer header = ByteBuffer.wrap(file)
                .order(ByteOrder.BIG_ENDIAN);

        int headerMagic = header.getInt(0);
        int version = header.getInt(4);
        long headerTableId = header.getLong(8);

        ByteBuffer footer = ByteBuffer.wrap(file)
                .order(ByteOrder.BIG_ENDIAN);

        long dataOffset = footer.getLong(footerStart + 16);
        long dataLength = footer.getLong(footerStart + 24);
        long indexOffset = footer.getLong(footerStart + 32);
        long indexLength = footer.getLong(footerStart + 40);
        long bloomOffset = footer.getLong(footerStart + 48);
        long bloomLength = footer.getLong(footerStart + 56);

        byte[] indexBytes = Arrays.copyOfRange(
                file,
                Math.toIntExact(indexOffset),
                Math.toIntExact(indexOffset + indexLength)
        );

        SparseIndex index =
                SparseIndex.deserialize(indexBytes);

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("format", "SST1");
        result.put("version", version);
        result.put("tableId", expected.tableId());
        result.put("file", expected.file());
        result.put("valid", true);
        result.put("headerCrcStatus", "NOT_PRESENT");
        result.put("footerCrcStatus", "VALID");
        result.put("indexCrcStatus", "VALID");
        result.put("bloomCrcStatus", "VALID");
        result.put("headerMagic",
                String.format("0x%08X", headerMagic));
        result.put("headerTableId", headerTableId);

        result.put("sizeBytes", expected.sizeBytes());
        result.put("entryCount", expected.entryCount());
        result.put("blockCount", reader.blockCount());

        result.put("minKey", expected.minKey());
        result.put("maxKey", expected.maxKey());
        result.put("minSequence", expected.minSequence());
        result.put("maxSequence", expected.maxSequence());

        Map<String, Object> regions = new LinkedHashMap<>();

        regions.put("data",
                region(dataOffset, dataLength));
        regions.put("index",
                region(indexOffset, indexLength));
        regions.put("bloom",
                region(bloomOffset, bloomLength));
        regions.put("footer",
                region(footerStart, SSTableWriter.FOOTER_BYTES));

        result.put("regions", regions);

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

            indexItem.put("firstKey", entry.firstKey());
            indexItem.put("offset", entry.offset());
            indexItem.put("length", entry.length());

            indexEntries.add(indexItem);

            byte[] blockBytes = Arrays.copyOfRange(
                    file,
                    Math.toIntExact(entry.offset()),
                    Math.toIntExact(
                            entry.offset() + entry.length())
            );

            List<BlockCodec.Record> decoded =
                    BlockCodec.decode(blockBytes);

            Map<String, Object> block =
                    new LinkedHashMap<>();

            block.put("blockNumber", blockNumber);
            block.put("offset", entry.offset());
            block.put("length", entry.length());
            block.put("firstKey", entry.firstKey());
            block.put("lastKey",
                    decoded.get(decoded.size() - 1).key());
            block.put("recordCount", decoded.size());
            block.put("crcStatus", "VALID");

            blocks.add(block);

            for (BlockCodec.Record record : decoded) {

                Map<String, Object> item =
                        new LinkedHashMap<>();

                item.put("blockNumber", blockNumber);
                item.put("key", record.key());
                item.put("sequence", record.sequence());
                item.put("kind",
                        record.tombstone() ? "DELETE" : "PUT");
                item.put("valueLength",
                        record.tombstone()
                                ? null
                                : record.value().length);

                records.add(item);
            }

            blockNumber++;
        }

        result.put("sparseIndex", indexEntries);
        result.put("blocks", blocks);
        result.put("records", records);

        return result;
    }

    private static Map<String, Object> region(
            long offset, long length) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put("offset", offset);
        result.put("length", length);

        return result;
    }
}
