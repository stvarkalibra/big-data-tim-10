package rs.raf.dlsm.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verdicts and the command line of the scenario runner; no node is started. */
class ScenarioRunnerTest {

    private static final long SEED = 42;

    private static Report execute(ScenarioRegistry.RunnableScenario scenario) {
        return ScenarioRunner.execute("CP1-TST-001", scenario, SEED);
    }

    @Test
    void scenarioThatPassesIsPassed() {
        Report report = execute((seed, r) -> r.pass());

        assertEquals(Report.Status.PASSED, report.getStatus());
        assertEquals(SEED, report.getSeed());
    }

    @Test
    void failureRecordedBeforeReturningStaysFailed() {
        // The old runner called pass() after every scenario and turned this into PASSED.
        Report report = execute((seed, r) -> r.fail("GET_MISMATCH", "expected 200", null));

        assertEquals(Report.Status.FAILED, report.getStatus());
        assertEquals("GET_MISMATCH", report.getFailurePhase());
    }

    @Test
    void passAfterAFailureDoesNotHideIt() {
        Report report = execute((seed, r) -> {
            r.fail("FIRST", "the cause", List.of());
            r.fail("SECOND", "an effect", List.of());
            r.pass();
        });

        assertEquals(Report.Status.FAILED, report.getStatus());
        assertEquals("FIRST", report.getFailurePhase());
        assertEquals("the cause", report.getDiagnostics());
    }

    @Test
    void thrownViolationFailsTheExecutionPhase() {
        Report report = execute((seed, r) -> {
            throw new AssertionError("scan returned [beta]");
        });

        assertEquals(Report.Status.FAILED, report.getStatus());
        assertEquals("EXECUTION", report.getFailurePhase());
        assertEquals("AssertionError: scan returned [beta]", report.getDiagnostics());
    }

    @Test
    void scenarioWithoutAVerdictFails() {
        Report report = execute((seed, r) -> { });

        assertEquals(Report.Status.FAILED, report.getStatus());
        assertEquals("VERDICT", report.getFailurePhase());
    }

    @Test
    void unknownScenarioFailsInSetup() {
        Report report = ScenarioRunner.execute("CP1-API-999", null, SEED);

        assertEquals(Report.Status.FAILED, report.getStatus());
        assertEquals("SETUP", report.getFailurePhase());
    }

    @Test
    void optionsAreParsedStrictly() {
        ScenarioRunner.Options options =
                ScenarioRunner.Options.parse(new String[] {"--case", "CP1-API-003", "--seed", "7"});
        assertEquals("CP1-API-003", options.caseId());
        assertEquals(7, options.seed());
        assertFalse(options.listOnly());

        assertEquals(ScenarioRunner.DEFAULT_SEED,
                ScenarioRunner.Options.parse(new String[] {"--list"}).seed());
    }

    @Test
    void invalidCommandLinesAreUsageErrors() {
        for (String[] args : List.of(
                new String[] {"--seed", "abc"},
                new String[] {"--seed"},
                new String[] {"--case"},
                new String[] {"--case", "--seed", "1"},
                new String[] {"--case", "A", "--case", "B"},
                new String[] {"checkpoint-2"})) {
            // A missing --case value must not silently run every scenario.
            assertEquals(ScenarioRunner.EXIT_USAGE, ScenarioRunner.run(args), String.join(" ", args));
        }
    }

    @Test
    void registeredIdsAreCheckpointOneIds() {
        assertFalse(ScenarioRegistry.getScenarioIds().isEmpty());
        for (String id : ScenarioRegistry.getScenarioIds()) {
            assertTrue(id.matches("CP1-(API|WAL|FLS|SST|CMP|MDL)-\\d{3}"), id);
        }
    }

    // ---------- the run report (PI §25.3) ----------

    @TempDir
    Path reports;

    private JsonNode report(long seed) throws Exception {
        return new ObjectMapper().readTree(
                Files.readAllBytes(reports.resolve("checkpoint-1-" + seed + ".json")));
    }

    @Test
    void everySelectedCaseGetsOneEntryInOneFile() throws Exception {
        Map<String, ScenarioRegistry.RunnableScenario> scenarios = Map.of(
                "CP1-API-001", (seed, r) -> r.pass(),
                "CP1-API-002", (seed, r) -> {
                    throw new AssertionError("GET returned 200");
                });
        List<String> ids = List.of("CP1-API-001", "CP1-API-404", "CP1-API-002");

        int exit = ScenarioRunner.runCases(ids, scenarios::get, SEED, reports);

        assertEquals(ScenarioRunner.EXIT_FAILED, exit);
        JsonNode root = report(SEED);
        assertEquals(3, root.get("selectedCaseIds").size());
        JsonNode cases = root.get("cases");
        assertEquals(3, cases.size());
        assertEquals("PASSED", cases.get(0).get("status").textValue());
        // An unknown id still gets its entry: setup failed.
        assertEquals("CP1-API-404", cases.get(1).get("id").textValue());
        assertEquals("SETUP", cases.get(1).get("failurePhase").textValue());
        assertEquals("EXECUTION", cases.get(2).get("failurePhase").textValue());
    }

    @Test
    void runWhereEveryCasePassesExitsZero() throws Exception {
        int exit = ScenarioRunner.runCases(List.of("CP1-API-003"),
                id -> (seed, r) -> r.pass(), SEED, reports);

        assertEquals(ScenarioRunner.EXIT_PASSED, exit);
        assertEquals("PASSED", report(SEED).get("cases").get(0).get("status").textValue());
    }

    @Test
    void reportThatCannotBeWrittenFailsTheRun() throws Exception {
        Path notADirectory = Files.createFile(reports.resolve("file"));

        assertEquals(ScenarioRunner.EXIT_FAILED, ScenarioRunner.runCases(List.of("CP1-API-003"),
                id -> (seed, r) -> r.pass(), SEED, notADirectory));
    }
}
