package rs.raf.dlsm.common;

import java.util.Objects;

/**
 * Checked failure of a storage operation.
 *
 * <p>{@code code} is the client-facing {@link ErrorCode}. {@code category}
 * is an optional stable diagnostic name such as {@code WAL_FRAME},
 * {@code CHECKSUM} or {@code DATA_BLOCK} (CP1 §16.4, §17.3), reported in
 * {@code lastFatalError.code} and logs. Categories are owned by the module
 * that detects the problem; this class only enforces their shape.
 *
 * <p>The message may reach clients and logs, so it must not contain
 * absolute paths or value bytes (PI §17.4).
 */
public class StorageException extends Exception {

    private final ErrorCode code;
    private final String category;

    public StorageException(ErrorCode code, String message) {
        this(code, null, message, null);
    }

    public StorageException(ErrorCode code, String category, String message) {
        this(code, category, message, null);
    }

    public StorageException(
            ErrorCode code, String category, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");

        if (category != null && !isValidCategory(category)) {
            throw new IllegalArgumentException(
                    "Category must be UPPER_SNAKE_CASE: " + category);
        }
        this.category = category;
    }

    public ErrorCode code() {
        return code;
    }

    /** Diagnostic category, or {@code null} when none applies. */
    public String category() {
        return category;
    }

    private static boolean isValidCategory(String category) {
        if (category.isEmpty()) {
            return false;
        }

        char first = category.charAt(0);
        if (first < 'A' || first > 'Z') {
            return false;
        }

        for (int i = 1; i < category.length(); i++) {
            char c = category.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }

        return true;
    }
}
