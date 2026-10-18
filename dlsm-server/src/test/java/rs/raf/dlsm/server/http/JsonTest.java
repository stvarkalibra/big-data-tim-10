package rs.raf.dlsm.server.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writesMapsInInsertionOrder() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("count", 2);
        body.put("items", List.of(Map.of("key", "a")));

        assertEquals("{\"status\":\"OK\",\"count\":2,\"items\":[{\"key\":\"a\"}]}",
                new String(Json.write(body), StandardCharsets.UTF_8));
    }

    @Test
    void readsObjectAndIgnoresUnknownFields() throws ApiException {
        ObjectNode node = Json.readObject(
                utf8("{\"value\":\"QQ==\",\"future\":1}"));

        assertEquals("QQ==", node.get("value").asText());
    }

    @Test
    void readsEmptyObject() throws ApiException {
        assertTrue(Json.readObject(utf8("{}")).isEmpty());
    }

    @Test
    void rejectsEmptyMalformedAndNonObjectBodies() {
        for (String body : new String[] {
                "", "   ", "{", "not json", "[]", "\"x\"", "1", "null",
                "{} {}", "{\"value\":\"a\",\"value\":\"b\"}"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> Json.readObject(utf8(body)), body);
            assertEquals(ErrorCode.INVALID_REQUEST, e.code());
        }

        assertThrows(ApiException.class, () -> Json.readObject(null));
    }

    @Test
    void parserDetailIsNotEchoedToClient() {
        ApiException e = assertThrows(ApiException.class,
                () -> Json.readObject(utf8("{\"value\": @@@ }")));

        assertFalse(e.getMessage().contains("@"));
        assertFalse(e.getMessage().contains("line"));
    }
}
