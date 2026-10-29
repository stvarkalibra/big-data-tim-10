package rs.raf.dlsm.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;

public class CandidateSet {
    private final Set<byte[]> candidates = new HashSet<>();

    public void add(byte[] value) {
        if (value != null) {
            candidates.add(value);
        }
    }

    public Set<byte[]> getCandidates() {
        return Collections.unmodifiableSet(candidates);
    }

    public boolean containsMatch(byte[] actual) {
        if (actual == null) return false;
        for (byte[] candidate : candidates) {
            if (Arrays.equals(candidate, actual)) {
                return true;
            }
        }
        return false;
    }
}