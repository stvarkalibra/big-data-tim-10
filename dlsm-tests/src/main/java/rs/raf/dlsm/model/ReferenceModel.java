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
}
