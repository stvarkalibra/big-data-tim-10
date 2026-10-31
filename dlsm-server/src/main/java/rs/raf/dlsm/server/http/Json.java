package rs.raf.dlsm.server.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * The single place where the server turns JSON into bytes and back (UTF-8,
 * PI §17.4). Unknown fields are allowed by the spec, so parsing returns a
 * tree and each handler reads only the fields it needs.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Json() {
    }

    /** Serializes maps, lists, strings, numbers and booleans as UTF-8 JSON. */
    public static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            // Only our own response maps are written; failure is a bug.
            throw new IllegalStateException("Cannot serialize response", e);
        }
    }

    /**
     * Parses a request body that must be one JSON object.
     *
     * @throws ApiException {@code INVALID_REQUEST} for empty, malformed or
     *                      non-object bodies; the parser detail is not echoed
     */
    public static ObjectNode readObject(byte[] body) throws ApiException {
        if (body == null || body.length == 0) {
            throw ApiException.invalid("Request body must be a JSON object");
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (IOException e) {
            throw ApiException.invalid("Request body is not valid JSON");
        }

        if (node == null || !node.isObject()) {
            throw ApiException.invalid("Request body must be a JSON object");
        }

        return (ObjectNode) node;
    }

    /**
     * Parses UTF-8 text that must be one JSON array, with the same strictness
     * as request bodies, e.g. the bootstrap failpoint rules (PI §21.2).
     *
     * @throws ApiException {@code INVALID_REQUEST} for empty, malformed or
     *                      non-array input; the parser detail is not echoed
     */
    public static ArrayNode readArray(byte[] text) throws ApiException {
        if (text == null || text.length == 0) {
            throw ApiException.invalid("Value must be a JSON array");
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (IOException e) {
            throw ApiException.invalid("Value is not valid JSON");
        }

        if (node == null || !node.isArray()) {
            throw ApiException.invalid("Value must be a JSON array");
        }

        return (ArrayNode) node;
    }
}
