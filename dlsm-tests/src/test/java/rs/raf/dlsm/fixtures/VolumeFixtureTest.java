package rs.raf.dlsm.fixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.recovery.NodeIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The prepared volume against FORMAT.md §2, §4.1 and §5.1. */
class VolumeFixtureTest {

    @TempDir
    Path root;

    private List<String> entries(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writesTheDocumentedLayoutAndFiles() throws Exception {
        VolumeFixture.initialize(root);

        assertEquals(List.of("lsm", "node.json"), entries(root));
        assertEquals(List.of("MANIFEST.json", "sst", "tmp", "wal"), entries(root.resolve("lsm")));
        assertEquals("{\"formatVersion\":1,\"nodeId\":\"standalone\"}",
                Files.readString(root.resolve("node.json"), StandardCharsets.UTF_8));
        assertEquals("{\"formatVersion\":1,\"generation\":0,\"nextTableId\":1,"
                        + "\"flushedThroughSequence\":0,\"sstables\":[]}",
                Files.readString(root.resolve("lsm/MANIFEST.json"), StandardCharsets.UTF_8));
        assertEquals(root.resolve("lsm/wal"), VolumeFixture.walDir(root));
    }

    @Test
    void neverReplacesAnExistingVolume() throws Exception {
        VolumeFixture.initialize(root);

        assertThrows(FileAlreadyExistsException.class, () -> VolumeFixture.initialize(root));
    }

    // Both sides of the FORMAT.md contract agree: production readers accept the fixture.
    @Test
    void productionReadersAcceptTheVolume() throws Exception {
        VolumeFixture.initialize(root);
        DataLayout layout = new DataLayout(root);

        assertDoesNotThrow(() -> NodeIdentity.readAndCheck(layout.nodeFile(), VolumeFixture.NODE_ID));
        Manifest manifest = new ManifestManager(layout, new DefaultStorageIO(new NoOpFailpoints()))
                .loadAndValidate();
        assertEquals(Manifest.initial(), manifest);
    }
}
