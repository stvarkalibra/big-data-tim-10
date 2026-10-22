package rs.raf.dlsm.harness;

import java.util.List;

/**
 * The result of one scenario: the scenario records its verdict here and
 * {@link SuiteReport} writes it as one entry of the run report (PI §25.3).
 */
public class Report {
    public enum Status { PASSED, FAILED, SKIPPED }

    private final String scenarioId;
    private final long seed;
    private Status status;
    private String failurePhase;
    private String diagnostics;
    private List<String> artifactPaths = List.of();

    public Report(String scenarioId, long seed) {
        this.scenarioId = scenarioId;
        this.seed = seed;
        this.status = Status.SKIPPED;
    }

    /** Marks the scenario passed, unless it already failed: a failure is final. */
    public void pass() {
        if (status != Status.FAILED) {
            this.status = Status.PASSED;
        }
    }

    /**
     * Records a failure; the first one wins, since later ones are usually its effects.
     *
     * @param artifactPaths trace and inspection files for the failure; may be {@code null}
     */
    public void fail(String phase, String diagnostics, List<String> artifactPaths) {
        if (status == Status.FAILED) {
            return;
        }
        this.status = Status.FAILED;
        this.failurePhase = phase;
        this.diagnostics = diagnostics;
        this.artifactPaths = artifactPaths == null ? List.of() : List.copyOf(artifactPaths);
    }

    public String getScenarioId() { return scenarioId; }
    public long getSeed() { return seed; }
    public Status getStatus() { return status; }
    public String getFailurePhase() { return failurePhase; }
    public String getDiagnostics() { return diagnostics; }
    /** Never {@code null}; empty when the scenario saved no artifacts. */
    public List<String> getArtifactPaths() { return artifactPaths; }
}
