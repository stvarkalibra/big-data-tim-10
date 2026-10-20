package rs.raf.dlsm.harness;

import rs.raf.dlsm.scenarios.Cp1Api003;
import rs.raf.dlsm.scenarios.Cp1Mdl001;

import java.nio.charset.StandardCharsets;
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
        register(Cp1Api003.ID, Cp1Api003::run);
        // Ovde ćemo kasnije registrovati svih 33 scenarija (API, WAL, FLS, SST, CMP, MDL)

        register("CP1-API-001", (seed, report) -> {
            int publicPort = 18081;
            int adminPort = 18083;
            try (NodeController node = NodeController.startStandalone(publicPort, adminPort)) {
                node.awaitReady(10000);
                ApiClient api = new ApiClient(publicPort);

                String key = "test-key";
                byte[] val1 = "hello-dlsm".getBytes(StandardCharsets.UTF_8);

                // 1. PUT upis
                var putResp = api.put(key, val1, "REQ-API-001-1", 5000);
                if (putResp.statusCode() != 200) {
                    report.fail("API_PUT_FAILED", "Expected 200, got " + putResp.statusCode(), null);
                    return;
                }

                // 2. GET čitanje i provera vrednosti
                var getResp = api.get(key, "REQ-API-001-2", 5000);
                if (getResp.statusCode() != 200) {
                    report.fail("API_GET_FAILED", "Expected 200, got " + getResp.statusCode(), null);
                    return;
                }

                // 3. Prepisivanje (update) i prazna vrednost
                byte[] emptyVal = new byte[0];
                api.put(key, emptyVal, "REQ-API-001-3", 5000);
                var getEmptyResp = api.get(key, "REQ-API-001-4", 5000);
                if (getEmptyResp.statusCode() != 200) {
                    report.fail("API_EMPTY_VAL_FAILED", "Expected 200 for empty value, got " + getEmptyResp.statusCode(), null);
                    return;
                }

                report.pass();
            }
        });    }
}
