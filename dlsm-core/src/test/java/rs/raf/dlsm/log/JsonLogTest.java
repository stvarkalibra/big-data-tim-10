
package rs.raf.dlsm.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonLogTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-10-18T10:00:00Z"),
            ZoneOffset.UTC
    );

    private static JsonLog create(ByteArrayOutputStream buffer) {
        return new JsonLog(
                "node-1",
                new PrintStream(buffer, true, StandardCharsets.UTF_8),
                FIXED_CLOCK
        );
    }

    @Test
    void writesJsonLineWithRequiredFields() {
        var buffer = new ByteArrayOutputStream();
        var log = create(buffer);

        log.info("SSTABLE_WRITTEN", Map.of(
                "tableId", 42,
                "sequence", 100
        ));

        String output = buffer.toString(StandardCharsets.UTF_8);

        assertTrue(output.contains(
                "\"timestamp\":\"2026-10-18T10:00:00Z\""));
        assertTrue(output.contains("\"level\":\"INFO\""));
        assertTrue(output.contains("\"event\":\"SSTABLE_WRITTEN\""));
        assertTrue(output.contains("\"nodeId\":\"node-1\""));
        assertTrue(output.contains("\"tableId\":42"));
        assertTrue(output.contains("\"sequence\":100"));
        assertEquals(1, output.lines().count());
    }

    @Test
    void writesMultipleIndependentLines() {
        var buffer = new ByteArrayOutputStream();
        var log = create(buffer);

        log.info("STARTUP", Map.of());
        log.warn("RECOVERY_WARNING", Map.of("walSegmentId", 5));

        assertEquals(
                2,
                buffer.toString(StandardCharsets.UTF_8)
                        .lines().count()
        );
    }

    @Test
    void escapesSpecialCharacters() {
        var buffer = new ByteArrayOutputStream();
        var log = create(buffer);

        log.error("IO_FAILURE", Map.of(
                "errorCode", "IO_ERROR",
                "message", "Disk \"full\"\nRetry"
        ));

        String output = buffer.toString(StandardCharsets.UTF_8);

        assertTrue(output.contains(
                "Disk \\\"full\\\"\\nRetry"));
        assertEquals(1, output.lines().count());
    }

    @Test
    void rejectsPayloadFields() {
        var buffer = new ByteArrayOutputStream();
        var log = create(buffer);

        assertThrows(IllegalArgumentException.class, () ->
                log.info("WRITE", Map.of(
                        "value", "secret data"
                )));

        assertEquals("", buffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidEvent() {
        var buffer = new ByteArrayOutputStream();
        var log = create(buffer);

        assertThrows(IllegalArgumentException.class, () ->
                log.info("Some random message", Map.of()));
    }

    @Test
    void logsCorruptionDiagnostics() {
        var buffer = new ByteArrayOutputStream();
        var log = create(buffer);

        log.error("SSTABLE_CORRUPTION", Map.of(
                "tableId", 7,
                "corruptionCategory", "DATA_BLOCK",
                "fileOffset", 4096L,
                "errorCode", "CORRUPTION"
        ));

        String output = buffer.toString(StandardCharsets.UTF_8);

        assertTrue(output.contains(
                "\"corruptionCategory\":\"DATA_BLOCK\""));
        assertTrue(output.contains("\"fileOffset\":4096"));
        assertTrue(output.contains("\"errorCode\":\"CORRUPTION\""));
    }
}
