package rs.raf.dlsm.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;

public final class CandidateSet {

    private final List<ReferenceModel> candidates;

    private CandidateSet(List<ReferenceModel> candidates) {
        this.candidates = List.copyOf(candidates);
    }

    /** Only the confirmed model: no mutation is in doubt. */
    public static CandidateSet exactly(ReferenceModel confirmed) {
        return new CandidateSet(List.of(confirmed.copy()));
    }

    public CandidateSet applied(String key, byte[] valueOrNullForDelete) {
        List<ReferenceModel> next = new ArrayList<>();
        for (ReferenceModel candidate : candidates) {
            next.add(apply(candidate, key, valueOrNullForDelete));
        }
        return new CandidateSet(next);
    }

    public CandidateSet absentOrApplied(String key, byte[] valueOrNullForDelete) {
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "Only one mutation may be in doubt at a time (CP1 §28.3)");
        }
        ReferenceModel without = candidates.get(0);
        return new CandidateSet(List.of(without, apply(without, key, valueOrNullForDelete)));
    }

    public int size() {
        return candidates.size();
    }

    /** The candidate equal to the observed state, key by key and byte by byte. */
    public Optional<ReferenceModel> match(SortedMap<String, byte[]> observed) {
        for (ReferenceModel candidate : candidates) {
            if (sameState(candidate.snapshot(), observed)) {
                return Optional.of(candidate.copy());
            }
        }
        return Optional.empty();
    }

    /** One digest per candidate, in order, for the campaign report (CP1 §29.6). */
    public List<String> digests() {
        return candidates.stream().map(candidate -> digest(candidate.snapshot())).toList();
    }

    /** A short SHA-256 over the keys and values in key order. */
    public static String digest(SortedMap<String, byte[]> state) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK has SHA-256", e);
        }
        for (Map.Entry<String, byte[]> entry : state.entrySet()) {
            byte[] key = entry.getKey().getBytes(StandardCharsets.US_ASCII);
            sha.update(intBytes(key.length));
            sha.update(key);
            sha.update(intBytes(entry.getValue().length));
            sha.update(entry.getValue());
        }
        return HexFormat.of().formatHex(sha.digest(), 0, 8);
    }

    private static boolean sameState(SortedMap<String, byte[]> a, SortedMap<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : a.entrySet()) {
            if (!Arrays.equals(entry.getValue(), b.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static ReferenceModel apply(ReferenceModel model, String key, byte[] value) {
        Objects.requireNonNull(key, "key");
        ReferenceModel next = model.copy();
        if (value == null) {
            next.delete(key);
        } else {
            next.put(key, value);
        }
        return next;
    }

    private static byte[] intBytes(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }
}