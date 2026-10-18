
package rs.raf.dlsm.log;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JsonLog {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "requestId", "lifecycleState", "operation",
            "sequence", "walSegmentId", "tableId",
            "manifestGeneration", "maintenancePhase",
            "failpointName", "failpointAction",
            "corruptionCategory", "fileOffset",
            "partitionId", "epoch", "peerId",
            "messageId", "errorCode", "message"
    );

    private final String nodeId;
    private final PrintStream output;
    private final Clock clock;

    public JsonLog(String nodeId) {
        this(nodeId, System.out, Clock.systemUTC());
    }

    public JsonLog(String nodeId, PrintStream output, Clock clock) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("Invalid node ID");
        }

        this.nodeId = nodeId;
        this.output = Objects.requireNonNull(output);
        this.clock = Objects.requireNonNull(clock);
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
            String level, String event, Map<String, ?> fields) {

        if (!Set.of("DEBUG", "INFO", "WARN", "ERROR")
                .contains(level)) {
            throw new IllegalArgumentException("Invalid log level");
        }

        if (event == null ||
                !event.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Event must be a stable symbolic name");
        }

        Objects.requireNonNull(fields, "fields");

        Map<String, Object> document = new LinkedHashMap<>();

        document.put("timestamp", Instant.now(clock).toString());
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

            if (value != null
                    && !(value instanceof String)
                    && !(value instanceof Number)
                    && !(value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "Unsupported log value type: " + key);
            }

            if (value instanceof Double d && !Double.isFinite(d)
                    || value instanceof Float f && !Float.isFinite(f)) {
                throw new IllegalArgumentException(
                        "Non-finite number: " + key);
            }

            document.put(key, value);
        }

        String json = toJson(document);

        synchronized (output) {
            output.println(json);
            output.flush();
        }
    }

    private static String toJson(Map<String, Object> document) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;

        for (var entry : document.entrySet()) {
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
            } else {
                builder.append(value);
            }
        }

        return builder.append('}').toString();
    }

    private static void appendString(
            StringBuilder builder, String value) {

        builder.append('"');

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(
                                String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }

        builder.append('"');
    }
}
