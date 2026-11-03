package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import rs.raf.dlsm.failpoint.FailpointCatalog;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.harness.ScenarioRegistry;
import static rs.raf.dlsm.scenarios.Mdl002Matrix.*;
import static rs.raf.dlsm.scenarios.Mdl002Node.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The matrices and the schedule of CP1-MDL-002; the campaign itself runs against real nodes. */
class Cp1Mdl002Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void crashMatrixHasOneRowForEveryCatalogHook() {
        List<String> hooks = Mdl002Matrix.CRASH.stream().map(CrashRow::hook).toList();

        assertEquals(21, hooks.size());
        assertEquals(new HashSet<>(FailpointCatalog.names()), new HashSet<>(hooks));
    }

    @Test
    void ioErrorMatrixIsExactlyTheEightMappings() {
        Set<String> mapped = new HashSet<>();
        for (String name : FailpointCatalog.names()) {
            if (FailpointCatalog.actions(name).contains(Action.IO_ERROR)) {
                mapped.add(name);
            }
        }
        Set<String> rows = new HashSet<>();
        Mdl002Matrix.IO_ERROR.forEach(row -> rows.add(row.hook()));

        assertEquals(8, Mdl002Matrix.IO_ERROR.size());
        assertEquals(mapped, rows);
    }

    @Test
    void everyScheduleCoversEveryRowInAtLeastFiftyCrashes() {
        for (long seed : new long[] {1, 7, 42, 12345, -3}) {
            List<CrashRow> schedule = Mdl002Matrix.schedule(seed, Cp1Mdl002.MIN_CRASH_CYCLES);

            assertTrue(schedule.containsAll(Mdl002Matrix.CRASH), "seed " + seed);
            int crashes = schedule.stream().mapToInt(Mdl002Matrix::crashes).sum();
            assertTrue(crashes >= Cp1Mdl002.MIN_CRASH_CYCLES, "seed " + seed + ": " + crashes);
            // Extra cycles only repeat reachable non-recovery hooks.
            Map<String, Integer> recovery = new HashMap<>();
            schedule.stream().filter(row -> row.target() == Target.RECOVERY)
                    .forEach(row -> recovery.merge(row.hook(), 1, Integer::sum));
            assertEquals(Map.of(FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD, 1,
                    FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE, 1,
                    FailpointNames.RECOVERY_AFTER_WAL_REPLAY_BEFORE_READY, 1), recovery);
        }
    }

    @Test
    void theSameSeedGivesTheSameSchedule() {
        assertEquals(Mdl002Matrix.schedule(12345, 50), Mdl002Matrix.schedule(12345, 50));
        assertEquals(Mdl002Matrix.ioSchedule(12345), Mdl002Matrix.ioSchedule(12345));
        assertNotEquals(Mdl002Matrix.schedule(1, 50), Mdl002Matrix.schedule(2, 50));
    }

    @Test
    void recoveryHooksCrashTwiceAndTheTailIsReachedOverAPrefix() {
        assertEquals(1, Mdl002Matrix.crashes(Mdl002Matrix.crashRow(FailpointNames.WAL_BEFORE_APPEND)));
        assertEquals(2, Mdl002Matrix.crashes(
                Mdl002Matrix.crashRow(FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD)));
        assertEquals(3, Mdl002Matrix.crashes(
                Mdl002Matrix.crashRow(FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE)));
    }

    @Test
    void ruleIsTheFailpointApiFormat() throws Exception {
        JsonNode rule = MAPPER.readTree(Cp1Mdl002.rule(
                FailpointNames.WAL_AFTER_RECORD_PREFIX, "CRASH", "CP1-MDL-002-1-9", 5, 1));

        assertEquals(FailpointNames.WAL_AFTER_RECORD_PREFIX, rule.path("name").asText());
        assertEquals("CRASH", rule.path("action").asText());
        assertEquals("CP1-MDL-002-1-9", rule.path("filters").path("requestId").asText());
        assertEquals(5, rule.path("parameters").path("prefixBytes").asInt());
        assertTrue(MAPPER.readTree(Cp1Mdl002.rule("x", "IO_ERROR", null, 0, 0))
                .path("filters").isMissingNode());
    }

    @Test
    void bootstrapRulesAreAnArrayWithAToken() throws Exception {
        JsonNode rules = MAPPER.readTree(
                Cp1Mdl002.bootstrapRules("boot-1", FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD));

        assertTrue(rules.isArray());
        assertEquals(1, rules.size());
        assertEquals("boot-1", rules.get(0).path("token").asText());
        assertEquals("CRASH", rules.get(0).path("action").asText());
    }

    @Test
    void keysStayInsideTheScanRange() {
        assertEquals("mdl-00", Cp1Mdl002.key(0));
        assertTrue("mdl".compareTo(Cp1Mdl002.key(0)) < 0);
        assertTrue(Cp1Mdl002.key(Cp1Mdl002.KEY_SPACE - 1).compareTo("mdm") < 0);
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Mdl002.ID));
    }
}