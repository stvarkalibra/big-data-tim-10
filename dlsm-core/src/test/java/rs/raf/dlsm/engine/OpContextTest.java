package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OpContextTest {

    @Test
    void timeoutSetsDeadlineFromMonotonicClock() {
        long before = System.nanoTime();
        OpContext ctx = OpContext.withTimeout("put-1", 5000);
        long after = System.nanoTime();

        assertEquals("put-1", ctx.requestId());
        long budget = TimeUnit.MILLISECONDS.toNanos(5000);
        assertTrue(ctx.deadlineNanos() - before >= budget);
        assertTrue(ctx.deadlineNanos() - after <= budget);
        assertFalse(ctx.isExpired());
        assertTrue(ctx.remainingNanos() <= budget);
    }

    @Test
    void pastDeadlineIsExpired() {
        OpContext ctx = new OpContext("r", System.nanoTime() - 1);

        assertTrue(ctx.isExpired());
        assertTrue(ctx.remainingNanos() <= 0);
    }

    @Test
    void backgroundHasNoRequestIdAndDoesNotExpire() {
        OpContext ctx = OpContext.background();

        assertNull(ctx.requestId());
        assertFalse(ctx.isExpired());
        assertTrue(ctx.remainingNanos() > TimeUnit.DAYS.toNanos(365));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> OpContext.withTimeout("r", 0));
    }
}
