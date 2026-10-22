package rs.raf.dlsm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The machine-readable report of one run (PI §25.3): one file
 * {@code reports/checkpoint-1-<seed>.json} with every selected case id and
 * exactly one entry per case.
 *
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "suite": "checkpoint-1",
 *   "seed": "123456",
 *   "selectedCaseIds": ["CP1-WAL-005"],
 *   "cases": [
 *     { "id": "CP1-WAL-005", "status": "PASSED", "seed": "123456",
 *       "durationMs": 842, "artifactPaths": [] }
 *   ]
 * }
 * </pre>
 *
 * <p>A failed entry also has {@code failurePhase} and {@code diagnostics}.
 * The seed is a string, as in the specification, so a 64-bit seed survives
 * JSON readers that use doubles.
 */
public final class SuiteReport {

    public static final int SCHEMA_VERSION = 1;
    public static final String SUITE = "checkpoint-1";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private record Entry(Report report, long durationMs) {
    }

    private final long seed;
    private final List<String> selectedCaseIds;
    private final List<Entry> cases = new ArrayList<>();

    public SuiteReport(long seed, List<String> selectedCaseIds) {
        this.seed = seed;
        this.selectedCaseIds = List.copyOf(selectedCaseIds);
    }

    /** Adds the final result of one selected case. */
    public void add(Report report, long durationMs) {
        Objects.requireNonNull(report, "report");
        String id = report.getScenarioId();
        if (!selectedCaseIds.contains(id)) {
            throw new IllegalArgumentException("Case was not selected: " + id);
        }
        if (cases.stream().anyMatch(e -> e.report().getScenarioId().equals(id))) {
            throw new IllegalStateException("Case already reported: " + id);
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        cases.add(new Entry(report, durationMs));
    }

    /** {@code <reportsDir>/checkpoint-1-<seed>.json}. */
    public Path file(Path reportsDir) {
        return reportsDir.resolve(SUITE + "-" + seed + ".json");
    }

    /**
     * Writes the whole report, replacing an earlier version of this run's
     * file in one atomic step, so a reader never sees half a report.
     *
     * @return the written file
     * @throws IOException if the report cannot be written; the run must
     *                     then end with a failure exit code
     */
    public Path write(Path reportsDir) throws IOException {
        Files.createDirectories(reportsDir);
        Path target = file(reportsDir);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, MAPPER.writeValueAsBytes(toJson()));
        Files.move(temp, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    ObjectNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("suite", SUITE);
        root.put("seed", String.valueOf(seed));
        ArrayNode selected = root.putArray("selectedCaseIds");
        selectedCaseIds.forEach(selected::add);

        ArrayNode entries = root.putArray("cases");
        for (Entry entry : cases) {
            Report report = entry.report();
            ObjectNode item = entries.addObject();
            item.put("id", report.getScenarioId());
            item.put("status", report.getStatus().name());
            item.put("seed", String.valueOf(report.getSeed()));
            item.put("durationMs", entry.durationMs());
            ArrayNode artifacts = item.putArray("artifactPaths");
            report.getArtifactPaths().forEach(artifacts::add);
            if (report.getStatus() == Report.Status.FAILED) {
                item.put("failurePhase", report.getFailurePhase());
                item.put("diagnostics", report.getDiagnostics());
            }
        }
        return root;
    }
}
