
package rs.raf.dlsm.inspect;

import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.SSTableIterator;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableReader;
import rs.raf.dlsm.sstable.SSTableValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InspectSSTable {

    private InspectSSTable() {
    }

    public static Map<String, Object> inspect(
            byte[] file, SSTableMeta expected) {

        SSTableValidator.validateFully(file, expected);
        SSTableReader reader = SSTableReader.open(file, expected);

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("tableId", expected.tableId());
        result.put("file", expected.file());
        result.put("valid", true);
        result.put("sizeBytes", expected.sizeBytes());
        result.put("entryCount", expected.entryCount());
        result.put("blockCount", reader.blockCount());
        result.put("minKey", expected.minKey());
        result.put("maxKey", expected.maxKey());
        result.put("minSequence", expected.minSequence());
        result.put("maxSequence", expected.maxSequence());

        List<Map<String, Object>> records = new ArrayList<>();

        try (SSTableIterator iterator =
                     new SSTableIterator(file, expected, expected.minKey())) {

            while (iterator.hasNext()) {
                BlockCodec.Record record = iterator.next();

                Map<String, Object> item = new LinkedHashMap<>();

                item.put("key", record.key());
                item.put("sequence", record.sequence());
                item.put("kind", record.tombstone()
                        ? "DELETE" : "PUT");
                item.put("valueLength", record.tombstone()
                        ? null : record.value().length);

                records.add(item);
            }
        }

        result.put("records", records);

        return result;
    }
}
