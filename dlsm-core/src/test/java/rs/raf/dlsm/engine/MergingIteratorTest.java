package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.InternalRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class MergingIteratorTest {

    private static InternalRecord put(String key, long seq) {
        return InternalRecord.put(key, seq,
                (key + seq).getBytes(StandardCharsets.UTF_8));
    }

    private static InternalRecord del(String key, long seq) {
        return InternalRecord.delete(key, seq);
    }

    private static Iterator<InternalRecord> src(InternalRecord... records) {
        return List.of(records).iterator();
    }

    private static List<String> drain(Iterator<InternalRecord> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(r ->
                out.add(r.key() + "@" + r.sequence() + (r.isTombstone() ? "D" : "")));
        return out;
    }

    /** Counts how many records were pulled from a source. */
    private static final class CountingSource implements Iterator<InternalRecord> {
        private final Iterator<InternalRecord> delegate;
        int pulled;

        CountingSource(InternalRecord... records) {
            this.delegate = List.of(records).iterator();
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
    void noSourcesOrEmptySourcesYieldNothing() {
        assertFalse(new MergingIterator(List.of()).hasNext());
        assertFalse(new MergingIterator(List.of(src(), src())).hasNext());
        assertThrows(NoSuchElementException.class,
                () -> new MergingIterator(List.of()).next());
    }

    @Test
    void singleSourcePassesThroughIncludingTombstones() {
        assertEquals(List.of("a@1", "b@2D", "c@3"),
                drain(new MergingIterator(List.of(
                        src(put("a", 1), del("b", 2), put("c", 3))))));
    }

    @Test
    void interleavesSourcesInGlobalKeyOrder() {
        assertEquals(List.of("a@1", "b@5", "c@2", "d@6", "e@3"),
                drain(new MergingIterator(List.of(
                        src(put("a", 1), put("c", 2), put("e", 3)),
                        src(put("b", 5), put("d", 6))))));
    }

    @Test
    void greatestSequenceWinsWhateverTheSourcePosition() {
        // Newest version first, middle and last: source order never matters.
        for (List<Iterator<InternalRecord>> sources : List.of(
                List.of(src(put("k", 9)), src(put("k", 4)), src(put("k", 1))),
                List.of(src(put("k", 4)), src(put("k", 9)), src(put("k", 1))),
                List.of(src(put("k", 1)), src(put("k", 4)), src(put("k", 9))))) {
            assertEquals(List.of("k@9"), drain(new MergingIterator(sources)));
        }
    }

    @Test
    void newestTombstoneIsKeptAndOlderTombstoneLoses() {
        assertEquals(List.of("a@7D", "b@8"),
                drain(new MergingIterator(List.of(
                        src(del("a", 7), del("b", 2)),
                        src(put("a", 3), put("b", 8))))));
    }

    @Test
    void identicalDuplicateCollapsesButConflictFails() {
        assertEquals(List.of("a@5"), drain(new MergingIterator(List.of(
                src(put("a", 5)), src(put("a", 5))))));

        MergingIterator conflicting = new MergingIterator(List.of(
                src(put("a", 5)), src(del("a", 5))));
        assertThrows(IllegalStateException.class, conflicting::next);
    }

    @Test
    void unorderedSourceIsRejected() {
        assertThrows(IllegalStateException.class, () -> drain(
                new MergingIterator(List.of(src(put("b", 1), put("a", 2))))));
        assertThrows(IllegalStateException.class, () -> drain(
                new MergingIterator(List.of(src(put("a", 1), put("a", 2))))));
    }

    @Test
    void boundsAreHalfOpen() {
        Iterator<InternalRecord> a = src(put("alpha", 1), put("beta", 2),
                put("omega", 3), put("zeta", 4));
        Iterator<InternalRecord> b = src(put("aaa", 5), put("delta", 6),
                put("omega", 7));

        assertEquals(List.of("beta@2", "delta@6"),
                drain(new MergingIterator(List.of(a, b), "b", "omega")));
    }

    @Test
    void sourcesAreNotReadPastEndExclusive() {
        CountingSource source = new CountingSource(
                put("a", 1), put("b", 2), put("c", 3), put("d", 4));

        assertEquals(List.of("a@1", "b@2"),
                drain(new MergingIterator(List.of(source), null, "c")));
        // a, b and the first out-of-range record c; never d.
        assertEquals(3, source.pulled);
    }

    @Test
    void sourcesAreAdvancedOnlyWhenTheNextKeyIsNeeded() {
        CountingSource first = new CountingSource(put("a", 1), put("c", 3));
        CountingSource second = new CountingSource(put("a", 2), put("d", 4));

        MergingIterator merged = new MergingIterator(List.of(first, second));
        assertEquals(1, first.pulled);
        assertEquals(1, second.pulled);

        assertEquals("a", merged.next().key());
        // A caller that stops here (limit reached) triggers no further reads.
        assertEquals(1, first.pulled);
        assertEquals(1, second.pulled);

        assertTrue(merged.hasNext());
        assertEquals(2, first.pulled);
        assertEquals(2, second.pulled);
    }

    @Test
    void matchesNaiveModelOnRandomSources() {
        Random random = new Random(12345);

        for (int round = 0; round < 200; round++) {
            int sourceCount = random.nextInt(5);
            long sequence = 1;
            List<TreeMap<String, InternalRecord>> tables = new ArrayList<>();
            Map<String, InternalRecord> expected = new TreeMap<>();

            for (int s = 0; s < sourceCount; s++) {
                tables.add(new TreeMap<>());
            }
            int writes = sourceCount == 0 ? 0 : random.nextInt(60);
            for (int w = 0; w < writes; w++) {
                String key = "k" + random.nextInt(25);
                InternalRecord record = random.nextInt(4) == 0
                        ? del(key, sequence++) : put(key, sequence++);
                // Like a MemTable: each source keeps its own newest version.
                tables.get(random.nextInt(sourceCount)).put(key, record);
                expected.put(key, record);
            }

            List<Iterator<InternalRecord>> sources = new ArrayList<>();
            for (TreeMap<String, InternalRecord> table : tables) {
                sources.add(table.values().iterator());
            }

            assertEquals(drain(expected.values().iterator()),
                    drain(new MergingIterator(sources)), "round " + round);
        }
    }
}
