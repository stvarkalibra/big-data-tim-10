package rs.raf.dlsm.recovery;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Contents of {@code node.json}: the stable identity of the node and the
 * format version of its directory (CP1 §14.1, FORMAT.md §4.1).
 *
 * <p>The engine writes exactly {@code {"formatVersion":1,"nodeId":"<id>"}}.
 * Reading is strict: anything else, including an unknown field, means the
 * file is not ours and startup fails with {@code NODE_IDENTITY_MISMATCH}.
 */
public record NodeIdentity(int formatVersion, String nodeId) {

    public static final int FORMAT_VERSION = 1;

    /** Stable diagnostic category (plan §18.3). */
    public static final String CATEGORY = "NODE_IDENTITY_MISMATCH";

    private static final int MAX_NODE_ID_LENGTH = 64;
    private static final Set<String> FIELDS = Set.of("formatVersion", "nodeId");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public NodeIdentity {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported node format version: " + formatVersion);
        }
        if (!isValidNodeId(nodeId)) {
            throw new IllegalArgumentException("Invalid node ID");
        }
    }

    /** Identity of a node configured with {@code NODE_ID = nodeId}. */
    public static NodeIdentity of(String nodeId) {
        return new NodeIdentity(FORMAT_VERSION, nodeId);
    }

    /** The exact bytes written to {@code node.json}: UTF-8, no BOM, no whitespace. */
    public byte[] toJson() {
        // A valid node ID contains only [A-Za-z0-9._-], so it needs no escaping.
        return ("{\"formatVersion\":" + formatVersion + ",\"nodeId\":\"" + nodeId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses {@code node.json} bytes strictly: a JSON object with exactly an
     * integer {@code formatVersion} of {@code 1} and a valid string
     * {@code nodeId}.
     *
     * @throws StorageException {@code NODE_IDENTITY_MISMATCH} for anything else
     */
    public static NodeIdentity parse(byte[] json) throws StorageException {
        Objects.requireNonNull(json, "json");

        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw mismatch("node.json is not valid JSON", e);
        } catch (IOException e) {
            throw mismatch("node.json could not be parsed", e);
        }

        if (root == null || !root.isObject()) {
            throw mismatch("node.json is not a JSON object", null);
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!FIELDS.contains(name)) {
                throw mismatch("node.json has an unknown field: " + name, null);
            }
        }

        JsonNode version = root.get("formatVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) {
            throw mismatch("node.json formatVersion is missing or not an integer", null);
        }
        if (version.intValue() != FORMAT_VERSION) {
            throw mismatch("Unsupported node.json formatVersion " + version.asText(), null);
        }

        JsonNode nodeId = root.get("nodeId");
        if (nodeId == null || !nodeId.isTextual() || !isValidNodeId(nodeId.textValue())) {
            throw mismatch("node.json nodeId is missing or invalid", null);
        }

        return new NodeIdentity(FORMAT_VERSION, nodeId.textValue());
    }

    /**
     * Reads {@code node.json} and requires it to name {@code expectedNodeId}
     * (CP1 §14.1: a different ID than configured fails startup).
     *
     * @throws java.nio.file.NoSuchFileException when the file does not exist;
     *         whether that is allowed is decided by startup classification
     */
    public static NodeIdentity readAndCheck(Path nodeFile, String expectedNodeId)
            throws IOException, StorageException {

        NodeIdentity identity = parse(Files.readAllBytes(nodeFile));
        if (!identity.nodeId().equals(expectedNodeId)) {
            throw mismatch("node.json names node '" + identity.nodeId()
                    + "', configured NODE_ID is '" + expectedNodeId + "'", null);
        }
        return identity;
    }

    /** Same rules as {@code NODE_ID} in {@code ServerConfig}: 1..64 of [A-Za-z0-9._-]. */
    static boolean isValidNodeId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_NODE_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static StorageException mismatch(String message, Throwable cause) {
        return new StorageException(ErrorCode.STORAGE_IO, CATEGORY, message, cause);
    }
}
