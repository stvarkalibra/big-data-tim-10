
package rs.raf.dlsm.inspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.SSTableWriter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InspectSSTableCommandTest {

    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();

    private record Result(int code, JsonNode report) {
    }

    private Path writeTable() throws Exception {
        Path sstDir = directory.resolve("lsm").resolve("sst");
        Files.createDirectories(sstDir);

        var records = List.of(
                new BlockCodec.Record(
                        "alpha", 1, BlockCodec.PUT,
                        "A".getBytes(StandardCharsets.UTF_8)),
                new BlockCodec.Record(
                        "beta", 2, BlockCodec.DELETE, null),
                new BlockCodec.Record(
                        "gamma", 3, BlockCodec.PUT, new byte[0])
        );

        var table = SSTableWriter.build(
                42, records.iterator(), records.size());

        Path path = sstDir.resolve(table.meta().file());
        Files.write(path, table.bytes());

        return path;
    }

    private Result inspect() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (PrintStream stream = new PrintStream(
                output, true, StandardCharsets.UTF_8)) {

            int exitCode = InspectSSTableCommand.run(
                    directory, 42, stream);

            JsonNode report = json.readTree(
                    output.toString(StandardCharsets.UTF_8));

            return new Result(exitCode, report);
        }
    }

    @Test
    void inspectsValidTable() throws Exception {
        writeTable();

        Result result = inspect();

        assertEquals(0, result.code());
        assertTrue(result.report().get("valid").asBoolean());
        assertEquals(42, result.report().get("tableId").asLong());
        assertEquals(3, result.report().get("entryCount").asLong());
    }

    @Test
    void distinguishesDeleteFromEmptyPut() throws Exception {
        writeTable();

        JsonNode records = inspect().report().get("records");

        assertEquals("DELETE",
                records.get(1).get("kind").asText());
        assertTrue(records.get(1).get("valueLength").isNull());

        assertEquals("PUT",
                records.get(2).get("kind").asText());
        assertEquals(0,
                records.get(2).get("valueLength").asInt());
    }

    @Test
    void missingTableReturnsError() throws Exception {
        Result result = inspect();

        assertEquals(
                InspectSSTableCommand.EXIT_MISSING,
                result.code());

        assertEquals("MISSING",
                result.report().get("status").asText());
    }

    @Test
    void detectsCorruptedBlock() throws Exception {
        Path path = writeTable();

        byte[] bytes = Files.readAllBytes(path);
        bytes[SSTableWriter.HEADER_BYTES + 25] ^= 1;
        Files.write(path, bytes);

        Result result = inspect();

        assertEquals(
                InspectSSTableCommand.EXIT_CORRUPT,
                result.code());

        assertEquals("DATA_BLOCK",
                result.report().get("corruptionCategory").asText());
    }

    @Test
    void detectsTruncatedFile() throws Exception {
        Path path = writeTable();
        Files.write(path, new byte[]{1, 2, 3});

        Result result = inspect();

        assertEquals(
                InspectSSTableCommand.EXIT_CORRUPT,
                result.code());

        assertEquals("TRUNCATED_REGION",
                result.report().get("corruptionCategory").asText());
    }

    @Test
    void doesNotModifySstable() throws Exception {
        Path path = writeTable();
        byte[] before = Files.readAllBytes(path);

        assertEquals(0, inspect().code());

        assertArrayEquals(before, Files.readAllBytes(path));
    }
}
