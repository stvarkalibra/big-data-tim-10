package rs.raf.dlsm.harness;

import java.util.List;

/**
 * Runs Checkpoint 1 scenarios: {@code --list}, {@code --case ID} and
 * {@code --seed N} (plan §6.5).
 *
 * <p>The verdict of a scenario is what its {@link Report} says when it
 * returns: a scenario passes only by calling {@link Report#pass()} and never
 * failing. Every selected case gets a report, even when it cannot start
 * (PI §25.3).
 *
 * <p>Exit codes: {@code 0} when every selected scenario passed, {@code 1}
 * when at least one did not, {@code 64} for an invalid command line.
 */
public final class ScenarioRunner {

    static final int EXIT_PASSED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 64;

    static final long DEFAULT_SEED = 12345;

    private static final String USAGE =
            "Usage: ScenarioRunner [--list] [--case ID] [--seed N]";

    private ScenarioRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return EXIT_USAGE;
        }

        if (options.listOnly()) {
            System.out.println("Available Checkpoint-1 Scenarios ("
                    + ScenarioRegistry.getScenarioIds().size() + "/33):");
            for (String id : ScenarioRegistry.getScenarioIds()) {
                System.out.println(" - " + id);
            }
            return EXIT_PASSED;
        }

        List<String> ids = options.caseId() != null
                ? List.of(options.caseId())
                : List.copyOf(ScenarioRegistry.getScenarioIds());
        System.out.println("Running " + ids.size() + " scenario(s) with seed "
                + options.seed() + "...");

        boolean allPassed = true;
        for (String id : ids) {
            Report report = execute(id, ScenarioRegistry.get(id), options.seed());
            report.save(options.seed());
            if (report.getStatus() == Report.Status.PASSED) {
                System.out.println("[PASSED] " + id);
            } else {
                allPassed = false;
                System.err.println("[" + report.getStatus() + "] " + id + " ("
                        + report.getFailurePhase() + "): " + report.getDiagnostics());
            }
        }
        return allPassed ? EXIT_PASSED : EXIT_FAILED;
    }

    /**
     * Runs one scenario and returns its report with a final verdict; never
     * throws, so a report is written for every selected case.
     *
     * @param scenario the registered scenario, or {@code null} if the id is unknown
     */
    static Report execute(String id, ScenarioRegistry.RunnableScenario scenario, long seed) {
        Report report = new Report(id, seed);
        if (scenario == null) {
            report.fail("SETUP", "Scenario not found", List.of());
            return report;
        }

        try {
            scenario.run(seed, report);
        } catch (Throwable t) {
            // Any failure, including an AssertionError from an oracle,
            // becomes the verdict instead of ending the run without a report.
            t.printStackTrace();
            report.fail("EXECUTION", describe(t), List.of());
            return report;
        }

        if (report.getStatus() != Report.Status.PASSED
                && report.getStatus() != Report.Status.FAILED) {
            report.fail("VERDICT", "Scenario returned without a verdict", List.of());
        }
        return report;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }

    /** The parsed command line; any unknown or incomplete option is an error. */
    record Options(boolean listOnly, String caseId, long seed) {

        static Options parse(String[] args) {
            boolean listOnly = false;
            String caseId = null;
            Long seed = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--list" -> listOnly = true;
                    case "--case" -> {
                        if (caseId != null) {
                            throw new IllegalArgumentException("--case given twice");
                        }
                        caseId = value(args, ++i, "--case");
                    }
                    case "--seed" -> {
                        if (seed != null) {
                            throw new IllegalArgumentException("--seed given twice");
                        }
                        String text = value(args, ++i, "--seed");
                        try {
                            seed = Long.parseLong(text);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--seed must be an integer: " + text);
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            return new Options(listOnly, caseId, seed != null ? seed : DEFAULT_SEED);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length || args[index].isEmpty() || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }
    }
}
