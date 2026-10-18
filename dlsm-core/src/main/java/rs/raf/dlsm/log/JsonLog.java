
package rs.raf.dlsm.log;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JsonLog {

    private static final Set<String> LEVELS =
            Set.of("DEBUG", "INFO", "WARN", "ERROR");

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "requestId",
            "lifecycleState",
            "operation",
            "sequence",
            "walSegmentId",
            "tableId",
            "manifestGeneration",
            "maintenancePhase",
            "failpointName",
            "failpointAction",
            "corruptionCategory",
            "fileOffset",
            "partitionId",
            "epoch",
            "peerId",
            "messageId",
            "errorCode",
            "message"
    );

    private final String nodeId;
    private final PrintStream output;
    private final Clock clock;

    public JsonLog(String nodeId) {
        this(nodeId, System.out, Clock.systemUTC());
    }

    public JsonLog(
            String nodeId,
            PrintStream output,
            Clock clock) {

        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid node ID");
        }

        this.nodeId = nodeId;
        this.output = Objects.requireNonNull(output, "output");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void debug(String event, Map<String, ?> fields) {
        log("DEBUG", event, fields);
    }

    public void info(String event, Map<String, ?> fields) {
        log("INFO", event, fields);
    }

    public void warn(String event, Map<String, ?> fields) {
        log("WARN", event, fields);
    }

    public void error(String event, Map<String, ?> fields) {
        log("ERROR", event, fields);
    }

    public void log(
            String level,
            String event,
            Map<String, ?> fields) {

        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException(
                    "Invalid log level");
        }

        if (event == null
                || !event.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Event must be a stable symbolic name");
        }

        Objects.requireNonNull(fields, "fields");

        Map<String, Object> document = new LinkedHashMap<>();

        document.put(
                "timestamp",
                Instant.now(clock).toString()
        );
        document.put("level", level);
        document.put("event", event);
        document.put("nodeId", nodeId);

        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            String key = entry.getKey();

            if (!ALLOWED_FIELDS.contains(key)) {
                throw new IllegalArgumentException(
                        "Unsupported log field: " + key);
            }

            Object value = entry.getValue();

            if (!supportedValue(value)) {
                throw new IllegalArgumentException(
                        "Unsupported or non-finite value: " + key);
            }

            document.put(key, value);
        }

        String json = toJson(document);

        synchronized (output) {
            output.println(json);
            output.flush();
        }
    }

    private static boolean supportedValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean) {
            return true;
        }

        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return true;
        }

        if (value instanceof Double number) {
            return Double.isFinite(number);
        }

        if (value instanceof Float number) {
            return Float.isFinite(number);
        }

        return false;
    }

    private static String toJson(
            Map<String, Object> document) {

        StringBuilder builder = new StringBuilder("{");
        boolean first = true;

        for (Map.Entry<String, Object> entry
                : document.entrySet()) {

            if (!first) {
                builder.append(',');
            }
            first = false;

            appendString(builder, entry.getKey());
            builder.append(':');

            Object value = entry.getValue();

            if (value == null) {
                builder.append("null");
            } else if (value instanceof String text) {
                appendString(builder, text);
            } else if (value instanceof Boolean
                    || value instanceof Number) {
                builder.append(value);
            } else {
                throw new IllegalStateException(
                        "Unexpected JSON value");
            }
        }

        return builder.append('}').toString();
    }

    private static void appendString(
            StringBuilder builder,
            String value) {

        builder.append('"');

        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);

            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");

                default -> {
                    if (current < 0x20) {
                        appendUnicodeEscape(builder, current);
                    } else if (Character.isHighSurrogate(current)) {
                        if (i + 1 < value.length()
                                && Character.isLowSurrogate(
                                value.charAt(i + 1))) {

                            builder.append(current);
                            builder.append(value.charAt(++i));
                        } else {
                            appendUnicodeEscape(builder, current);
                        }
                    } else if (Character.isLowSurrogate(current)) {
                        appendUnicodeEscape(builder, current);
                    } else {
                        builder.append(current);
                    }
                }
            }
        }

        builder.append('"');
    }

    private static void appendUnicodeEscape(
            StringBuilder builder, char value) {

        builder.append("\\u");

        final char[] hex = "0123456789abcdef".toCharArray();

        builder.append(hex[(value >>> 12) & 0xF]);
        builder.append(hex[(value >>> 8) & 0xF]);
        builder.append(hex[(value >>> 4) & 0xF]);
        builder.append(hex[value & 0xF]);
    }
}
