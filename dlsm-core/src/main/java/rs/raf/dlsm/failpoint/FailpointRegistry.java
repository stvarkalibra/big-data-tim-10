package rs.raf.dlsm.failpoint;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.InjectedIOException;

import java.util.Map;
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
        ARMED, TRIGGERED, RELEASED, EXHAUSTED
    }

    public static class Rule {
        private final String token;
        private final String name;
        private final Action action;
        private final int afterHits;
        private final int maxTriggers;
        private final long pauseTimeoutMs;
        private final Map<String, String> parameters;
        private final AtomicInteger hitCount = new AtomicInteger(0);
        private final AtomicInteger triggerCount = new AtomicInteger(0);

        private volatile State state = State.ARMED;
        private volatile CountDownLatch latch;

        public Rule(String token, String name, Action action, int afterHits, int maxTriggers, long pauseTimeoutMs, Map<String, String> parameters) {
            this.token = token;
            this.name = name;
            this.action = action;
            this.afterHits = afterHits;
            this.maxTriggers = maxTriggers;
            this.pauseTimeoutMs = pauseTimeoutMs > 0 ? pauseTimeoutMs : 5000;
            this.parameters = parameters != null ? parameters : Map.of();
        }

        public String getToken() { return token; }
        public String getName() { return name; }
        public Action getAction() { return action; }
        public State getState() { return state; }
        public Map<String, String> getParameters() { return parameters; }

        public void release() {
            if (latch != null) {
                latch.countDown();
            }
            state = State.RELEASED;
        }
    }

    private final boolean testMode;
    private final Map<String, Rule> rulesByToken = new ConcurrentHashMap<>();
    private final Map<String, Rule> rulesByName = new ConcurrentHashMap<>();

    public FailpointRegistry(boolean testMode) {
        this.testMode = testMode;
    }

    public void addRule(Rule rule) {
        rulesByToken.put(rule.getToken(), rule);
        rulesByName.put(rule.getName(), rule);
    }

    public void removeRule(String token) {
        Rule r = rulesByToken.remove(token);
        if (r != null) {
            rulesByName.remove(r.getName());
        }
    }

    public void releaseRule(String token) {
        Rule r = rulesByToken.get(token);
        if (r != null) {
            r.release();
        }
    }

    public Map<String, Rule> getRulesByToken() {
        return rulesByToken;
    }

    public void clear() {
        rulesByToken.clear();
        rulesByName.clear();
    }

    @Override
    public void hit(String name, OpContext ctx) throws InjectedIOException {
        if (!testMode) {
            return;
        }

        Rule rule = rulesByName.get(name);
        if (rule == null) {
            return;
        }

        // Filtriranje po requestId ako je zadata parametarska vrednost
        if (rule.getParameters().containsKey("requestId")) {
            String expectedRequestId = rule.getParameters().get("requestId");
            if (ctx != null && ctx.requestId() != null && !ctx.requestId().equals(expectedRequestId)) {
                return;
            }
        }

        int currentHits = rule.hitCount.incrementAndGet();
        if (currentHits <= rule.afterHits) {
            return;
        }

        if (rule.maxTriggers > 0 && rule.triggerCount.get() >= rule.maxTriggers) {
            rule.state = State.EXHAUSTED;
            return;
        }

        rule.triggerCount.incrementAndGet();

        switch (rule.getAction()) {
            case CRASH:
                rule.state = State.TRIGGERED;
                Runtime.getRuntime().halt(137);
                break;
            case PAUSE:
                rule.state = State.TRIGGERED;
                rule.latch = new CountDownLatch(1);
                try {
                    rule.latch.await(rule.pauseTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (rule.state != State.RELEASED) {
                    rule.state = State.EXHAUSTED;
                }
                break;
            case IO_ERROR:
                rule.state = State.TRIGGERED;
                throw new InjectedIOException(rule.getName(), "Injected I/O error by rule token: " + rule.getToken());
            case RETURN_ERROR:
                rule.state = State.TRIGGERED;
                break;
        }
    }

    @Override
    public OptionalInt intParameter(String name, OpContext ctx, String param) {
        Rule rule = rulesByName.get(name);
        if (rule != null && rule.getParameters().containsKey(param)) {
            try {
                return OptionalInt.of(Integer.parseInt(rule.getParameters().get(param)));
            } catch (NumberFormatException e) {
                // ignorisanje neispravnog formata
            }
        }
        return OptionalInt.empty();
    }
}