package rs.raf.dlsm.inspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.wal.RecordingStorageIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class InspectManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private DataLayout layout;
    private ManifestManager manifests;

    @BeforeEach
    void setUp() throws IOException {
        layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        manifests = new ManifestManager(layout, new RecordingStorageIO(new NoOpFailpoints()));
    }

    private record Result(int exitCode, JsonNode report) {
    }

    private Result inspect() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = InspectManifest.run(root, new PrintStream(out, true, StandardCharsets.UTF_8));
        return new Result(exit, MAPPER.readTree(out.toString(StandardCharsets.UTF_8)));
    }

    private static SSTableMeta table(long id, long minSeq, long maxSeq, long size) {
        return new SSTableMeta(id, SSTableMeta.fileName(id), "a" + id, "z" + id, minSeq, maxSeq, 3, size);
    }

    private void writeTableFile(long id, int size) throws IOException {
        Files.write(layout.sstDir().resolve(SSTableMeta.fileName(id)), new byte[size]);
    }

    private void publish(SSTableMeta... tables) throws Exception {
        manifests.publishInitialManifest(OpContext.background());
        long next = 1;
        for (SSTableMeta table : tables) {
            next = Math.max(next, table.tableId() + 1);
        }
        long highWater = 0;
        for (SSTableMeta table : tables) {
            highWater = Math.max(highWater, table.maxSequence());
        }
        if (tables.length > 0) {
            manifests.publish(new Manifest(1, 1, next, highWater, List.of(tables)), OpContext.background());
        }
    }

    private List<String> tree() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.map(p -> root.relativize(p).toString()).sorted().toList();
        }
    }

    @Test
    void missingManifestExitsTwo() throws IOException {
        Result result = inspect();

        assertEquals(InspectManifest.EXIT_MISSING, result.exitCode());
        assertEquals("MISSING", result.report().path("status").asText());
        assertEquals("lsm/MANIFEST.json", result.report().path("manifestFile").asText());
    }

    @Test
    void initialManifestIsValid() throws Exception {
        publish();

        Result result = inspect();

        assertEquals(InspectManifest.EXIT_OK, result.exitCode());
        JsonNode report = result.report();
        assertEquals("VALID", report.path("status").asText());
        assertEquals(1, report.path("formatVersion").asInt());
        assertEquals(0, report.path("generation").asLong());
        assertEquals(1, report.path("nextTableId").asLong());
        assertEquals(0, report.path("flushedThroughSequence").asLong());
        assertEquals(0, report.path("sstableCount").asInt());
        assertTrue(report.path("error").isNull());
        assertEquals(0, report.path("sstables").size());
    }

    @Test
    void reportsTablesInIdOrderWithOrphansAndTemporaryManifests() throws Exception {
        writeTableFile(2, 40);
        writeTableFile(7, 70);
        publish(table(7, 11, 20, 70), table(2, 1, 10, 40));
        writeTableFile(9, 5);
        Files.write(layout.sstDir().resolve("notes.txt"), new byte[1]);
        Files.write(layout.tmpDir().resolve("MANIFEST-00000000000000000004.tmp"), new byte[3]);
        Files.write(layout.tmpDir().resolve("sst-00000000000000000010.sst.tmp"), new byte[3]);
        List<String> before = tree();

        Result result = inspect();

        assertEquals(InspectManifest.EXIT_OK, result.exitCode());
        JsonNode report = result.report();
        assertEquals("VALID", report.path("status").asText());
        assertEquals(1, report.path("generation").asLong());
        assertEquals(8, report.path("nextTableId").asLong());
        assertEquals(20, report.path("flushedThroughSequence").asLong());
        assertEquals(2, report.path("sstableCount").asInt());
        assertEquals(2, report.path("sstables").path(0).path("tableId").asLong());
        assertEquals(7, report.path("sstables").path(1).path("tableId").asLong());
        JsonNode first = report.path("sstables").path(0);
        assertEquals("sst-00000000000000000002.sst", first.path("file").asText());
        assertEquals("a2", first.path("minKey").asText());
        assertEquals(10, first.path("maxSequence").asLong());
        assertEquals("PRESENT", first.path("fileStatus").asText());
        assertEquals(40, first.path("sizeOnDisk").asLong());
        assertEquals(List.of("sst-00000000000000000009.sst"),
                MAPPER.convertValue(report.path("orphanTables"), List.class));
        assertEquals(List.of("MANIFEST-00000000000000000004.tmp"),
                MAPPER.convertValue(report.path("temporaryManifests"), List.class));
        assertEquals(before, tree());
    }

    @Test
    void invalidManifestIsCorrupt() throws IOException {
        Files.writeString(layout.manifestFile(), "{\"formatVersion\":1,");

        Result result = inspect();

        assertEquals(InspectManifest.EXIT_CORRUPT, result.exitCode());
        assertEquals("CORRUPT", result.report().path("status").asText());
        assertEquals("MANIFEST_INVALID", result.report().path("error").path("category").asText());
    }

    @Test
    void unsupportedVersionIsCorrupt() throws IOException {
        Files.writeString(layout.manifestFile(),
                "{\"formatVersion\":2,\"generation\":0,\"nextTableId\":1,\"flushedThroughSequence\":0,\"sstables\":[]}");

        Result result = inspect();

        assertEquals(InspectManifest.EXIT_CORRUPT, result.exitCode());
        assertEquals("MANIFEST_INVALID", result.report().path("error").path("category").asText());
    }

    @Test
    void missingReferencedTableIsCorrupt() throws Exception {
        writeTableFile(1, 30);
        publish(table(1, 1, 5, 30), table(3, 6, 9, 30));

        Result result = inspect();

        assertEquals(InspectManifest.EXIT_CORRUPT, result.exitCode());
        assertEquals("CORRUPT", result.report().path("status").asText());
        assertEquals(InspectManifest.REFERENCED_FILE_MISSING, result.report().path("error").path("category").asText());
        assertEquals("MISSING", result.report().path("sstables").path(1).path("fileStatus").asText());
        assertTrue(result.report().path("sstables").path(1).path("sizeOnDisk").isNull());
    }

    @Test
    void sizeMismatchIsCorrupt() throws Exception {
        writeTableFile(1, 29);
        publish(table(1, 1, 5, 30));

        Result result = inspect();

        assertEquals(InspectManifest.EXIT_CORRUPT, result.exitCode());
        assertEquals(InspectManifest.MANIFEST_TABLE_MISMATCH, result.report().path("error").path("category").asText());
        assertEquals("SIZE_MISMATCH", result.report().path("sstables").path(0).path("fileStatus").asText());
        assertEquals(29, result.report().path("sstables").path(0).path("sizeOnDisk").asLong());
    }

    @Test
    void directoryInPlaceOfManifestIsCorrupt() throws IOException {
        Files.createDirectory(layout.manifestFile());

        Result result = inspect();

        assertEquals(InspectManifest.EXIT_CORRUPT, result.exitCode());
        assertEquals("MANIFEST_INVALID", result.report().path("error").path("category").asText());
    }
}
