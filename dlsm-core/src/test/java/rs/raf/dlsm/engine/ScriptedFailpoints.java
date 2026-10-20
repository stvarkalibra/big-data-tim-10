package rs.raf.dlsm.engine;

import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.InjectedIOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test failpoints with the PI §21 behaviour the engine tests need: a
 * {@code PAUSE} that reports {@code TRIGGERED} and blocks until released,
 * an optional request-id filter, and {@code IO_ERROR}. Records every hit.
 */
final class ScriptedFailpoints implements Failpoints {

    private record Rule(String action, String requestId,
                        CountDownLatch triggered, CountDownLatch released) {
    }

    /** "name|requestId" for every hit, in order. */
    final List<String> hits = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Rule> rules = new ConcurrentHashMap<>();

    /** Pause the first hit of {@code name} by {@code requestId} (null: any request). */
    void pause(String name, String requestId) {
        rules.put(name, new Rule("PAUSE", requestId, new CountDownLatch(1), new CountDownLatch(1)));
    }

    void ioError(String name) {
        rules.put(name, new Rule("IO_ERROR", null, new CountDownLatch(1), new CountDownLatch(0)));
    }

    boolean awaitTriggered(String name, long timeoutMs) throws InterruptedException {
        return rules.get(name).triggered().await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    void release(String name) {
        rules.get(name).released().countDown();
    }

    List<String> hitNames() {
        synchronized (hits) {
            return hits.stream().map(h -> h.substring(0, h.indexOf('|'))).toList();
        }
    }

    @Override
    public void hit(String name, OpContext ctx) throws InjectedIOException {
        hits.add(name + "|" + ctx.requestId());
        Rule rule = rules.get(name);
        if (rule == null
                || (rule.requestId() != null && !rule.requestId().equals(ctx.requestId()))
                || rule.triggered().getCount() == 0) {
            return; // not armed for this request, or already triggered once
        }
        rule.triggered().countDown();

        if (rule.action().equals("IO_ERROR")) {
            throw new InjectedIOException(name, "scripted");
        }
        try {
            if (!rule.released().await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Paused failpoint " + name + " never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public OptionalInt intParameter(String name, OpContext ctx, String param) {
        return OptionalInt.empty();
    }
}
