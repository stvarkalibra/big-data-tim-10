package rs.raf.dlsm.failpoint;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.InjectedIOException;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class FailpointRegistry implements Failpoints {

    public enum Action {
        PAUSE, CRASH, IO_ERROR, RETURN_ERROR
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
        public Map<String, String> getParameters() { return parameters; }
    }

    private final boolean testMode;
    private final Map<String, Rule> rulesByToken = new ConcurrentHashMap<>();
    private final Map<String, Rule> rulesByName = new ConcurrentHashMap<>(); // pojednostavljeno: 1 pravilo po imenu za CP1

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

    public void clear() {
        rulesByToken.clear();
        rulesByName.clear();
    }

    @Override
    public void hit(String name, OpContext ctx) throws InjectedIOException {
        if (!testMode) {
            return; // Failpoint-i rade samo u testnom režimu
        }

        Rule rule = rulesByName.get(name);
        if (rule == null) {
            return;
        }

        int currentHits = rule.hitCount.incrementAndGet();
        if (currentHits <= rule.afterHits) {
            return;
        }

        if (rule.triggerCount.get() >= rule.maxTriggers && rule.maxTriggers > 0) {
            return;
        }

        rule.triggerCount.incrementAndGet();

        switch (rule.getAction()) {
            case CRASH:
                // Kill procesa bez shutdown hook-ova (Exit code 137 po planu)
                Runtime.getRuntime().halt(137);
                break;
            case PAUSE:
                // Pauza sa zadatim timeout-om
                LockSupport.parkNanos(rule.pauseTimeoutMs * 1_000_000L);
                break;
            case IO_ERROR:
                throw new InjectedIOException(rule.getName(), "Injected I/O error by rule token: " + rule.getToken());
            case RETURN_ERROR:
                // Može se implementirati po potrebi
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
