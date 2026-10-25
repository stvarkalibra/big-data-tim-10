package rs.raf.dlsm.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.wal.WalCodec;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class InspectCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dataDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return InspectCommand.run(List.of(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    /** Segment 1 with PUT(alpha,"A"), DELETE(beta) and PUT(gamma,""), as in FORMAT.md §3.6. */
    private Path writeSegment() throws IOException {
        Path walDir = Files.createDirectories(dataDir.resolve("lsm/wal"));
        Path segment = walDir.resolve(WalFileNames.segmentFileName(1));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(WalCodec.encodeSegmentHeader(1));
        bytes.writeBytes(WalCodec.encodeFrame(InternalRecord.put("alpha", 1, new byte[] {'A'})));
        bytes.writeBytes(WalCodec.encodeFrame(InternalRecord.delete("beta", 2)));
        bytes.writeBytes(WalCodec.encodeFrame(InternalRecord.put("gamma", 3, new byte[0])));
        return Files.write(segment, bytes.toByteArray());
    }

    /** Every file under the data directory with its exact bytes. */
    private Map<String, String> snapshot() throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(dataDir)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                String name = dataDir.relativize(p).toString();
                files.put(name, Files.isDirectory(p)
                        ? "<dir>" : java.util.HexFormat.of().formatHex(Files.readAllBytes(p)));
            }
        }
        return files;
    }

    @Test
    void validWalPrintsJsonReportAndExitsZero() throws Exception {
        writeSegment();

        assertEquals(0, run("wal", "--data-dir", dataDir.toString()));

        JsonNode report = MAPPER.readTree(stdout());
        assertEquals("VALID", report.get("status").asText());
        assertEquals(1, report.get("segmentCount").asInt());
        assertEquals(3, report.get("recordCount").asInt());
        assertEquals("", stderr());
        // The report is the only output: exactly one JSON value, no log
        // lines before or after it, and no absolute paths.
        assertDoesNotThrow(() -> MAPPER.reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(stdout()));
        assertTrue(stdout().stripLeading().startsWith("{"));
        assertFalse(stdout().contains(dataDir.toString()));
    }

    @Test
    void tornTailIsReportedButNotTruncated() throws Exception {
        Path segment = writeSegment();
        byte[] whole = Files.readAllBytes(segment);
        Files.write(segment, java.util.Arrays.copyOf(whole, 120));   // cut the last frame
        Map<String, String> before = snapshot();

        assertEquals(0, run("wal", "--data-dir", dataDir.toString()));

        assertEquals("TORN_TAIL", MAPPER.readTree(stdout()).get("status").asText());
        assertEquals(before, snapshot(), "inspection must not change any byte");
        assertEquals(120, Files.size(segment));
    }

    @Test
    void corruptWalExitsNonZeroAndChangesNothing() throws Exception {
        Path segment = writeSegment();
        byte[] bytes = Files.readAllBytes(segment);
        bytes[55] ^= 0x01;                                            // value byte of alpha
        Files.write(segment, bytes);
        Map<String, String> before = snapshot();

        assertEquals(1, run("wal", "--data-dir", dataDir.toString()));

        assertEquals("CORRUPT", MAPPER.readTree(stdout()).get("status").asText());
        assertEquals(before, snapshot());
    }

    @Test
    void missingWalDirectoryCreatesNothing() throws Exception {
        assertEquals(2, run("wal", "--data-dir", dataDir.toString()));

        assertEquals("MISSING", MAPPER.readTree(stdout()).get("status").asText());
        assertEquals(Map.of("", "<dir>"), snapshot(), "no directory or LOCK was created");
    }

    @Test
    void defaultDataDirIsThePersistentVolume() {
        assertEquals(Path.of("/var/lib/dlsm"), InspectCommand.DEFAULT_DATA_DIR);
    }

    @Test
    void malformedCommandLinesAreUsageErrors() {
        for (List<String> args : List.of(
                List.<String>of(),
                List.of("waL"),
                List.of("manifest", "extra"),
                List.of("wal", "--data-dir"),
                List.of("wal", "--dir", "x"),
                List.of("wal", "--data-dir", "x", "extra"),
                List.of("wal", "extra"))) {
            out.reset();
            err.reset();

            assertEquals(InspectCommand.EXIT_USAGE, run(args.toArray(String[]::new)),
                    args.toString());
            assertEquals("", stdout(), "stdout stays clean for " + args);
            assertTrue(stderr().contains("usage: inspect wal"), args.toString());
        }
    }

    @Test
    void usageExitCodeDiffersFromInspectorCodes() {
        assertFalse(List.of(0, 1, 2, 3).contains(InspectCommand.EXIT_USAGE));
    }
}
