package rs.raf.dlsm.server.http;

import java.util.List;

/**
 * Parses the shared request headers (PI §17.2, §17.3):
 * {@code X-Request-Id} (1..64 chars from {@code [A-Za-z0-9._:-]}) and
 * {@code X-Request-Timeout-Ms} ({@code 100..10000}, default 5000).
 */
public final class RequestContext {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TIMEOUT_HEADER = "X-Request-Timeout-Ms";

    public static final int MAX_REQUEST_ID_LENGTH = 64;
    public static final long DEFAULT_TIMEOUT_MS = 5000;
    public static final long MIN_TIMEOUT_MS = 100;
    public static final long MAX_TIMEOUT_MS = 10_000;

    private RequestContext() {
    }

    /**
     * @param values   all values of the header, or {@code null} if absent
     * @param required whether a missing header is an error (public API)
     * @return the request id, or {@code null} when absent and not required
     */
    public static String requestId(List<String> values, boolean required)
            throws ApiException {

        if (values == null || values.isEmpty()) {
            if (required) {
                throw ApiException.invalid("Missing X-Request-Id header");
            }
            return null;
        }

        if (values.size() > 1) {
            throw ApiException.invalid("Repeated X-Request-Id header");
        }

        String id = values.get(0);
        if (!isValidRequestId(id)) {
            throw ApiException.invalid("Invalid X-Request-Id header");
        }
        return id;
    }

    /** Relative timeout in milliseconds; the default when the header is absent. */
    public static long timeoutMillis(List<String> values) throws ApiException {
        if (values == null || values.isEmpty()) {
            return DEFAULT_TIMEOUT_MS;
        }

        if (values.size() > 1) {
            throw ApiException.invalid("Repeated X-Request-Timeout-Ms header");
        }

        String text = values.get(0);
        // Plain decimal digits only; at most 5 digits keeps parsing overflow-free.
        if (text.isEmpty() || text.length() > 5 || !text.chars().allMatch(
                c -> c >= '0' && c <= '9')) {
            throw ApiException.invalid("Invalid X-Request-Timeout-Ms header");
        }

        long millis = Long.parseLong(text);
        if (millis < MIN_TIMEOUT_MS || millis > MAX_TIMEOUT_MS) {
            throw ApiException.invalid("X-Request-Timeout-Ms out of range");
        }
        return millis;
    }

    public static boolean isValidRequestId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_REQUEST_ID_LENGTH) {
            return false;
        }

        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
