package rs.raf.dlsm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Report {
    public enum Status { PASSED, FAILED, SKIPPED }

    private String scenarioId;
    private long seed;
    private Status status;
    private String failurePhase;
    private String diagnostics;
    private List<String> artifactPaths;

    public Report(String scenarioId, long seed) {
        this.scenarioId = scenarioId;
        this.seed = seed;
        this.status = Status.SKIPPED;
    }

    public void pass() {
        this.status = Status.PASSED;
    }

    public void fail(String phase, String diagnostics, List<String> artifactPaths) {
        this.status = Status.FAILED;
        this.failurePhase = phase;
        this.diagnostics = diagnostics;
        this.artifactPaths = artifactPaths;
    }

    public void save(long seed) {
        try {
            Path reportsDir = Path.of("reports");
            Files.createDirectories(reportsDir);
            File file = reportsDir.resolve("checkpoint-1-" + seed + ".json").toFile();

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(file, this);
        } catch (IOException e) {
            System.err.println("Failed to write test report: " + e.getMessage());
        }
    }

    // Getters and Setters for Jackson serialization
    public String getScenarioId() { return scenarioId; }
    public long getSeed() { return seed; }
    public Status getStatus() { return status; }
    public String getFailurePhase() { return failurePhase; }
    public String getDiagnostics() { return diagnostics; }
    public List<String> getArtifactPaths() { return artifactPaths; }
}
