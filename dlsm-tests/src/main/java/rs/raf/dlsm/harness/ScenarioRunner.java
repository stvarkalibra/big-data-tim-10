package rs.raf.dlsm.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Runs Checkpoint 1 scenarios: {@code --list}, {@code --case ID} and
 * {@code --seed N} (plan §6.5).
 *
 * <p>The verdict of a scenario is what its {@link Report} says when it
 * returns: a scenario passes only by calling {@link Report#pass()} and never
 * failing. Every selected case gets exactly one entry in
 * {@code reports/checkpoint-1-<seed>.json}, even when it cannot start, and
 * the file is rewritten after each case, so it exists before the runner
 * exits (PI §25.3).
 *
 * <p>Exit codes: {@code 0} when every selected scenario passed, {@code 1}
 * when at least one did not or the report cannot be written, {@code 64}
 * for an invalid command line.
 */
public final class ScenarioRunner {

    static final int EXIT_PASSED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 64;

    static final long DEFAULT_SEED = 12345;

    static final Path REPORTS_DIR = Path.of("reports");

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
        return runCases(ids, ScenarioRegistry::get, options.seed(), REPORTS_DIR);
    }

    /**
     * Runs the selected cases in order and writes the run report before the
     * first case and after each one.
     *
     * @param lookup the scenario for an id, or {@code null} if it is unknown
     */
    static int runCases(List<String> ids,
                        Function<String, ScenarioRegistry.RunnableScenario> lookup,
                        long seed, Path reportsDir) {
        System.out.println("Running " + ids.size() + " scenario(s) with seed " + seed + "...");
        SuiteReport suite = new SuiteReport(seed, ids);
        // Written before the first case too, so even a run killed midway
        // leaves a report naming every selected case.
        if (!write(suite, reportsDir)) {
            return EXIT_FAILED;
        }

        boolean allPassed = true;
        for (String id : ids) {
            long started = System.nanoTime();
            Report report = execute(id, lookup.apply(id), seed);
            suite.add(report, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));

            if (report.getStatus() == Report.Status.PASSED) {
                System.out.println("[PASSED] " + id);
            } else {
                allPassed = false;
                System.err.println("[" + report.getStatus() + "] " + id + " ("
                        + report.getFailurePhase() + "): " + report.getDiagnostics());
            }

            if (!write(suite, reportsDir)) {
                return EXIT_FAILED;
            }
        }
        System.out.println("Report: " + suite.file(reportsDir));
        return allPassed ? EXIT_PASSED : EXIT_FAILED;
    }

    private static boolean write(SuiteReport suite, Path reportsDir) {
        try {
            suite.write(reportsDir);
            return true;
        } catch (IOException e) {
            // Without a report the run cannot be graded or diagnosed.
            System.err.println("Cannot write the report " + suite.file(reportsDir) + ": " + e);
            return false;
        }
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
