package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.InternalRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class MemTableTest {

    private static final long LARGE = 1 << 20;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static InternalRecord put(String key, long seq, String value) {
        return InternalRecord.put(key, seq, bytes(value));
    }

    private static List<String> keys(Iterator<InternalRecord> it) {
        List<String> result = new ArrayList<>();
        it.forEachRemaining(r -> result.add(r.key() + "@" + r.sequence()));
        return result;
    }

    @Test
    void newTableIsEmpty() {
        MemTable table = new MemTable(1, LARGE);

        assertTrue(table.isEmpty());
        assertEquals(1, table.generation());
        assertEquals(0, table.entryCount());
        assertEquals(0, table.logicalBytes());
        assertEquals(OptionalLong.empty(), table.minSequence());
        assertEquals(OptionalLong.empty(), table.maxSequence());
        assertFalse(table.rotationRequired());
        assertFalse(table.iterator().hasNext());
    }

    @Test
    void entryBytesFollowsStandardFormula() {
        assertEquals(32 + 5 + 3, MemTable.entryBytes(put("alpha", 1, "abc")));
        assertEquals(32 + 5, MemTable.entryBytes(put("alpha", 1, "")));
        assertEquals(32 + 5, MemTable.entryBytes(InternalRecord.delete("alpha", 1)));
    }

    @Test
    void overwriteKeepsOnlyNewestVersion() {
        // CP1-API-001 shape: three PUTs of alpha leave one entry.
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("alpha", 1, "A"));
        table.apply(put("alpha", 2, "BB"));
        table.apply(put("alpha", 3, "EEE"));

        assertEquals(1, table.entryCount());
        assertEquals(32 + 5 + 3, table.logicalBytes());
        InternalRecord current = table.get("alpha").orElseThrow();
        assertEquals(3, current.sequence());
        assertArrayEquals(bytes("EEE"), current.value());
    }

    @Test
    void deleteIsStoredAsTombstone() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("alpha", 1, "value"));
        table.apply(InternalRecord.delete("alpha", 2));

        assertEquals(1, table.entryCount());
        assertEquals(32 + 5, table.logicalBytes());
        assertTrue(table.get("alpha").orElseThrow().isTombstone());
    }

    @Test
    void deleteOfUnknownKeyStillCreatesTombstone() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(InternalRecord.delete("ghost", 1));

        assertEquals(1, table.entryCount());
        assertTrue(table.get("ghost").orElseThrow().isTombstone());
    }

    @Test
    void absentKeyIsEmpty() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("alpha", 1, "A"));

        assertEquals(Optional.empty(), table.get("beta"));
    }

    @Test
    void logicalBytesTracksMixOfKeys() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("a", 1, "1234"));        // 32 + 1 + 4 = 37
        table.apply(put("bb", 2, ""));           // 32 + 2     = 34
        table.apply(InternalRecord.delete("c", 3)); // 32 + 1  = 33
        table.apply(put("a", 4, "12"));          // 37 -> 35

        assertEquals(35 + 34 + 33, table.logicalBytes());
        assertEquals(3, table.entryCount());
    }

    @Test
    void sequenceMustStrictlyIncrease() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("alpha", 5, "A"));

        assertThrows(IllegalStateException.class,
                () -> table.apply(put("beta", 5, "B")));
        assertThrows(IllegalStateException.class,
                () -> table.apply(put("alpha", 4, "old")));

        // Rejected records leave no trace.
        assertEquals(1, table.entryCount());
        assertEquals(Optional.empty(), table.get("beta"));
        assertEquals(5, table.get("alpha").orElseThrow().sequence());
        assertEquals(32 + 5 + 1, table.logicalBytes());
    }

    @Test
    void sequenceRangeCoversAllAppliedMutations() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("alpha", 41, "A"));
        table.apply(put("beta", 42, "B"));
        table.apply(put("alpha", 47, "C"));

        // alpha@41 was replaced, but 41 was still applied to this generation.
        assertEquals(OptionalLong.of(41), table.minSequence());
        assertEquals(OptionalLong.of(47), table.maxSequence());
    }

    @Test
    void iteratesInUnsignedByteKeyOrderIncludingTombstones() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("b", 1, "x"));
        table.apply(put("B", 2, "x"));
        table.apply(InternalRecord.delete("a", 3));
        table.apply(put("_", 4, "x"));
        table.apply(put("-", 5, "x"));

        assertEquals(List.of("-@5", "B@2", "_@4", "a@3", "b@1"),
                keys(table.iterator()));
    }

    @Test
    void iteratorStartsAtStartInclusive() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("alpha", 1, "x"));
        table.apply(put("beta", 2, "x"));
        table.apply(put("gamma", 3, "x"));

        assertEquals(List.of("beta@2", "gamma@3"), keys(table.iterator("beta")));
        assertEquals(List.of("gamma@3"), keys(table.iterator("c")));
        assertEquals(List.of(), keys(table.iterator("zeta")));
    }

    @Test
    void iteratorIsReadOnly() {
        MemTable table = new MemTable(1, LARGE);
        table.apply(put("alpha", 1, "x"));

        Iterator<InternalRecord> it = table.iterator();
        it.next();
        assertThrows(UnsupportedOperationException.class, it::remove);
        assertEquals(1, table.entryCount());
    }

    @Test
    void rotationRequiredOnceLimitIsReached() {
        MemTable table = new MemTable(1, 2 * (32 + 1 + 1));
        table.apply(put("a", 1, "x"));
        assertFalse(table.rotationRequired());

        table.apply(put("b", 2, "x"));
        assertTrue(table.rotationRequired(), "exactly at the limit");
    }

    @Test
    void oversizedRecordIsAcceptedIntoEmptyTable() {
        MemTable table = new MemTable(1, 64);
        table.apply(put("big", 1, "v".repeat(1000)));

        assertEquals(1, table.entryCount());
        assertTrue(table.rotationRequired());
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new MemTable(0, LARGE));
        assertThrows(IllegalArgumentException.class, () -> new MemTable(1, 0));
        assertThrows(NullPointerException.class,
                () -> new MemTable(1, LARGE).apply(null));
    }
}
