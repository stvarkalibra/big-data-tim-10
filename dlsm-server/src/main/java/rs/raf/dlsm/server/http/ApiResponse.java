package rs.raf.dlsm.server.http;

import rs.raf.dlsm.common.ErrorCode;

import java.util.Map;
import java.util.Objects;

/**
 * Server-independent response: HTTP status, JSON body and extra headers.
 * The server adds {@code Content-Type}, {@code Cache-Control: no-store} and
 * the echoed {@code X-Request-Id} to every response itself.
 */
public record ApiResponse(int status, Map<String, Object> body,
                          Map<String, String> headers) {

    public ApiResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Invalid HTTP status");
        }
        Objects.requireNonNull(body, "body");
        headers = Map.copyOf(headers);
    }

    public static ApiResponse of(int status, Map<String, Object> body) {
        return new ApiResponse(status, body, Map.of());
    }

    public static ApiResponse ok(Map<String, Object> body) {
        return of(200, body);
    }

    /** Shared error envelope with the status mapped from the code. */
    public static ApiResponse error(ErrorCode code, String message,
                                    String requestId, PartitionRef partition) {
        return of(ErrorEnvelope.httpStatus(code),
                ErrorEnvelope.body(code, message, requestId, partition));
    }
}
