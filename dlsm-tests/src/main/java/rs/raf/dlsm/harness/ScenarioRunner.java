package rs.raf.dlsm.harness;

import java.util.List;

public class ScenarioRunner {
    public static void main(String[] args) {
        String caseId = null;
        long seed = 12345;
        boolean listOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--list":
                    listOnly = true;
                    break;
                case "--case":
                    if (i + 1 < args.length) caseId = args[++i];
                    break;
                case "--seed":
                    if (i + 1 < args.length) seed = Long.parseLong(args[++i]);
                    break;
            }
        }

        if (listOnly) {
            System.out.println("Available Checkpoint-1 Scenarios (" + ScenarioRegistry.getScenarioIds().size() + "/33):");
            for (String id : ScenarioRegistry.getScenarioIds()) {
                System.out.println(" - " + id);
            }
            return;
        }

        if (caseId != null) {
            runSingleScenario(caseId, seed);
        } else {
            runAllScenarios(seed);
        }
    }

    private static void runSingleScenario(String id, long seed) {
        ScenarioRegistry.RunnableScenario scenario = ScenarioRegistry.get(id);
        Report report = new Report(id, seed);

        if (scenario == null) {
            System.err.println("Scenario not found: " + id);
            report.fail("SETUP", "Scenario not found", List.of());
            report.save(seed);
            System.exit(1);
        }

        System.out.println("Running " + id + " with seed " + seed + "...");
        try {
            scenario.run(seed, report);
            System.out.println("RESULT: " + id + " -> PASSED");
        } catch (Throwable t) {
            System.err.println("RESULT: " + id + " -> FAILED: " + t.getMessage());
            t.printStackTrace();
            report.fail("EXECUTION", t.getMessage(), List.of());
            report.save(seed);
            System.exit(1);
        }
        report.save(seed);
    }

    private static void runAllScenarios(long seed) {
        System.out.println("Running all registered scenarios with seed " + seed + "...");
        boolean allPassed = true;

        for (String id : ScenarioRegistry.getScenarioIds()) {
            Report report = new Report(id, seed);
            try {
                ScenarioRegistry.RunnableScenario scenario = ScenarioRegistry.get(id);
                scenario.run(seed, report);
                report.pass();
                report.save(seed);
                System.out.println("[PASSED] " + id);
            } catch (Throwable t) {
                allPassed = false;
                report.fail("EXECUTION", t.getMessage(), List.of());
                report.save(seed);
                System.err.println("[FAILED] " + id + " -> " + t.getMessage());
            }
        }

        if (!allPassed) {
            System.exit(1);
        }
    }
}
