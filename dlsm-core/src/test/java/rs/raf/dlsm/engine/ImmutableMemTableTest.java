package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.InternalRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ImmutableMemTableTest {

    private static InternalRecord put(String key, long seq, String value) {
        return InternalRecord.put(key, seq, value.getBytes(StandardCharsets.UTF_8));
    }

    private static MemTable filled() {
        MemTable table = new MemTable(7, 1 << 20);
        table.apply(put("gamma", 10, "G"));
        table.apply(put("alpha", 11, "A1"));
        table.apply(InternalRecord.delete("beta", 12));
        table.apply(put("alpha", 13, "A2"));
        return table;
    }

    private static List<String> keys(Iterator<InternalRecord> it) {
        List<String> result = new ArrayList<>();
        it.forEachRemaining(r -> result.add(r.key() + "@" + r.sequence()));
        return result;
    }

    @Test
    void freezeExposesSameContentsAndMetadata() {
        MemTable table = filled();
        long bytes = table.logicalBytes();

        ImmutableMemTable frozen = table.freeze(List.of(7L));

        assertEquals(7, frozen.generation());
        assertEquals(List.of(7L), frozen.walSegmentIds());
        assertEquals(3, frozen.entryCount());
        assertEquals(bytes, frozen.logicalBytes());
        assertEquals(10, frozen.minSequence());
        assertEquals(13, frozen.maxSequence());
        assertEquals(List.of("alpha@13", "beta@12", "gamma@10"),
                keys(frozen.iterator()));
        assertEquals(List.of("beta@12", "gamma@10"), keys(frozen.iterator("b")));
    }

    @Test
    void frozenViewReadsTombstonesAndAbsentKeys() {
        ImmutableMemTable frozen = filled().freeze(List.of(1L));

        assertTrue(frozen.get("beta").orElseThrow().isTombstone());
        assertArrayEquals("A2".getBytes(StandardCharsets.UTF_8),
                frozen.get("alpha").orElseThrow().value());
        assertEquals(Optional.empty(), frozen.get("delta"));
    }

    @Test
    void frozenTableRejectsWrites() {
        MemTable table = filled();
        ImmutableMemTable frozen = table.freeze(List.of(1L));

        assertTrue(table.isFrozen());
        assertThrows(IllegalStateException.class,
                () -> table.apply(put("zeta", 14, "Z")));
        assertEquals(3, frozen.entryCount());
        assertEquals(Optional.empty(), frozen.get("zeta"));
    }

    @Test
    void frozenIteratorIsReadOnly() {
        Iterator<InternalRecord> it = filled().freeze(List.of(1L)).iterator();
        it.next();

        assertThrows(UnsupportedOperationException.class, it::remove);
    }

    @Test
    void cannotFreezeTwiceOrWhenEmpty() {
        MemTable table = filled();
        table.freeze(List.of(1L));

        assertThrows(IllegalStateException.class, () -> table.freeze(List.of(2L)));
        assertThrows(IllegalStateException.class,
                () -> new MemTable(1, 100).freeze(List.of(1L)));
    }

    @Test
    void recoveredGenerationSortsItsWalSegments() {
        // Recovery replays several segments into one generation (CP1 §24).
        MemTable recovered = new MemTable(1, 1 << 20);
        recovered.apply(put("a", 1, "x"));
        recovered.apply(put("b", 2, "x"));

        ImmutableMemTable frozen = recovered.freeze(List.of(9L, 3L, 6L));

        assertEquals(List.of(3L, 6L, 9L), frozen.walSegmentIds());
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.walSegmentIds().add(10L));
    }

    @Test
    void invalidWalSegmentsLeaveTableWritable() {
        for (List<Long> ids : List.of(
                List.<Long>of(), List.of(0L), List.of(-1L), List.of(2L, 2L),
                Arrays.asList(1L, null))) {
            MemTable table = filled();

            assertThrows(IllegalArgumentException.class,
                    () -> table.freeze(ids), String.valueOf(ids));
            assertFalse(table.isFrozen());
            assertDoesNotThrow(() -> table.apply(put("zeta", 14, "Z")));
        }

        assertThrows(NullPointerException.class, () -> filled().freeze(null));
    }
}
