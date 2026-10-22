package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.wal.RecordingStorageIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeIdentityFileTest {

    private static final OpContext CTX = OpContext.background();

    @TempDir
    Path root;

    private DataLayout layout;

    @BeforeEach
    void createDirectories() throws IOException {
        layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
    }

    @Test
    void publishesExactBytesUnderFinalName() throws IOException, StorageException {
        NodeIdentityFile.publish(layout, NodeIdentity.of("standalone"),
                new RecordingStorageIO(new NoOpFailpoints()), CTX);

        assertArrayEquals(NodeIdentity.of("standalone").toJson(), Files.readAllBytes(layout.nodeFile()));
        assertEquals(NodeIdentity.of("standalone"),
                NodeIdentity.readAndCheck(layout.nodeFile(), "standalone"));
        assertFalse(Files.exists(layout.nodeTempFile()));
    }

    // FORMAT.md §4.1: temp file, force, atomic move without replace, force of the root.
    @Test
    void followsPublishProtocolInOrder() throws IOException {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints());

        NodeIdentityFile.publish(layout, NodeIdentity.of("standalone"), io, CTX);

        assertEquals(List.of(
                "delete node.json.tmp",
                "openNew node.json.tmp",
                "writeFully",
                "force",
                "atomicMoveNoReplace node.json",
                "fsyncDirectory " + root.getFileName()), io.calls());
    }

    // A crashed earlier attempt may leave a temporary file; it is not evidence.
    @Test
    void replacesLeftoverTemporaryFile() throws IOException {
        Files.write(layout.nodeTempFile(), "half-written".getBytes(StandardCharsets.US_ASCII));

        NodeIdentityFile.publish(layout, NodeIdentity.of("standalone"),
                new RecordingStorageIO(new NoOpFailpoints()), CTX);

        assertArrayEquals(NodeIdentity.of("standalone").toJson(), Files.readAllBytes(layout.nodeFile()));
    }

    // CP1 §14.1: an existing identity is never silently re-created.
    @Test
    void neverOverwritesExistingNodeFile() throws IOException {
        byte[] original = NodeIdentity.of("standalone").toJson();
        Files.write(layout.nodeFile(), original);

        assertThrows(FileAlreadyExistsException.class, () -> NodeIdentityFile.publish(
                layout, NodeIdentity.of("other"), new RecordingStorageIO(new NoOpFailpoints()), CTX));

        assertArrayEquals(original, Files.readAllBytes(layout.nodeFile()));
    }

    @Test
    void failureBeforeMoveLeavesNoNodeFile() throws IOException {
        for (String failing : new String[]{"writeFully", "force", "atomicMoveNoReplace"}) {
            Files.deleteIfExists(layout.nodeTempFile());
            RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints()).failOn(failing);

            assertThrows(IOException.class, () -> NodeIdentityFile.publish(
                    layout, NodeIdentity.of("standalone"), io, CTX), failing);

            assertFalse(Files.exists(layout.nodeFile()), failing);
        }
    }

    // The name was moved but its durability is unknown: the caller fails startup.
    @Test
    void failedRootForceIsReported() {
        RecordingStorageIO io = new RecordingStorageIO(new NoOpFailpoints()).failOn("fsyncDirectory");

        assertThrows(IOException.class, () -> NodeIdentityFile.publish(
                layout, NodeIdentity.of("standalone"), io, CTX));
    }
}
