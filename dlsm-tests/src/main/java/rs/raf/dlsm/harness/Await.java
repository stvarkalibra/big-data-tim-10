package rs.raf.dlsm.harness;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public class Await {
    public static void until(BooleanSupplier condition, Duration timeout, String message) {
        // Monotonic clock: a wall-clock jump must not stretch or cut the deadline.
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() - deadline < 0) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during await: " + message, e);
            }
        }
        throw new AssertionError("Timeout exceeded while waiting for: " + message);
    }
}
