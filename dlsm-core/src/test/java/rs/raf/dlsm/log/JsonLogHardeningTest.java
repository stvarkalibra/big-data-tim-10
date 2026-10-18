
package rs.raf.dlsm.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JsonLogHardeningTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-18T12:00:00Z"),
            ZoneOffset.UTC
    );

    private record Fixture(
            JsonLog log,
            ByteArrayOutputStream output
    ) {
        String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private Fixture create() {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        PrintStream stream = new PrintStream(
                output,
                true,
                StandardCharsets.UTF_8
        );

        return new Fixture(
                new JsonLog("node-test", stream, CLOCK),
                output
        );
    }

    @Test
    void writesRequiredFields() {
        Fixture fixture = create();

        fixture.log().info(
                "SSTABLE_OPENED",
                Map.of("tableId", 42L)
        );

        String json = fixture.text();

        assertTrue(json.contains(
                "\"timestamp\":\"2026-10-18T12:00:00Z\""));
        assertTrue(json.contains("\"level\":\"INFO\""));
        assertTrue(json.contains("\"event\":\"SSTABLE_OPENED\""));
        assertTrue(json.contains("\"nodeId\":\"node-test\""));
        assertTrue(json.contains("\"tableId\":42"));

        assertEquals(1, json.lines().count());
    }

    @Test
    void supportsAllFourLogLevels() {
        Fixture fixture = create();

        fixture.log().debug("DEBUG_EVENT", Map.of());
        fixture.log().info("INFO_EVENT", Map.of());
        fixture.log().warn("WARN_EVENT", Map.of());
        fixture.log().error("ERROR_EVENT", Map.of());

        String output = fixture.text();

        assertEquals(4, output.lines().count());
        assertTrue(output.contains("\"level\":\"DEBUG\""));
        assertTrue(output.contains("\"level\":\"INFO\""));
        assertTrue(output.contains("\"level\":\"WARN\""));
        assertTrue(output.contains("\"level\":\"ERROR\""));
    }

    @Test
    void escapesSpecialCharactersCorrectly() {
        Fixture fixture = create();

        fixture.log().error("IO_FAILURE", Map.of(
                "message", "Quote \" Slash \\ Newline\nTab\t"
        ));

        String output = fixture.text();

        assertTrue(output.contains("Quote \\\""));
        assertTrue(output.contains("Slash \\\\"));
        assertTrue(output.contains("Newline\\n"));
        assertTrue(output.contains("Tab\\t"));

        assertEquals(1, output.lines().count());
    }

    @Test
    void encodesControlCharacters() {
        Fixture fixture = create();

        fixture.log().warn("CONTROL_CHAR", Map.of(
                "message", "a\u0000b\u001fc"
        ));

        String output = fixture.text();

        assertTrue(output.contains("a\\u0000b\\u001fc"));
        assertEquals(1, output.lines().count());
    }

    @Test
    void preservesValidUnicode() {
        Fixture fixture = create();

        fixture.log().info("UNICODE_EVENT", Map.of(
                "message", "Čvor \uD83D\uDE80"
        ));

        String output = fixture.text();

        assertTrue(output.contains("Čvor"));
        assertTrue(output.contains("\uD83D\uDE80"));
        assertEquals(1, output.lines().count());
    }

    @Test
    void escapesUnpairedSurrogates() {
        Fixture fixture = create();

        fixture.log().info("BAD_UNICODE", Map.of(
                "message", "before\uD800after\uDC00"
        ));

        String output = fixture.text();

        assertTrue(output.contains(
                "before\\ud800after\\udc00"));
        assertEquals(1, output.lines().count());
    }

    @Test
    void rejectsNonFiniteNumbersWithoutWritingAnything() {
        Fixture fixture = create();

        for (Number value : List.of(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Float.NaN,
                Float.POSITIVE_INFINITY
        )) {
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.log().info(
                            "INVALID_NUMBER",
                            Map.of("sequence", value)
                    ));
        }

        assertEquals("", fixture.text());
    }

    @Test
    void acceptsBigDecimalExactly() {
        Fixture fixture = create();

        fixture.log().info("LARGE_NUMBER", Map.of(
                "sequence", new BigDecimal("123456789.125")
        ));

        assertTrue(fixture.text().contains(
                "\"sequence\":123456789.125"));
    }

    @Test
    void rejectsCustomNumberImplementations() {
        Fixture fixture = create();

        Number unsafeNumber = new Number() {
            @Override
            public int intValue() {
                return 1;
            }

            @Override
            public long longValue() {
                return 1;
            }

            @Override
            public float floatValue() {
                return 1;
            }

            @Override
            public double doubleValue() {
                return 1;
            }

            @Override
            public String toString() {
                return "not-valid-json";
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> fixture.log().info(
                        "BAD_NUMBER",
                        Map.of("sequence", unsafeNumber)
                ));

        assertEquals("", fixture.text());
    }

    @Test
    void rejectsPayloadAndUnknownFields() {
        Fixture fixture = create();

        assertThrows(IllegalArgumentException.class,
                () -> fixture.log().info(
                        "PUT_COMPLETED",
                        Map.of("value", "SECRET")
                ));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.log().info(
                        "PUT_COMPLETED",
                        Map.of("payloadBase64", "U0VDUkVU")
                ));

        assertEquals("", fixture.text());
    }

    @Test
    void rejectsUnsupportedObjects() {
        Fixture fixture = create();

        assertThrows(IllegalArgumentException.class,
                () -> fixture.log().info(
                        "INVALID_TYPE",
                        Map.of("message", new byte[]{1, 2, 3})
                ));

        assertEquals("", fixture.text());
    }

    @Test
    void logsSstableCorruptionDiagnostics() {
        Fixture fixture = create();

        fixture.log().error("SSTABLE_CORRUPTION", Map.of(
                "tableId", 9L,
                "corruptionCategory", "DATA_BLOCK",
                "fileOffset", 4096L,
                "errorCode", "STORAGE_IO"
        ));

        String output = fixture.text();

        assertTrue(output.contains("\"tableId\":9"));
        assertTrue(output.contains(
                "\"corruptionCategory\":\"DATA_BLOCK\""));
        assertTrue(output.contains("\"fileOffset\":4096"));
        assertTrue(output.contains(
                "\"errorCode\":\"STORAGE_IO\""));
    }

    @Test
    void writesNullFieldsAsJsonNull() {
        Fixture fixture = create();

        Map<String, Object> fields = new HashMap<>();
        fields.put("requestId", null);
        fields.put("tableId", 42);

        fixture.log().info("NULL_FIELD", fields);

        assertTrue(fixture.text().contains("\"requestId\":null"));
        assertTrue(fixture.text().contains("\"tableId\":42"));
    }

    @Test
    void neverWritesPartialLineForInvalidFields() {
        Fixture fixture = create();

        Map<String, Object> fields = new HashMap<>();
        fields.put("sequence", 42);
        fields.put("value", "SHOULD_NOT_BE_LOGGED");

        assertThrows(IllegalArgumentException.class,
                () -> fixture.log().info("INVALID", fields));

        assertEquals("", fixture.text());
    }

    @Test
    void concurrentLoggingProducesWholeLines()
            throws Exception {

        Fixture fixture = create();

        int threads = 8;
        int messagesPerThread = 50;

        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();

        try (var executor =
                     Executors.newFixedThreadPool(threads)) {

            for (int thread = 0; thread < threads; thread++) {
                final int threadId = thread;

                executor.submit(() -> {
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Start latch timed out");
                        }

                        for (int i = 0; i < messagesPerThread; i++) {
                            fixture.log().info(
                                    "CONCURRENT_EVENT",
                                    Map.of(
                                            "sequence",
                                            (long) threadId * 1000 + i
                                    )
                            );
                        }
                    } catch (Throwable error) {
                        synchronized (failures) {
                            failures.add(error);
                        }
                    }
                });
            }

            start.countDown();
        }

        assertTrue(failures.isEmpty(), failures.toString());

        List<String> lines = fixture.text().lines().toList();

        assertEquals(
                threads * messagesPerThread,
                lines.size()
        );

        for (String line : lines) {
            assertTrue(line.startsWith("{"));
            assertTrue(line.endsWith("}"));
            assertTrue(line.contains(
                    "\"event\":\"CONCURRENT_EVENT\""));
            assertTrue(line.contains("\"sequence\":"));
        }
    }
}
