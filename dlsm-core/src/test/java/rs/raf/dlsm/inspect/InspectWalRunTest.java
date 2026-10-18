package rs.raf.dlsm.inspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.Bin;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.wal.WalCodec;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code dbctl inspect wal} behaviour (CP1 §25): exit codes, JSON output,
 * and that inspecting never changes a file, not even a torn or corrupt one.
 */
class InspectWalRunTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataDir;

    private static byte[] segment(long id, InternalRecord... records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(WalCodec.encodeSegmentHeader(id));
        for (InternalRecord record : records) {
            out.writeBytes(WalCodec.encodeFrame(record));
        }
        return out.toByteArray();
    }

    private static byte[] twoRecords() {
        return segment(1,
                InternalRecord.put("alpha", 1, "A".getBytes(StandardCharsets.US_ASCII)),
                InternalRecord.delete("beta", 2));
    }

    private Path writeSegment(long id, byte[] bytes) throws IOException {
        Path walDir = Files.createDirectories(dataDir.resolve("lsm/wal"));
        return Files.write(walDir.resolve(WalFileNames.segmentFileName(id)), bytes);
    }

    private record Run(int exitCode, String output) {
        JsonNode json() throws IOException {
            return JSON.readTree(output);
        }
    }

    private Run run(Path dir) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exitCode = InspectWal.run(dir, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return new Run(exitCode, buffer.toString(StandardCharsets.UTF_8));
    }

    // ===== Exit codes =====

    @Test
    void validLogExitsWithZero() throws IOException {
        writeSegment(1, twoRecords());

        Run run = run(dataDir);

        assertEquals(InspectWal.EXIT_OK, run.exitCode());
        assertEquals("VALID", run.json().get("status").asText());
    }

    @Test
    void tornTailIsReportedAndStillExitsWithZero() throws IOException {
        byte[] bytes = twoRecords();
        writeSegment(1, Arrays.copyOf(bytes, bytes.length - 4));

        Run run = run(dataDir);

        assertEquals(InspectWal.EXIT_OK, run.exitCode());
        assertEquals("TORN_TAIL", run.json().get("status").asText());
        assertEquals(60, run.json().at("/segments/0/tornTail/frameOffset").asLong());
    }

    @Test
    void corruptionExitsNonZero() throws IOException {
        byte[] bytes = twoRecords();
        bytes[55] ^= 0x01;
        writeSegment(1, bytes);

        Run run = run(dataDir);

        assertEquals(InspectWal.EXIT_CORRUPT, run.exitCode());
        assertEquals("CORRUPT", run.json().get("status").asText());
        assertEquals("CHECKSUM", run.json().at("/segments/0/corruption/category").asText());
    }

    @Test
    void unsupportedVersionExitsNonZero() throws IOException {
        byte[] bytes = twoRecords();
        Bin.putU16(bytes, 8, 2);
        Bin.putCrc32c(bytes, 0, 20, 20);
        writeSegment(1, bytes);

        Run run = run(dataDir);

        assertEquals(InspectWal.EXIT_CORRUPT, run.exitCode());
        assertEquals(2, run.json().at("/segments/0/formatVersion").asInt());
    }

    @Test
    void missingWalDirectoryExitsNonZero() throws IOException {
        Run run = run(dataDir);

        assertEquals(InspectWal.EXIT_MISSING, run.exitCode());
        assertEquals("MISSING", run.json().get("status").asText());
        assertEquals("lsm/wal", run.json().get("walDirectory").asText());
    }

    @Test
    void walPathThatIsAFileCountsAsMissing() throws IOException {
        Files.createDirectories(dataDir.resolve("lsm"));
        Files.write(dataDir.resolve("lsm/wal"), new byte[1]);

        assertEquals(InspectWal.EXIT_MISSING, run(dataDir).exitCode());
    }

    // ===== Output =====

    @Test
    void outputIsTheJsonOfTheReport() throws IOException {
        writeSegment(1, twoRecords());

        Run run = run(dataDir);

        // Parse both sides from text so int and long nodes compare equal.
        JsonNode expected = JSON.readTree(
                JSON.writeValueAsString(InspectWal.inspectLog(dataDir)));
        assertEquals(expected, run.json());
    }

    @Test
    void outputNeverContainsAbsolutePaths() throws IOException {
        String absolute = dataDir.toAbsolutePath().toString();

        assertFalse(run(dataDir).output().contains(absolute));

        writeSegment(1, twoRecords());
        assertFalse(run(dataDir).output().contains(absolute));
    }

    @Test
    void repeatedRunsPrintIdenticalOutput() throws IOException {
        writeSegment(3, segment(3, InternalRecord.put("c", 3, new byte[2])));
        writeSegment(1, twoRecords());

        assertEquals(run(dataDir).output(), run(dataDir).output());
    }

    // ===== Read-only (CP1 §25: never normalize, truncate, delete or repair) =====

    @Test
    void neverChangesAnyFile() throws IOException {
        byte[] torn = twoRecords();
        writeSegment(1, Arrays.copyOf(torn, torn.length - 4));
        byte[] corrupt = segment(2, InternalRecord.put("c", 3, new byte[1]));
        corrupt[30] ^= 0x01;
        writeSegment(2, corrupt);
        writeSegment(3, segment(3));
        Files.write(dataDir.resolve("lsm/wal/stray.bin"), new byte[]{1, 2, 3});
        Path tmp = Files.createDirectories(dataDir.resolve("lsm/tmp"));
        Files.write(tmp.resolve(WalFileNames.tempFileName(4)), segment(4));

        Map<String, Snapshot> before = snapshot();
        run(dataDir);
        run(dataDir);
        Map<String, Snapshot> after = snapshot();

        assertEquals(before, after);
    }

    private record Snapshot(String bytes, FileTime modified) {
    }

    /** Every file under the data directory: its exact bytes and modification time. */
    private Map<String, Snapshot> snapshot() throws IOException {
        Map<String, Snapshot> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(dataDir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(dataDir.relativize(path).toString(), new Snapshot(
                        Arrays.toString(Files.readAllBytes(path)),
                        Files.getLastModifiedTime(path)));
            }
        }
        return files;
    }
}
