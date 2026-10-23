package rs.raf.dlsm.manifest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Manifest(
        int formatVersion,
        long generation,
        long nextTableId,
        long flushedThroughSequence,
        List<SSTableMeta> sstables
) {

    public static final int FORMAT_VERSION = 1;
    public static final String CATEGORY = "MANIFEST_INVALID";

    private static final Set<String> FIELDS = Set.of(
            "formatVersion", "generation", "nextTableId", "flushedThroughSequence", "sstables");
    private static final Set<String> TABLE_FIELDS = Set.of(
            "tableId", "file", "minKey", "maxKey", "minSequence", "maxSequence", "entryCount", "sizeBytes");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Manifest {
        sstables = List.copyOf(Objects.requireNonNull(sstables, "sstables"));
    }

    public static Manifest initial() {
        return new Manifest(FORMAT_VERSION, 0, 1, 0, List.of());
    }

    public byte[] toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("formatVersion", formatVersion);
        root.put("generation", generation);
        root.put("nextTableId", nextTableId);
        root.put("flushedThroughSequence", flushedThroughSequence);
        ArrayNode tables = root.putArray("sstables");
        for (SSTableMeta meta : sstables) {
            ObjectNode table = tables.addObject();
            table.put("tableId", meta.tableId());
            table.put("file", meta.file());
            table.put("minKey", meta.minKey());
            table.put("maxKey", meta.maxKey());
            table.put("minSequence", meta.minSequence());
            table.put("maxSequence", meta.maxSequence());
            table.put("entryCount", meta.entryCount());
            table.put("sizeBytes", meta.sizeBytes());
        }
        try {
            return JSON.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Manifest serialization failed", e);
        }
    }

    public static Manifest parse(byte[] json) throws StorageException {
        Objects.requireNonNull(json, "json");

        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw invalid("MANIFEST.json is not valid JSON", e);
        } catch (IOException e) {
            throw invalid("MANIFEST.json could not be parsed", e);
        }

        if (root == null || !root.isObject()) {
            throw invalid("MANIFEST.json is not a JSON object", null);
        }
        requireExactFields(root, FIELDS, "MANIFEST.json");

        long version = requireLong(root, "formatVersion", "MANIFEST.json");
        if (version != FORMAT_VERSION) {
            throw invalid("Unsupported MANIFEST.json formatVersion " + version, null);
        }

        long generation = requireLong(root, "generation", "MANIFEST.json");
        long nextTableId = requireLong(root, "nextTableId", "MANIFEST.json");
        long flushedThrough = requireLong(root, "flushedThroughSequence", "MANIFEST.json");

        JsonNode tablesNode = root.get("sstables");
        if (!tablesNode.isArray()) {
            throw invalid("MANIFEST.json sstables is not an array", null);
        }

        List<SSTableMeta> tables = new ArrayList<>(tablesNode.size());
        for (int i = 0; i < tablesNode.size(); i++) {
            tables.add(parseTable(tablesNode.get(i), "sstables[" + i + "]"));
        }

        Manifest manifest = new Manifest((int) version, generation, nextTableId, flushedThrough, tables);
        ManifestValidator.validate(manifest);
        return manifest;
    }

    private static SSTableMeta parseTable(JsonNode node, String where) throws StorageException {
        if (node == null || !node.isObject()) {
            throw invalid(where + " is not a JSON object", null);
        }
        requireExactFields(node, TABLE_FIELDS, where);

        long tableId = requireLong(node, "tableId", where);
        String file = requireText(node, "file", where);
        String minKey = requireText(node, "minKey", where);
        String maxKey = requireText(node, "maxKey", where);
        long minSequence = requireLong(node, "minSequence", where);
        long maxSequence = requireLong(node, "maxSequence", where);
        long entryCount = requireLong(node, "entryCount", where);
        long sizeBytes = requireLong(node, "sizeBytes", where);

        try {
            return new SSTableMeta(tableId, file, minKey, maxKey,
                    minSequence, maxSequence, entryCount, sizeBytes);
        } catch (IllegalArgumentException e) {
            throw invalid(where + ": " + e.getMessage(), e);
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> fields, String where)
            throws StorageException {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!fields.contains(name)) {
                throw invalid(where + " has an unknown field: " + name, null);
            }
        }
        for (String field : fields) {
            if (!node.has(field)) {
                throw invalid(where + " is missing field: " + field, null);
            }
        }
    }

    private static long requireLong(JsonNode node, String field, String where) throws StorageException {
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(where + " " + field + " is not a 64-bit integer", null);
        }
        return value.longValue();
    }

    private static String requireText(JsonNode node, String field, String where) throws StorageException {
        JsonNode value = node.get(field);
        if (!value.isTextual()) {
            throw invalid(where + " " + field + " is not a string", null);
        }
        return value.textValue();
    }

    static StorageException invalid(String message, Throwable cause) {
        return new StorageException(ErrorCode.STORAGE_IO, CATEGORY, message, cause);
    }
}
