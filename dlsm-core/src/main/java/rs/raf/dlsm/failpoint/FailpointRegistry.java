package rs.raf.dlsm.failpoint;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.InjectedIOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FailpointRegistry implements Failpoints {

    public enum Action {
        PAUSE, CRASH, IO_ERROR, RETURN_ERROR
    }

    public enum State {
        ARMED, TRIGGERED, RELEASED, EXHAUSTED, UNKNOWN
    }

    /** Used when a rule gives no positive {@code pauseTimeoutMs}. */
    public static final long DEFAULT_PAUSE_TIMEOUT_MS = 5000;

    /** Category of a {@code RETURN_ERROR} rule without its own {@code errorCode}. */
    public static final String DEFAULT_ERROR_CODE = "INJECTED_ERROR";

    /** Category of the error a timed-out {@code PAUSE} continues with. */
    public static final String PAUSE_TIMEOUT_CATEGORY = "FAILPOINT_PAUSE_TIMEOUT";

    public static class Rule {
        private final String token;
        private final String name;
        private final Action action;
        private final int afterHits;
        private final int maxTriggers;
        private final long pauseTimeoutMs;
        private final Map<String, String> parameters;
        private final String errorCode;
        private final AtomicInteger hitCount = new AtomicInteger(0);
        private final AtomicInteger triggerCount = new AtomicInteger(0);

        private volatile State state = State.ARMED;
        /** The latch of the pause in progress, or null when nothing is paused. */
        private volatile CountDownLatch pauseLatch;

        public Rule(String token, String name, Action action, int afterHits, int maxTriggers,
                    long pauseTimeoutMs, Map<String, String> parameters) {
            this(token, name, action, afterHits, maxTriggers, pauseTimeoutMs, parameters, null);
        }

        public Rule(String token, String name, Action action, int afterHits, int maxTriggers,
                    long pauseTimeoutMs, Map<String, String> parameters, String errorCode) {
            this.token = Objects.requireNonNull(token, "token");
            this.name = Objects.requireNonNull(name, "name");
            this.action = Objects.requireNonNull(action, "action");
            if (afterHits < 0) {
                throw new IllegalArgumentException("afterHits must not be negative");
            }
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("errorCode must be UPPER_SNAKE_CASE");
            }
            this.afterHits = afterHits;
            this.maxTriggers = maxTriggers;
            this.pauseTimeoutMs = pauseTimeoutMs > 0 ? pauseTimeoutMs : DEFAULT_PAUSE_TIMEOUT_MS;
            this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
            this.errorCode = errorCode != null ? errorCode : DEFAULT_ERROR_CODE;
        }

        public String getToken() { return token; }
        public String getName() { return name; }
        public Action getAction() { return action; }
        public State getState() { return state; }
        public Map<String, String> getParameters() { return parameters; }
        public String getErrorCode() { return errorCode; }
        public long getPauseTimeoutMs() { return pauseTimeoutMs; }

        public boolean release() {
            CountDownLatch latch = pauseLatch;
            if (latch == null || state != State.TRIGGERED) {
                return false; // nothing is paused at this rule
            }
            state = State.RELEASED;
            latch.countDown();
            return true;
        }
    }

    private final boolean testMode;
    private final Map<String, Rule> rulesByToken = new ConcurrentHashMap<>();
    private final Map<String, Rule> rulesByName = new ConcurrentHashMap<>();

    public FailpointRegistry(boolean testMode) {
        this.testMode = testMode;
    }

    public boolean isTestMode() {
        return testMode;
    }

    /** Arms {@code rule}; an earlier rule with the same token or name is removed and released. */
    public void addRule(Rule rule) {
        Objects.requireNonNull(rule, "rule");
        removeRule(rule.getToken());
        Rule sameName = rulesByName.get(rule.getName());
        if (sameName != null) {
            removeRule(sameName.getToken());
        }
        rulesByToken.put(rule.getToken(), rule);
        rulesByName.put(rule.getName(), rule);
    }

    /** Disarms one rule and lets an operation paused at it continue. */
    public void removeRule(String token) {
        Rule rule = rulesByToken.remove(token);
        if (rule != null) {
            rulesByName.remove(rule.getName(), rule);
            rule.release();
        }
    }

    /** @return true if an operation paused at this rule was released */
    public boolean releaseRule(String token) {
        Rule rule = rulesByToken.get(token);
        return rule != null && rule.release();
    }

    /** {@code GET /admin/v1/failpoints/{token}}: the rule's state, or {@code UNKNOWN}. */
    public State stateOf(String token) {
        Rule rule = rulesByToken.get(token);
        return rule != null ? rule.getState() : State.UNKNOWN;
    }

    public Map<String, Rule> getRulesByToken() {
        return Collections.unmodifiableMap(rulesByToken);
    }

    /** Removes every rule and releases every pause (cleanup, PI §21.1). */
    public void clear() {
        for (String token : new ArrayList<>(rulesByToken.keySet())) {
            removeRule(token);
        }
    }

    @Override
    public void hit(String name, OpContext ctx) throws InjectedIOException {
        if (!testMode) {
            return;
        }

        Rule rule = rulesByName.get(name);
        if (rule == null || !matchesFilters(rule, ctx)) {
            return;
        }

        if (rule.hitCount.incrementAndGet() <= rule.afterHits) {
            return;
        }

        // One atomic step, so concurrent hits never trigger more than maxTriggers.
        int trigger = rule.triggerCount.incrementAndGet();
        if (rule.maxTriggers > 0 && trigger > rule.maxTriggers) {
            rule.state = State.EXHAUSTED;
            return;
        }

        switch (rule.getAction()) {
            case CRASH -> {
                rule.state = State.TRIGGERED;
                Runtime.getRuntime().halt(137);
            }
            case PAUSE -> pause(rule);
            case IO_ERROR -> {
                rule.state = State.TRIGGERED;
                throw new InjectedIOException(rule.getName(),
                        "Injected I/O error by rule token: " + rule.getToken());
            }
            case RETURN_ERROR -> {
                rule.state = State.TRIGGERED;
                throw new InjectedFailureException(rule.getName(), ErrorCode.INTERNAL_ERROR,
                        rule.getErrorCode(),
                        "Injected error at " + rule.getName() + " by rule " + rule.getToken());
            }
        }
    }

    private static void pause(Rule rule) {
        // The latch exists before the rule shows TRIGGERED, so a release
        // issued right after seeing TRIGGERED is never lost.
        CountDownLatch latch = new CountDownLatch(1);
        rule.pauseLatch = latch;
        rule.state = State.TRIGGERED;

        boolean released;
        try {
            released = latch.await(rule.pauseTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            released = false;
        }

        if (!released) {
            rule.state = State.EXHAUSTED;
            rule.pauseLatch = null;
            throw new InjectedFailureException(rule.getName(), ErrorCode.DEADLINE_EXCEEDED,
                    PAUSE_TIMEOUT_CATEGORY, "Paused failpoint " + rule.getName()
                    + " was not released within " + rule.pauseTimeoutMs + " ms");
        }
        rule.pauseLatch = null;
    }

    private static boolean matchesFilters(Rule rule, OpContext ctx) {
        String requestId = rule.getParameters().get("requestId");
        if (requestId != null
                && (ctx == null || ctx.requestId() == null || !ctx.requestId().equals(requestId))) {
            return false;
        }
        String partitionId = rule.getParameters().get("partitionId");
        // The standalone node serves only the synthetic partition 0 (PI §18.2).
        return partitionId == null || partitionId.equals("0");
    }

    @Override
    public OptionalInt intParameter(String name, OpContext ctx, String param) {
        Rule rule = rulesByName.get(name);
        if (rule != null && rule.getParameters().containsKey(param)) {
            try {
                return OptionalInt.of(Integer.parseInt(rule.getParameters().get(param)));
            } catch (NumberFormatException e) {
                // A malformed parameter is treated as absent; the admin API validates it.
            }
        }
        return OptionalInt.empty();
    }
}