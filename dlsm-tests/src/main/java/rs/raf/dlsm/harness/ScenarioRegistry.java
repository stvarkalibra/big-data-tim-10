
package rs.raf.dlsm.harness;

import rs.raf.dlsm.scenarios.Cp1Api003;
import rs.raf.dlsm.scenarios.Cp1Mdl001;
import rs.raf.dlsm.scenarios.Cp1Sst006;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ScenarioRegistry {

    private static final Map<String, RunnableScenario> scenarios =
            new LinkedHashMap<>();

    public interface RunnableScenario {
        void run(long seed, Report report) throws Exception;
    }

    public static void register(
            String id,
            RunnableScenario scenario) {

        scenarios.put(id, scenario);
    }

    public static Set<String> getScenarioIds() {
        return Collections.unmodifiableSet(
                scenarios.keySet()
        );
    }

    public static RunnableScenario get(String id) {
        return scenarios.get(id);
    }

    static {
        register("CP1-MDL-001", Cp1Mdl001::run);

        register(Cp1Api003.ID, Cp1Api003::run);

        register(Cp1Sst006.ID, Cp1Sst006::run);

        // Only real scenarios are registered: a placeholder that just passes
        // would report PASSED for a rule nobody checked (PI §25.3).
    }
}
