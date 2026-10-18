package rs.raf.dlsm.server.http;

import rs.raf.dlsm.common.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The shared error body (PI §18.7), used for every error including
 * {@code NOT_FOUND}:
 *
 * <pre>{"status":"ERROR","code":..,"message":..,"requestId":..,"retryable":..,
 *  "partitionId":..,"epoch":..}</pre>
 *
 * <p>{@code partitionId} and {@code epoch} are omitted only when unknown,
 * e.g. when the key itself is invalid or on admin routes.
 */
public final class ErrorEnvelope {

    private ErrorEnvelope() {
    }

    /** HTTP status for each shared code (PI §18.7, CP1 §23). */
    public static int httpStatus(ErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST, VALUE_TOO_LARGE -> 400;
            case NOT_FOUND -> 404;
            case STORAGE_IO, INTERNAL_ERROR -> 500;
            case NODE_NOT_READY -> 503;
            case DEADLINE_EXCEEDED -> 504;
        };
    }

    /**
     * Whether the same request may succeed if simply sent again later.
     * TEAM CHOICE: the spec does not fix this flag for CP1 codes; the
     * choice is recorded in docs/ODLUKE.md.
     */
    public static boolean retryable(ErrorCode code) {
        return switch (code) {
            case NODE_NOT_READY, DEADLINE_EXCEEDED -> true;
            case INVALID_REQUEST, VALUE_TOO_LARGE, NOT_FOUND,
                 STORAGE_IO, INTERNAL_ERROR -> false;
        };
    }

    /**
     * @param requestId the echoed request id, or {@code null} when the
     *                  client sent none or an invalid one
     * @param partition partition context, or {@code null} when unknown
     */
    public static Map<String, Object> body(
            ErrorCode code, String message, String requestId,
            PartitionRef partition) {

        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("code", code.name());
        body.put("message", message);
        body.put("requestId", requestId);
        body.put("retryable", retryable(code));

        if (partition != null) {
            body.put("partitionId", partition.partitionId());
            body.put("epoch", partition.epoch());
        }

        return body;
    }
}
