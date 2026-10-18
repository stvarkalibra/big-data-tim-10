package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Keys;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * The visible part of SCAN (CP1 §12, PI §18.6): merges the sources and
 * returns at most {@code limit} live keys in {@code [startInclusive,
 * endExclusive)}, in strictly increasing key order.
 *
 * <p>The winning version of a key is chosen first, by sequence, and only
 * then filtered: a winning tombstone hides the key, so an older PUT can
 * never come back. {@code limit} counts emitted live keys only, never
 * tombstones or superseded versions.
 *
 * <p>The caller provides the stable snapshot and positions each source at
 * {@code startInclusive} (MemTable {@code iterator(start)}, SSTable index
 * seek); this class does no locking.
 */
public final class LiveScan {

    public static final int MAX_LIMIT = 1000;

    private LiveScan() {
    }

    /**
     * @return live PUT records only, ascending by key
     * @throws IllegalArgumentException for invalid bounds or limit; the
     *                                  HTTP layer rejects these earlier
     */
    public static List<InternalRecord> collect(
            List<? extends Iterator<InternalRecord>> sources,
            String startInclusive, String endExclusive, int limit) {

        Objects.requireNonNull(sources, "sources");
        validate(startInclusive, endExclusive, limit);

        MergingIterator merged =
                new MergingIterator(sources, startInclusive, endExclusive);

        List<InternalRecord> live = new ArrayList<>(Math.min(limit, 64));
        while (live.size() < limit && merged.hasNext()) {
            InternalRecord winner = merged.next();
            if (!winner.isTombstone()) {
                live.add(winner);
            }
        }
        return live;
    }

    public static void validate(String startInclusive, String endExclusive,
                                int limit) {
        if (!Keys.isValid(startInclusive) || !Keys.isValid(endExclusive)) {
            throw new IllegalArgumentException("Invalid scan bound");
        }
        if (Keys.compare(startInclusive, endExclusive) >= 0) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
        }
    }
}
