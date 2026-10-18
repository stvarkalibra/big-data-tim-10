package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.InternalRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveScanTest {

    private static InternalRecord put(String key, long seq, String value) {
        return InternalRecord.put(key, seq, value.getBytes(StandardCharsets.UTF_8));
    }

    private static InternalRecord del(String key, long seq) {
        return InternalRecord.delete(key, seq);
    }

    private static List<String> pairs(List<InternalRecord> records) {
        List<String> out = new ArrayList<>();
        for (InternalRecord r : records) {
            out.add(r.key() + "=" + new String(r.value(), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** Counts how many records were pulled from a source. */
    private static final class CountingSource implements Iterator<InternalRecord> {
        private final Iterator<InternalRecord> delegate;
        int pulled;

        CountingSource(List<InternalRecord> records) {
            this.delegate = records.iterator();
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public InternalRecord next() {
            pulled++;
            return delegate.next();
        }
    }

    @Test
    void workedExampleFromSpec() {
        // CP1 §12 "Razrađen primer: spajanje više verzija".
        MemTable immutableSource = new MemTable(1, 1 << 20);
        immutableSource.apply(put("alpha", 8, "A2"));
        immutableSource.apply(put("beta", 9, "B"));
        ImmutableMemTable immutable = immutableSource.freeze(List.of(1L));

        MemTable active = new MemTable(2, 1 << 20);
        active.apply(del("beta", 12));
        active.apply(put("delta", 13, "D"));

        Iterator<InternalRecord> sstable7 =
                List.of(put("alpha", 3, "A1"), put("gamma", 6, "G")).iterator();

        List<InternalRecord> result = LiveScan.collect(List.of(
                        active.iterator("alpha"),
                        immutable.iterator("alpha"),
                        sstable7),
                "alpha", "omega", 100);

        assertEquals(List.of("alpha=A2", "delta=D", "gamma=G"), pairs(result));
    }

    @Test
    void limitCountsOnlyLiveKeys() {
        Iterator<InternalRecord> source = List.of(
                del("a", 1), del("b", 2), put("c", 3, "C"),
                del("d", 4), put("e", 5, "E"), put("f", 6, "F")).iterator();

        assertEquals(List.of("c=C", "e=E"),
                pairs(LiveScan.collect(List.of(source), "a", "z", 2)));
    }

    @Test
    void endIsExclusiveAndStartInclusive() {
        Iterator<InternalRecord> source = List.of(
                put("alpha", 1, "A"), put("beta", 2, "B"),
                put("gamma", 3, "G")).iterator();

        assertEquals(List.of("alpha=A", "beta=B"),
                pairs(LiveScan.collect(List.of(source), "alpha", "gamma", 10)));
    }

    @Test
    void emptyRangeGivesEmptyResult() {
        assertEquals(List.of(), LiveScan.collect(
                List.of(List.of(put("a", 1, "A")).iterator()), "m", "n", 10));
        assertEquals(List.of(), LiveScan.collect(List.of(), "a", "b", 10));
    }

    @Test
    void stopsReadingOnceLimitIsReached() {
        List<InternalRecord> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(put("k%03d".formatted(i), i + 1, "v"));
        }
        CountingSource source = new CountingSource(many);

        assertEquals(3, LiveScan.collect(List.of(source), "k000", "z", 3).size());
        assertEquals(3, source.pulled);
    }

    @Test
    void rejectsInvalidArguments() {
        List<Iterator<InternalRecord>> none = List.of();

        assertThrows(IllegalArgumentException.class,
                () -> LiveScan.collect(none, "a", "b", 0));
        assertThrows(IllegalArgumentException.class,
                () -> LiveScan.collect(none, "a", "b", 1001));
        assertThrows(IllegalArgumentException.class,
                () -> LiveScan.collect(none, "b", "b", 10));
        assertThrows(IllegalArgumentException.class,
                () -> LiveScan.collect(none, "b", "a", 10));
        assertThrows(IllegalArgumentException.class,
                () -> LiveScan.collect(none, "bad key", "z", 10));
        assertThrows(IllegalArgumentException.class,
                () -> LiveScan.collect(none, "a", null, 10));
        assertDoesNotThrow(() -> LiveScan.collect(none, "a", "b", 1000));
    }
}
