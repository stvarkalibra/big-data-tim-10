package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.wal.WalCodec;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** One test per row of FORMAT.md §4.3, plus what must never count as evidence. */
class StartupClassifierTest {

    private static final String NODE = "standalone";
    private static final byte[] EMPTY_MANIFEST = ("{\"formatVersion\":1,\"generation\":0,"
            + "\"nextTableId\":1,\"flushedThroughSequence\":0,\"sstables\":[]}")
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private DataLayout layout() {
        return new DataLayout(root);
    }

    private StartupState classify() throws IOException, StorageException {
        return StartupClassifier.classify(layout(), NODE);
    }

    private void createDirectories() throws IOException {
        for (Path dir : layout().requiredDirectories()) {
            Files.createDirectories(dir);
        }
    }

    private void writeNode(String nodeId) throws IOException {
        Files.write(layout().nodeFile(), NodeIdentity.of(nodeId).toJson());
    }

    private void writeManifest() throws IOException {
        Files.write(layout().manifestFile(), EMPTY_MANIFEST);
    }

    private void writeWalSegment(long id) throws IOException {
        Files.write(layout().walDir().resolve(WalFileNames.segmentFileName(id)),
                WalCodec.encodeSegmentHeader(id));
    }

    private void writeSstFile() throws IOException {
        Files.write(layout().sstDir().resolve("sst-00000000000000000001.sst"), new byte[]{1});
    }

    // ===== NEW_DATABASE =====

    @Test
    void missingRootIsNewDatabase() throws Exception {
        DataLayout missing = new DataLayout(root.resolve("not-created-yet"));

        assertEquals(StartupState.NEW_DATABASE, StartupClassifier.classify(missing, NODE));
    }

    @Test
    void emptyRootIsNewDatabase() throws Exception {
        assertEquals(StartupState.NEW_DATABASE, classify());
    }

    // Init crashed after step 1: directories exist, node.json does not.
    @Test
    void directoriesWithoutNodeFileAreNewDatabase() throws Exception {
        createDirectories();

        assertEquals(StartupState.NEW_DATABASE, classify());
    }

    // tmp/ is never the source of published state, not even a finished temp file.
    @Test
    void temporaryFilesAreNeverEvidence() throws Exception {
        createDirectories();
        Files.write(layout().nodeTempFile(), NodeIdentity.of(NODE).toJson());
        Files.write(layout().tmpDir().resolve(WalFileNames.tempFileName(1)),
                WalCodec.encodeSegmentHeader(1));
        Files.write(layout().tmpDir().resolve("MANIFEST-00000000000000000000.tmp"), EMPTY_MANIFEST);

        assertEquals(StartupState.NEW_DATABASE, classify());
    }

    // ===== No node.json next to stable data =====

    @Test
    void manifestWithoutNodeFileFails() throws Exception {
        createDirectories();
        writeManifest();

        assertFailure(NodeIdentity.CATEGORY, this::classify);
    }

    @Test
    void walSegmentWithoutNodeFileFails() throws Exception {
        createDirectories();
        writeWalSegment(1);

        assertFailure(NodeIdentity.CATEGORY, this::classify);
    }

    @Test
    void sstableWithoutNodeFileFails() throws Exception {
        createDirectories();
        writeSstFile();

        assertFailure(NodeIdentity.CATEGORY, this::classify);
    }

    // Any entry in wal/ or sst/ means "not a new empty database".
    @Test
    void unknownFileInWalDirectoryCountsAsStableData() throws Exception {
        createDirectories();
        Files.write(layout().walDir().resolve("stray.bin"), new byte[]{1});

        assertFailure(NodeIdentity.CATEGORY, this::classify);
    }

    // ===== Invalid or foreign node.json =====

    @Test
    void invalidNodeFileFails() throws Exception {
        createDirectories();
        Files.write(layout().nodeFile(), "{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8));

        assertFailure(NodeIdentity.CATEGORY, this::classify);
    }

    @Test
    void nodeFileOfAnotherNodeFails() throws Exception {
        createDirectories();
        writeNode("storage-1");
        writeManifest();

        assertFailure(NodeIdentity.CATEGORY, this::classify);
    }

    @Test
    void nodeFileThatIsADirectoryFails() throws Exception {
        createDirectories();
        Files.createDirectory(layout().nodeFile());

        assertFailure(NodeIdentity.CATEGORY, this::classify);
    }

    // ===== Missing directory next to node.json =====

    @Test
    void everyMissingRequiredDirectoryFails() throws Exception {
        for (String missing : new String[]{"lsm/wal", "lsm/sst", "lsm/tmp"}) {
            createDirectories();
            writeNode(NODE);
            writeManifest();
            Files.delete(root.resolve(missing));

            StorageException e = assertFailure(StartupClassifier.CATEGORY_DIRECTORY_LAYOUT,
                    this::classify);
            assertTrue(e.getMessage().contains(missing), e.getMessage());

            Files.createDirectories(root.resolve(missing));
        }
    }

    @Test
    void missingLsmDirectoryFails() throws Exception {
        writeNode(NODE);

        StorageException e = assertFailure(StartupClassifier.CATEGORY_DIRECTORY_LAYOUT, this::classify);
        assertTrue(e.getMessage().contains("lsm"), e.getMessage());
    }

    // ===== Valid node.json =====

    @Test
    void manifestMeansExistingDatabase() throws Exception {
        createDirectories();
        writeNode(NODE);
        writeManifest();

        assertEquals(StartupState.EXISTING, classify());
    }

    @Test
    void manifestWithWalAndSstIsExistingDatabase() throws Exception {
        createDirectories();
        writeNode(NODE);
        writeManifest();
        writeWalSegment(3);
        writeSstFile();

        assertEquals(StartupState.EXISTING, classify());
    }

    @Test
    void walWithoutManifestFails() throws Exception {
        createDirectories();
        writeNode(NODE);
        writeWalSegment(1);

        assertFailure(StartupClassifier.CATEGORY_MANIFEST_INVALID, this::classify);
    }

    @Test
    void sstableWithoutManifestFails() throws Exception {
        createDirectories();
        writeNode(NODE);
        writeSstFile();

        assertFailure(StartupClassifier.CATEGORY_MANIFEST_INVALID, this::classify);
    }

    // Init crashed after step 2: continue at step 3.
    @Test
    void nodeFileAloneResumesAtManifest() throws Exception {
        createDirectories();
        writeNode(NODE);

        assertEquals(StartupState.RESUME_AT_MANIFEST, classify());
    }

    @Test
    void leftoverTemporaryFilesStillResumeAtManifest() throws Exception {
        createDirectories();
        writeNode(NODE);
        Files.write(layout().tmpDir().resolve("MANIFEST-00000000000000000000.tmp"), EMPTY_MANIFEST);

        assertEquals(StartupState.RESUME_AT_MANIFEST, classify());
    }

    // ===== Read-only, no absolute paths =====

    @Test
    void classificationChangesNothing() throws Exception {
        createDirectories();
        writeNode("storage-1");
        writeWalSegment(1);
        Files.write(layout().nodeTempFile(), new byte[]{9});
        Map<String, String> before = snapshot();

        assertThrows(StorageException.class, this::classify);

        assertEquals(before, snapshot());
    }

    @Test
    void failureMessagesHaveNoAbsolutePaths() throws Exception {
        createDirectories();
        writeWalSegment(1);
        String absolute = root.toAbsolutePath().toString();

        StorageException noNode = assertThrows(StorageException.class, this::classify);
        writeNode(NODE);
        StorageException noManifest = assertThrows(StorageException.class, this::classify);
        Files.delete(layout().walDir().resolve(WalFileNames.segmentFileName(1)));
        Files.delete(layout().walDir());
        StorageException noDir = assertThrows(StorageException.class, this::classify);

        for (StorageException e : new StorageException[]{noNode, noManifest, noDir}) {
            assertFalse(e.getMessage().contains(absolute), e.getMessage());
        }
    }

    private static StorageException assertFailure(String category, Executable call) {
        StorageException e = assertThrows(StorageException.class, call);
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(category, e.category());
        return e;
    }

    private Map<String, String> snapshot() throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                String content = Files.isRegularFile(path)
                        ? Arrays.toString(Files.readAllBytes(path))
                        : "<dir>";
                files.put(root.relativize(path).toString(), content);
            }
        }
        return files;
    }
}
