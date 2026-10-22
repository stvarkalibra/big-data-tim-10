package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NodeIdentityTest {

    @TempDir
    Path root;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ===== Writing (FORMAT.md §4.1) =====

    @Test
    void writesExactDocumentedBytes() {
        assertArrayEquals(utf8("{\"formatVersion\":1,\"nodeId\":\"standalone\"}"),
                NodeIdentity.of("standalone").toJson());
    }

    @Test
    void writtenBytesParseBack() throws StorageException {
        for (String id : new String[]{"standalone", "storage-1", "a", "Node_2.x", "n".repeat(64)}) {
            NodeIdentity identity = NodeIdentity.of(id);
            assertEquals(identity, NodeIdentity.parse(identity.toJson()));
        }
    }

    @Test
    void rejectsInvalidIdentityWhenBuilding() {
        assertThrows(IllegalArgumentException.class, () -> NodeIdentity.of(""));
        assertThrows(IllegalArgumentException.class, () -> NodeIdentity.of("bad id"));
        assertThrows(IllegalArgumentException.class, () -> NodeIdentity.of("n".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> new NodeIdentity(2, "standalone"));
    }

    // ===== Strict reading =====

    @Test
    void acceptsWhitespaceAndFieldOrder() throws StorageException {
        assertEquals(NodeIdentity.of("standalone"), NodeIdentity.parse(utf8(
                "{\n  \"nodeId\" : \"standalone\",\n  \"formatVersion\" : 1\n}\n")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "null",
            "[]",
            "\"standalone\"",
            "{",
            "{}",
            "{\"formatVersion\":1}",
            "{\"nodeId\":\"standalone\"}",
            "{\"formatVersion\":2,\"nodeId\":\"standalone\"}",
            "{\"formatVersion\":0,\"nodeId\":\"standalone\"}",
            "{\"formatVersion\":\"1\",\"nodeId\":\"standalone\"}",
            "{\"formatVersion\":1.0,\"nodeId\":\"standalone\"}",
            "{\"formatVersion\":true,\"nodeId\":\"standalone\"}",
            "{\"formatVersion\":99999999999,\"nodeId\":\"standalone\"}",
            "{\"formatVersion\":1,\"nodeId\":7}",
            "{\"formatVersion\":1,\"nodeId\":null}",
            "{\"formatVersion\":1,\"nodeId\":\"\"}",
            "{\"formatVersion\":1,\"nodeId\":\"bad id\"}",
            "{\"formatVersion\":1,\"nodeId\":\"../etc\"}",
            "{\"formatVersion\":1,\"nodeId\":\"standalone\",\"extra\":1}",
            "{\"formatVersion\":1,\"nodeId\":\"standalone\",\"nodeId\":\"other\"}",
            "{\"formatVersion\":1,\"nodeId\":\"standalone\"} trailing",
            "{\"formatVersion\":1,\"nodeId\":\"standalone\"}{}"
    })
    void rejectsEverythingElseAsIdentityMismatch(String json) {
        assertMismatch(() -> NodeIdentity.parse(utf8(json)));
    }

    // ===== Reading the file =====

    @Test
    void readsFileNamingConfiguredNode() throws IOException, StorageException {
        Path file = Files.write(root.resolve("node.json"), NodeIdentity.of("standalone").toJson());

        assertEquals(NodeIdentity.of("standalone"), NodeIdentity.readAndCheck(file, "standalone"));
    }

    // CP1 §14.1: a value different from the configured node ID fails startup.
    @Test
    void differentConfiguredNodeIsMismatch() throws IOException {
        Path file = Files.write(root.resolve("node.json"), NodeIdentity.of("standalone").toJson());

        StorageException e = assertMismatch(() -> NodeIdentity.readAndCheck(file, "storage-1"));
        assertTrue(e.getMessage().contains("storage-1"));
        assertFalse(e.getMessage().contains(root.toString()), "no absolute paths in messages");
    }

    @Test
    void missingFileIsLeftToStartupClassification() {
        assertThrows(NoSuchFileException.class,
                () -> NodeIdentity.readAndCheck(root.resolve("node.json"), "standalone"));
    }

    private static StorageException assertMismatch(Executable call) {
        StorageException e = assertThrows(StorageException.class, call);
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(NodeIdentity.CATEGORY, e.category());
        return e;
    }
}
