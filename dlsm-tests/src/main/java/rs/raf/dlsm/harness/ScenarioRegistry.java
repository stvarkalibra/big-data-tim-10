package rs.raf.dlsm.harness;

import rs.raf.dlsm.scenarios.Cp1Api001;
import rs.raf.dlsm.scenarios.Cp1Api002;
import rs.raf.dlsm.scenarios.Cp1Api003;
import rs.raf.dlsm.scenarios.Cp1Api004;
import rs.raf.dlsm.scenarios.Cp1Api005;
import rs.raf.dlsm.scenarios.Cp1Cmp001;
import rs.raf.dlsm.scenarios.Cp1Cmp002;
import rs.raf.dlsm.scenarios.Cp1Cmp003;
import rs.raf.dlsm.scenarios.Cp1Cmp004;
import rs.raf.dlsm.scenarios.Cp1Cmp005;
import rs.raf.dlsm.scenarios.Cp1Fls001;
import rs.raf.dlsm.scenarios.Cp1Fls002;
import rs.raf.dlsm.scenarios.Cp1Fls003;
import rs.raf.dlsm.scenarios.Cp1Fls004;
import rs.raf.dlsm.scenarios.Cp1Fls005;
import rs.raf.dlsm.scenarios.Cp1Fls006;
import rs.raf.dlsm.scenarios.Cp1Fls007;
import rs.raf.dlsm.scenarios.Cp1Mdl001;
import rs.raf.dlsm.scenarios.Cp1Sst001;
import rs.raf.dlsm.scenarios.Cp1Sst002;
import rs.raf.dlsm.scenarios.Cp1Sst003;
import rs.raf.dlsm.scenarios.Cp1Sst004;
import rs.raf.dlsm.scenarios.Cp1Sst005;
import rs.raf.dlsm.scenarios.Cp1Sst006;
import rs.raf.dlsm.scenarios.Cp1Wal001;
import rs.raf.dlsm.scenarios.Cp1Wal002;
import rs.raf.dlsm.scenarios.Cp1Wal003;
import rs.raf.dlsm.scenarios.Cp1Wal004;
import rs.raf.dlsm.scenarios.Cp1Wal005;
import rs.raf.dlsm.scenarios.Cp1Wal006;
import rs.raf.dlsm.scenarios.Cp1Mdl002;
import rs.raf.dlsm.scenarios.Cp1Wal007;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 33 stable scenario IDs of Checkpoint 1 (CP1 §29)
 */
public final class ScenarioRegistry{

    public interface RunnableScenario {
        void run(long seed, Report report) throws Exception;
    }
    /** CP1 §29.1–§29.6. */
    public static final List<String> CHECKPOINT_1_IDS = List.of(
            "CP1-API-001", "CP1-API-002", "CP1-API-003", "CP1-API-004", "CP1-API-005",
            "CP1-WAL-001", "CP1-WAL-002", "CP1-WAL-003", "CP1-WAL-004", "CP1-WAL-005", "CP1-WAL-006", "CP1-WAL-007", "CP1-WAL-008",
            "CP1-FLS-001", "CP1-FLS-002", "CP1-FLS-003", "CP1-FLS-004", "CP1-FLS-005", "CP1-FLS-006", "CP1-FLS-007",
            "CP1-SST-001", "CP1-SST-002", "CP1-SST-003", "CP1-SST-004", "CP1-SST-005", "CP1-SST-006",
            "CP1-CMP-001", "CP1-CMP-002", "CP1-CMP-003", "CP1-CMP-004", "CP1-CMP-005", "CP1-MDL-001", "CP1-MDL-002");

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
        register("CP1-MDL-002", Cp1Mdl002::run);
        register(Cp1Sst001.ID, Cp1Sst001::run);
        register(Cp1Sst002.ID, Cp1Sst002::run);
        register(Cp1Sst003.ID, Cp1Sst003::run);
        register(Cp1Sst004.ID, Cp1Sst004::run);
        register(Cp1Sst005.ID, Cp1Sst005::run);
        register(Cp1Fls001.ID, Cp1Fls001::run);
        register(Cp1Fls002.ID, Cp1Fls002::run);
        register(Cp1Fls003.ID, Cp1Fls003::run);
        register(Cp1Fls004.ID, Cp1Fls004::run);
        register(Cp1Fls005.ID, Cp1Fls005::run);
        register(Cp1Fls006.ID, Cp1Fls006::run);
        register(Cp1Fls007.ID, Cp1Fls007::run);
        register(Cp1Cmp001.ID, Cp1Cmp001::run);
        register(Cp1Cmp002.ID, Cp1Cmp002::run);
        register(Cp1Cmp003.ID, Cp1Cmp003::run);
        register(Cp1Cmp004.ID, Cp1Cmp004::run);
        register(Cp1Cmp005.ID, Cp1Cmp005::run);

        /** Wal */
        register(Cp1Wal001.ID, Cp1Wal001::run);
        register(Cp1Wal002.ID, Cp1Wal002::run);
        register(Cp1Wal003.ID, Cp1Wal003::run);
        register(Cp1Wal004.ID, Cp1Wal004::run);
        register(Cp1Wal005.ID, Cp1Wal005::run);
        register(Cp1Wal006.ID, Cp1Wal006::run);
        register(Cp1Wal007.ID, Cp1Wal007::run);
    }

    private ScenarioRegistry() {
    }

    public static void register(String id, RunnableScenario scenario) {
        if (!CHECKPOINT_1_IDS.contains(id)) {
            throw new IllegalArgumentException("Not a Checkpoint 1 scenario ID: " + id);
        }
        if (implemented.putIfAbsent(id, scenario) != null) {
            throw new IllegalStateException("Scenario registered twice: " + id);
        }
    }
    public static List<String> getScenarioIds() {
        return CHECKPOINT_1_IDS;
    }
    public static boolean isImplemented(String id) {
        return implemented.containsKey(id);
    }
    public static RunnableScenario get(String id) {
        RunnableScenario scenario = implemented.get(id);
        if (scenario != null || !CHECKPOINT_1_IDS.contains(id)) {
            return scenario;
        }
        return (seed, report) -> report.skip(NOT_IMPLEMENTED, "Scenario is not implemented yet");
    }
}