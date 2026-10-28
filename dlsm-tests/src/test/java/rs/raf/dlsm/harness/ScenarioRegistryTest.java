package rs.raf.dlsm.harness;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The 33 scenario IDs of CP1 §29; no scenario is run. */
class ScenarioRegistryTest {

    @Test
    void listsExactlyThe33IdsOfTheSpecificationInOrder() {
        List<String> ids = ScenarioRegistry.getScenarioIds();

        assertEquals(33, ids.size());
        assertEquals(33, new HashSet<>(ids).size(), "no duplicates");
        assertEquals("CP1-API-001", ids.get(0));
        assertEquals("CP1-MDL-002", ids.get(32));
        assertEquals(5, ids.stream().filter(id -> id.startsWith("CP1-API-")).count());
        assertEquals(8, ids.stream().filter(id -> id.startsWith("CP1-WAL-")).count());
        assertEquals(7, ids.stream().filter(id -> id.startsWith("CP1-FLS-")).count());
        assertEquals(6, ids.stream().filter(id -> id.startsWith("CP1-SST-")).count());
        assertEquals(5, ids.stream().filter(id -> id.startsWith("CP1-CMP-")).count());
        assertEquals(2, ids.stream().filter(id -> id.startsWith("CP1-MDL-")).count());
    }

    @Test
    void implementedScenariosAreRegistered() {
        assertTrue(ScenarioRegistry.isImplemented("CP1-API-003"));
        assertTrue(ScenarioRegistry.isImplemented("CP1-SST-006"));
        assertTrue(ScenarioRegistry.isImplemented("CP1-WAL-001"));
    }

    @Test
    void unimplementedScenarioIsSkippedNeverPassed() {
        // Any ID still without an implementation; a fixed ID breaks as soon as it is registered.
        Optional<String> unimplemented = ScenarioRegistry.getScenarioIds().stream()
                .filter(id -> !ScenarioRegistry.isImplemented(id))
                .findFirst();
        assumeTrue(unimplemented.isPresent(), "all 33 scenarios are implemented");
        String id = unimplemented.get();

        Report report = ScenarioRunner.execute(id, ScenarioRegistry.get(id), 1);

        assertEquals(Report.Status.SKIPPED, report.getStatus());
        assertEquals(ScenarioRegistry.NOT_IMPLEMENTED, report.getFailurePhase());
    }

    @Test
    void unknownIdHasNoScenario() {
        assertNull(ScenarioRegistry.get("CP1-API-404"));
        assertNull(ScenarioRegistry.get("CP2-API-001"));
    }

    @Test
    void onlyCheckpointOneIdsCanBeRegisteredOnce() {
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioRegistry.register("CP1-API-999", (seed, r) -> r.pass()));
        assertThrows(IllegalStateException.class,
                () -> ScenarioRegistry.register("CP1-API-003", (seed, r) -> r.pass()));
    }
}
