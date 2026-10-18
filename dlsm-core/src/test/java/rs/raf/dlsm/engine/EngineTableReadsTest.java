package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EngineTableReadsTest {

    private static InternalRecord put(String key, long seq, String value) {
        return InternalRecord.put(key, seq, value.getBytes(StandardCharsets.UTF_8));
    }

    private static InternalRecord del(String key, long seq) {
        return InternalRecord.delete(key, seq);
    }

    private static OpContext ctx() {
        return OpContext.withTimeout("t", 5000);
    }

    /** An engine over published tables whose next sequence follows them. */
    private static Engine engine(FakeTable... tables) {
        EngineView view = new EngineView(null, List.of(tables));
        return new Engine(EngineConfig.defaults(), new InMemoryWal().createSegment(ctx()),
                SequenceAllocator.after(view.maxSequence()), view);
    }

    private static String get(Engine engine, String key) throws StorageException {
        GetResult r = engine.get(key, ctx());
        return r.isFound() ? new String(r.value(), StandardCharsets.UTF_8) : null;
    }

    private static List<String> scan(Engine engine, String start, String end, int limit)
            throws StorageException {
        List<String> out = new ArrayList<>();
        for (InternalRecord r : engine.scan(start, end, limit, ctx()).items()) {
            out.add(r.key() + "=" + new String(r.value(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void getFindsValuesInTables() throws StorageException {
        Engine engine = engine(new FakeTable(1, put("alpha", 1, "A"), put("beta", 2, "B")));

        assertEquals("A", get(engine, "alpha"));
        assertEquals("B", get(engine, "beta"));
        assertNull(get(engine, "gamma"));
    }

    @Test
    void greatestSequenceWinsAcrossTablesWhateverTheTableId() throws StorageException {
        // Table 1 is newer than table 2 for "k": ids say nothing about freshness.
        Engine engine = engine(
                new FakeTable(1, put("k", 10, "new")),
                new FakeTable(2, put("k", 5, "old")));

        assertEquals("new", get(engine, "k"));
        assertEquals(List.of("k=new"), scan(engine, "a", "z", 10));
    }

    @Test
    void newerTombstoneInTableHidesOlderValue() throws StorageException {
        Engine engine = engine(
                new FakeTable(1, put("k", 3, "A")),
                new FakeTable(2, del("k", 7)));

        assertNull(get(engine, "k"));
        assertEquals(List.of(), scan(engine, "a", "z", 10));
    }

    @Test
    void memTableVersionOverridesTables() throws StorageException {
        Engine engine = engine(new FakeTable(1, put("k", 3, "table"), put("m", 4, "M")));
        engine.put("k", "memory".getBytes(StandardCharsets.UTF_8), ctx());
        engine.delete("m", ctx());

        assertEquals("memory", get(engine, "k"));
        assertNull(get(engine, "m"));
        assertEquals(List.of("k=memory"), scan(engine, "a", "z", 10));
    }

    @Test
    void workedScanExampleWithTables() throws StorageException {
        // CP1 §12 example, with the "immutable" generation as an older table.
        Engine engine = engine(
                new FakeTable(7, put("alpha", 3, "A1"), put("gamma", 6, "G")),
                new FakeTable(8, put("alpha", 8, "A2"), put("beta", 9, "B")));
        // Next sequences are 10 and 11; the spec uses 12 and 13, order is what matters.
        engine.delete("beta", ctx());
        engine.put("delta", "D".getBytes(StandardCharsets.UTF_8), ctx());

        assertEquals(List.of("alpha=A2", "delta=D", "gamma=G"),
                scan(engine, "alpha", "omega", 100));
    }

    @Test
    void tablesOutsideTheKeyRangeAreNotConsulted() throws StorageException {
        FakeTable low = new FakeTable(1, put("alpha", 1, "A"), put("delta", 2, "D"));
        FakeTable high = new FakeTable(2, put("epsilon", 3, "E"), put("omega", 4, "O"));
        FakeTable wide = new FakeTable(3, put("beta", 5, "B"), put("zulu", 6, "Z"));
        Engine engine = engine(low, high, wide);

        // CP1 §11.2 example: kappa is outside table 1 only.
        assertNull(get(engine, "kappa"));
        assertEquals(0, low.gets);
        assertEquals(1, high.gets);
        assertEquals(1, wide.gets);

        // [a, b) touches only table 1.
        assertEquals(List.of("alpha=A"), scan(engine, "a", "b", 10));
        assertEquals(1, low.iterators);
        assertEquals(0, high.iterators);
        assertEquals(0, wide.iterators);
    }

    @Test
    void scanEndBoundaryExcludesTableStartingThere() throws StorageException {
        FakeTable atEnd = new FakeTable(2, put("omega", 2, "O"));
        Engine engine = engine(new FakeTable(1, put("alpha", 1, "A")), atEnd);

        assertEquals(List.of("alpha=A"), scan(engine, "alpha", "omega", 10));
        assertEquals(0, atEnd.iterators);
    }

    @Test
    void corruptTableOnGetFailsTheEngineInsteadOfNotFound() {
        FakeTable corrupt = new FakeTable(1, put("alpha", 1, "A"));
        corrupt.corruptGet = true;
        Engine engine = engine(corrupt);

        StorageException e = assertThrows(StorageException.class,
                () -> engine.get("alpha", ctx()));
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals("DATA_BLOCK", e.category());
        assertEquals(EngineMode.FAILED, engine.mode());
        assertEquals("DATA_BLOCK", engine.lastFatalError().orElseThrow().category());

        StorageException after = assertThrows(StorageException.class,
                () -> engine.put("x", new byte[0], ctx()));
        assertEquals(ErrorCode.NODE_NOT_READY, after.code());
    }

    @Test
    void corruptTableDuringScanFailsTheEngine() {
        FakeTable corrupt = new FakeTable(1, put("a", 1, "A"), put("b", 2, "B"), put("c", 3, "C"));
        corrupt.corruptAfter = 1;
        Engine engine = engine(corrupt);

        StorageException e = assertThrows(StorageException.class,
                () -> engine.scan("a", "z", 10, ctx()));
        assertEquals("DATA_BLOCK", e.category());
        assertEquals(EngineMode.FAILED, engine.mode());
    }

    @Test
    void engineViewSortsTablesAndRejectsDuplicates() {
        EngineView view = new EngineView(null, List.of(
                new FakeTable(9, put("a", 1, "x")),
                new FakeTable(3, put("b", 7, "x"))));

        assertEquals(List.of(3L, 9L), view.tableIds());
        assertEquals(7, view.maxSequence());
        assertTrue(view.table(9).isPresent());
        assertTrue(view.table(4).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new EngineView(null, List.of(
                new FakeTable(3, put("a", 1, "x")), new FakeTable(3, put("b", 2, "x")))));
        assertEquals(0, EngineView.EMPTY.maxSequence());
    }

    @Test
    void uncheckedStorageExceptionKeepsTheCause() {
        StorageException cause = FakeTable.corruption();
        UncheckedStorageException wrapped = new UncheckedStorageException(cause);

        assertSame(cause, wrapped.getCause());
        assertEquals(cause.getMessage(), wrapped.getMessage());
    }
}
