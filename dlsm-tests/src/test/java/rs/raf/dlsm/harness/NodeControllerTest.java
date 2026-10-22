package rs.raf.dlsm.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Locating the production server jar; no node is started. */
class NodeControllerTest {

    @TempDir
    Path dir;

    @Test
    void configuredJarIsUsed() throws Exception {
        Path jar = Files.createFile(dir.resolve("server.jar"));

        assertEquals(jar, NodeController.serverJar(jar.toString()));
    }

    @Test
    void missingJarFailsAtOnceAndNamesTheFix() {
        IOException e = assertThrows(IOException.class,
                () -> NodeController.serverJar(dir.resolve("missing.jar").toString()));

        assertTrue(e.getMessage().contains("missing.jar"));
        assertTrue(e.getMessage().contains(NodeController.SERVER_JAR_ENV));
    }

    @Test
    void unsetMeansTheJarOfThisRepository() {
        // Relative to the working directory: under Maven that is dlsm-tests,
        // where the default path does not exist.
        IOException e = assertThrows(IOException.class, () -> NodeController.serverJar(null));
        assertTrue(e.getMessage().contains("dlsm-server-1.0-SNAPSHOT.jar"));
    }
}
