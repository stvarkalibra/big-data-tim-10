package rs.raf.dlsm.model;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class ReferenceModel {
    private final TreeMap<String, byte[]> store = new TreeMap<>();

    public synchronized void put(String key, byte[] value) {
        store.put(key, value != null ? value.clone() : new byte[0]);
    }

    public synchronized boolean delete(String key) {
        return store.remove(key) != null;
    }

    public synchronized byte[] get(String key) {
        byte[] val = store.get(key);
        return val != null ? val.clone() : null;
    }

    public synchronized java.util.Set<String> keySet() {
        return store.keySet();
    }

    public synchronized Map<String, byte[]> scan(String startInclusive, String endExclusive, int limit) {
        return store.entrySet().stream()
                .filter(e -> (startInclusive == null || e.getKey().compareTo(startInclusive) >= 0))
                .filter(e -> (endExclusive == null || e.getKey().compareTo(endExclusive) < 0))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().clone(),
                        (v1, v2) -> v1,
                        TreeMap::new
                ));
    }

    public synchronized void clear() {
        store.clear();
    }

    /** An independent copy, e.g. one crash candidate (CP1 §28.3). */
    public synchronized ReferenceModel copy() {
        ReferenceModel copy = new ReferenceModel();
        store.forEach(copy::put);
        return copy;
    }

    /** Every live key with a copy of its value, in key order. */
    public synchronized TreeMap<String, byte[]> snapshot() {
        TreeMap<String, byte[]> snapshot = new TreeMap<>();
        store.forEach((key, value) -> snapshot.put(key, value.clone()));
        return snapshot;
    }
}