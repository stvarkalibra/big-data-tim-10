package rs.raf.dlsm.harness;

import rs.raf.dlsm.scenarios.Cp1Mdl001;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ScenarioRegistry {
    private static final Map<String, RunnableScenario> scenarios = new LinkedHashMap<>();

    public interface RunnableScenario {
        void run(long seed, Report report) throws Exception;
    }

    public static void register(String id, RunnableScenario scenario) {
        scenarios.put(id, scenario);
    }

    public static Set<String> getScenarioIds() {
        return Collections.unmodifiableSet(scenarios.keySet());
    }

    public static RunnableScenario get(String id) {
        return scenarios.get(id);
    }

    static {
        ScenarioRegistry.register("CP1-MDL-001", Cp1Mdl001::run);
        // Ovde ćemo kasnije registrovati svih 33 scenarija (API, WAL, FLS, SST, CMP, MDL)
        // Primer privremene registracije za testiranje harness-a:
        register("CP1-API-001", (seed, report) -> {
            // Logika testa će se ovde izvršavati
            report.pass();
        });
    }
}
