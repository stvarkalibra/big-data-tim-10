package rs.raf.dlsm.server.http;

import rs.raf.dlsm.common.ErrorCode;

import java.util.Objects;

/**
 * Request-level failure detected by the HTTP layer itself, such as an
 * invalid header, key or body (PI §17.4). It never reaches the engine.
 *
 * <p>The message is sent to the client as-is, so it must stay short and
 * must not contain paths or value bytes.
 */
public final class ApiException extends Exception {

    private final ErrorCode code;
    private final PartitionRef partition;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    /**
     * @param partition partition context once the request is known to
     *                  target one (e.g. a valid key), otherwise {@code null}
     */
    public ApiException(ErrorCode code, String message, PartitionRef partition) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.partition = partition;
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    public ErrorCode code() {
        return code;
    }

    /** Partition context for the error envelope, or {@code null} if unknown. */
    public PartitionRef partition() {
        return partition;
    }
}
