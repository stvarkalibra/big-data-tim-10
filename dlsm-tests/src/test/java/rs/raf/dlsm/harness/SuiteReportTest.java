package rs.raf.dlsm.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The run report of PI §25.3. */
class SuiteReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private static Report passed(String id, long seed) {
        Report report = new Report(id, seed);
        report.pass();
        return report;
    }

    private JsonNode read(Path file) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(file));
    }

    @Test
    void writesTheShapeOfTheSpecExample() throws Exception {
        SuiteReport suite = new SuiteReport(123456, List.of("CP1-WAL-005"));
        Report report = new Report("CP1-WAL-005", 123456);
        report.pass();
        suite.add(report, 842);

        Path file = suite.write(dir);

        assertEquals(dir.resolve("checkpoint-1-123456.json"), file);
        JsonNode root = read(file);
        assertEquals(1, root.get("schemaVersion").intValue());
        assertEquals("checkpoint-1", root.get("suite").textValue());
        assertEquals("123456", root.get("seed").textValue(), "the seed is a string");
        assertEquals("CP1-WAL-005", root.get("selectedCaseIds").get(0).textValue());

        JsonNode entry = root.get("cases").get(0);
        assertEquals("CP1-WAL-005", entry.get("id").textValue());
        assertEquals("PASSED", entry.get("status").textValue());
        assertEquals("123456", entry.get("seed").textValue());
        assertEquals(842, entry.get("durationMs").longValue());
        assertTrue(entry.get("artifactPaths").isArray());
        assertFalse(entry.has("failurePhase"), "only failures carry a phase");
    }

    @Test
    void failedEntryCarriesPhaseDiagnosticsAndArtifacts() throws Exception {
        SuiteReport suite = new SuiteReport(7, List.of("CP1-API-003"));
        Report report = new Report("CP1-API-003", 7);
        report.fail("SCAN_MISMATCH", "expected [alpha]",
                List.of("reports/artifacts/CP1-API-003/trace.json"));
        suite.add(report, 12);

        JsonNode entry = read(suite.write(dir)).get("cases").get(0);
        assertEquals("FAILED", entry.get("status").textValue());
        assertEquals("SCAN_MISMATCH", entry.get("failurePhase").textValue());
        assertEquals("expected [alpha]", entry.get("diagnostics").textValue());
        assertEquals("reports/artifacts/CP1-API-003/trace.json",
                entry.get("artifactPaths").get(0).textValue());
    }

    @Test
    void oneFileHoldsEveryCaseOfTheRun() throws Exception {
        SuiteReport suite = new SuiteReport(7, List.of("CP1-API-003", "CP1-SST-006"));
        suite.add(passed("CP1-API-003", 7), 5);
        suite.write(dir);
        suite.add(passed("CP1-SST-006", 7), 9);
        Path file = suite.write(dir);

        // The second write replaces the first; earlier cases are not lost.
        JsonNode cases = read(file).get("cases");
        assertEquals(2, cases.size());
        assertEquals("CP1-API-003", cases.get(0).get("id").textValue());
        assertEquals("CP1-SST-006", cases.get(1).get("id").textValue());
        try (var files = Files.list(dir)) {
            assertEquals(List.of(file), files.toList(), "no temporary file is left");
        }
    }

    @Test
    void onlySelectedCasesAreAddedOnce() {
        SuiteReport suite = new SuiteReport(7, List.of("CP1-API-003"));
        assertThrows(IllegalArgumentException.class,
                () -> suite.add(passed("CP1-API-002", 7), 1));

        suite.add(passed("CP1-API-003", 7), 1);
        assertThrows(IllegalStateException.class,
                () -> suite.add(passed("CP1-API-003", 7), 1));
    }

    @Test
    void aReportThatCannotBeWrittenIsAnError() throws Exception {
        Path notADirectory = Files.createFile(dir.resolve("reports"));
        SuiteReport suite = new SuiteReport(7, List.of("CP1-API-003"));

        assertThrows(IOException.class, () -> suite.write(notADirectory));
    }
}
