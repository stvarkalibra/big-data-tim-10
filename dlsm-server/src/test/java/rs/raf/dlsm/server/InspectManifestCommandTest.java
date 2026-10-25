package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.manifest.Manifest;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InspectManifestCommandTest {

    @TempDir
    Path root;

    @Test
    void routesManifestTargetWithDataDir() throws Exception {
        Files.createDirectories(root.resolve("lsm"));
        Files.write(root.resolve("lsm").resolve("MANIFEST.json"), Manifest.initial().toJson());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = InspectCommand.run(List.of("manifest", "--data-dir", root.toString()),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        assertEquals("VALID", new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8))
                .path("status").asText());
    }

    @Test
    void missingManifestExitsTwo() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = InspectCommand.run(List.of("manifest", "--data-dir", root.toString()),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertEquals(2, exit);
    }

    @Test
    void badArgumentsAreUsageErrors() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = InspectCommand.run(List.of("manifest", "--data-dir"),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(InspectCommand.EXIT_USAGE, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("inspect manifest"));
    }
}
