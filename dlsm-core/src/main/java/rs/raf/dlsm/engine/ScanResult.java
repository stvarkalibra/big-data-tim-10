package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;

import java.util.List;

/**
 * Result of SCAN: live keys in strictly increasing key order, at most
 * {@code limit} of them. Every item is a PUT; tombstones never appear.
 */
public record ScanResult(List<InternalRecord> items) {

    public ScanResult {
        items = List.copyOf(items);
        for (InternalRecord item : items) {
            if (item.isTombstone()) {
                throw new IllegalArgumentException("Scan result holds a tombstone");
            }
        }
    }
}
