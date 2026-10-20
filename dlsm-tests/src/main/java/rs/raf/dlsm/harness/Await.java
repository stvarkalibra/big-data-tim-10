package rs.raf.dlsm.harness;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public class Await {
    public static void until(BooleanSupplier condition, Duration timeout, String message) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
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
