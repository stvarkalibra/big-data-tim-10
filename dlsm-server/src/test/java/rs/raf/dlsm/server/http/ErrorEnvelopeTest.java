package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ErrorEnvelopeTest {

    @Test
    void httpStatusFollowsSharedTable() {
        assertEquals(400, ErrorEnvelope.httpStatus(ErrorCode.INVALID_REQUEST));
        assertEquals(400, ErrorEnvelope.httpStatus(ErrorCode.VALUE_TOO_LARGE));
        assertEquals(404, ErrorEnvelope.httpStatus(ErrorCode.NOT_FOUND));
        assertEquals(500, ErrorEnvelope.httpStatus(ErrorCode.STORAGE_IO));
        assertEquals(500, ErrorEnvelope.httpStatus(ErrorCode.INTERNAL_ERROR));
        assertEquals(503, ErrorEnvelope.httpStatus(ErrorCode.NODE_NOT_READY));
        assertEquals(504, ErrorEnvelope.httpStatus(ErrorCode.DEADLINE_EXCEEDED));
    }

    @Test
    void onlyTransientCodesAreRetryable() {
        assertTrue(ErrorEnvelope.retryable(ErrorCode.NODE_NOT_READY));
        assertTrue(ErrorEnvelope.retryable(ErrorCode.DEADLINE_EXCEEDED));
        assertFalse(ErrorEnvelope.retryable(ErrorCode.INVALID_REQUEST));
        assertFalse(ErrorEnvelope.retryable(ErrorCode.NOT_FOUND));
        assertFalse(ErrorEnvelope.retryable(ErrorCode.STORAGE_IO));
    }

    @Test
    void bodyWithPartitionHasExactFieldsInOrder() {
        Map<String, Object> body = ErrorEnvelope.body(
                ErrorCode.NOT_FOUND, "Key not found", "get-001",
                PartitionRef.STANDALONE);

        assertEquals(
                List.of("status", "code", "message", "requestId",
                        "retryable", "partitionId", "epoch"),
                List.copyOf(body.keySet()));
        assertEquals("ERROR", body.get("status"));
        assertEquals("NOT_FOUND", body.get("code"));
        assertEquals("get-001", body.get("requestId"));
        assertEquals(false, body.get("retryable"));
        assertEquals(0, body.get("partitionId"));
        assertEquals(0L, body.get("epoch"));
    }

    @Test
    void unknownPartitionIsOmittedNotNull() {
        Map<String, Object> body = ErrorEnvelope.body(
                ErrorCode.INVALID_REQUEST, "Invalid key", "put-1", null);

        assertFalse(body.containsKey("partitionId"));
        assertFalse(body.containsKey("epoch"));
    }

    @Test
    void missingRequestIdIsSerializedAsNull() {
        String json = new String(Json.write(ErrorEnvelope.body(
                ErrorCode.INVALID_REQUEST, "Invalid X-Request-Id", null, null)),
                StandardCharsets.UTF_8);

        assertEquals(
                "{\"status\":\"ERROR\",\"code\":\"INVALID_REQUEST\","
                        + "\"message\":\"Invalid X-Request-Id\","
                        + "\"requestId\":null,\"retryable\":false}",
                json);
    }
}
