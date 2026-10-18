package rs.raf.dlsm.engine;

import java.util.concurrent.TimeUnit;

/**
 * Per-operation context passed from the HTTP layer through the engine down
 * to {@code StorageIO} and failpoints (plan §5.1, §8).
 *
 * <p>{@code requestId} is {@code null} for background work such as an
 * automatic flush. {@code deadlineNanos} is a {@link System#nanoTime()}
 * instant: deadlines use the monotonic clock, never wall time (PI §17.3).
 * Compare instants only by subtraction, because nanoTime may overflow.
 */
public record OpContext(String requestId, long deadlineNanos) {

    /** Effectively unbounded budget for background maintenance. */
    private static final long BACKGROUND_BUDGET_NANOS = Long.MAX_VALUE / 2;

    public static OpContext withTimeout(String requestId, long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return new OpContext(requestId,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
    }

    public static OpContext background() {
        return new OpContext(null, System.nanoTime() + BACKGROUND_BUDGET_NANOS);
    }

    /** Nanoseconds left until the deadline; zero or negative once expired. */
    public long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }

    public boolean isExpired() {
        return remainingNanos() <= 0;
    }
}
