package rs.raf.dlsm.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Trace {
    private final List<Entry> entries = new ArrayList<>();

    public record Entry(String requestId, String operation, int statusCode, long durationMs, String details) {}

    public synchronized void record(String requestId, String operation, int statusCode, long durationMs, String details) {
        entries.add(new Entry(requestId, operation, statusCode, durationMs, details));
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}