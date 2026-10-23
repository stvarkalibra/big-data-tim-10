package rs.raf.dlsm.harness;

import rs.raf.dlsm.scenarios.Cp1Api001;
import rs.raf.dlsm.scenarios.Cp1Api002;
import rs.raf.dlsm.scenarios.Cp1Api003;
import rs.raf.dlsm.scenarios.Cp1Api004;
import rs.raf.dlsm.scenarios.Cp1Api005;
import rs.raf.dlsm.scenarios.Cp1Fls001;
import rs.raf.dlsm.scenarios.Cp1Fls002;
import rs.raf.dlsm.scenarios.Cp1Fls003;
import rs.raf.dlsm.scenarios.Cp1Fls007;
import rs.raf.dlsm.scenarios.Cp1Mdl001;
import rs.raf.dlsm.scenarios.Cp1Sst006;
import rs.raf.dlsm.scenarios.Cp1Sst001;
import rs.raf.dlsm.scenarios.Cp1Sst002;
import rs.raf.dlsm.scenarios.Cp1Sst003;
import rs.raf.dlsm.scenarios.Cp1Sst004;
import rs.raf.dlsm.scenarios.Cp1Sst005;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 33 stable scenario IDs of Checkpoint 1 (CP1 §29), in the order of the
 * specification, and the scenarios implemented so far (plan §5.2, §6.5).
 *
 * <p>{@code --list} and a full run always cover all 33 IDs. An ID without an
 * implementation is reported as {@code SKIPPED} with the phase
 * {@code NOT_IMPLEMENTED}, never as passed, so the suite fails until every
 * scenario exists (PI §25.3).
 */
public final class ScenarioRegistry {

    public interface RunnableScenario {
        void run(long seed, Report report) throws Exception;
    }

    /** CP1 §29.1–§29.6. */
    public static final List<String> CHECKPOINT_1_IDS = List.of(
            "CP1-API-001", "CP1-API-002", "CP1-API-003", "CP1-API-004", "CP1-API-005",
            "CP1-WAL-001", "CP1-WAL-002", "CP1-WAL-003", "CP1-WAL-004",
            "CP1-WAL-005", "CP1-WAL-006", "CP1-WAL-007", "CP1-WAL-008",
            "CP1-FLS-001", "CP1-FLS-002", "CP1-FLS-003", "CP1-FLS-004",
            "CP1-FLS-005", "CP1-FLS-006", "CP1-FLS-007",
            "CP1-SST-001", "CP1-SST-002", "CP1-SST-003",
            "CP1-SST-004", "CP1-SST-005", "CP1-SST-006",
            "CP1-CMP-001", "CP1-CMP-002", "CP1-CMP-003", "CP1-CMP-004", "CP1-CMP-005",
            "CP1-MDL-001", "CP1-MDL-002");

    public static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

    private static final Map<String, RunnableScenario> implemented = new HashMap<>();

    static {
        register(Cp1Api001.ID, Cp1Api001::run);
        register(Cp1Api002.ID, Cp1Api002::run);
        register(Cp1Api003.ID, Cp1Api003::run);
        register(Cp1Api004.ID, Cp1Api004::run);
        register(Cp1Api005.ID, Cp1Api005::run);
        register(Cp1Sst006.ID, Cp1Sst006::run);
        register("CP1-MDL-001", Cp1Mdl001::run);
        register(Cp1Sst001.ID, Cp1Sst001::run);
        register(Cp1Sst002.ID, Cp1Sst002::run);
        register(Cp1Sst003.ID, Cp1Sst003::run);
        register(Cp1Sst004.ID, Cp1Sst004::run);
        register(Cp1Sst005.ID, Cp1Sst005::run);
        register(Cp1Fls001.ID, Cp1Fls001::run);
        register(Cp1Fls002.ID, Cp1Fls002::run);
        register(Cp1Fls003.ID, Cp1Fls003::run);
        register(Cp1Fls007.ID, Cp1Fls007::run);
    }

    private ScenarioRegistry() {
    }

    /** Adds the implementation of one of the 33 IDs. */
    public static void register(String id, RunnableScenario scenario) {
        if (!CHECKPOINT_1_IDS.contains(id)) {
            throw new IllegalArgumentException("Not a Checkpoint 1 scenario ID: " + id);
        }
        if (implemented.putIfAbsent(id, scenario) != null) {
            throw new IllegalStateException("Scenario registered twice: " + id);
        }
    }

    /** All 33 IDs in the order of the specification. */
    public static List<String> getScenarioIds() {
        return CHECKPOINT_1_IDS;
    }

    public static boolean isImplemented(String id) {
        return implemented.containsKey(id);
    }

    /**
     * @return the scenario for {@code id}; for a Checkpoint 1 ID without an
     *         implementation, one that reports {@code SKIPPED}; {@code null}
     *         for an unknown ID
     */
    public static RunnableScenario get(String id) {
        RunnableScenario scenario = implemented.get(id);
        if (scenario != null || !CHECKPOINT_1_IDS.contains(id)) {
            return scenario;
        }
        return (seed, report) -> report.skip(NOT_IMPLEMENTED, "Scenario is not implemented yet");
    }
}