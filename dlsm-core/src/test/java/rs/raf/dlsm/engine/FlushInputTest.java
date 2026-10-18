package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.InternalRecord;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlushInputTest {

    @Test
    void exposesWhatFlushNeeds() {
        MemTable table = new MemTable(5, 1 << 20);
        table.apply(InternalRecord.put("beta", 31, new byte[] {1}));
        table.apply(InternalRecord.delete("alpha", 32));
        table.apply(InternalRecord.put("beta", 40, new byte[] {2}));
        FlushInput input = new FlushInput(table.freeze(List.of(5L)));

        assertEquals(5, input.memtableGeneration());
        assertEquals(2, input.entryCount());
        assertEquals(40, input.maxSequence());
        assertEquals(List.of(5L), input.walSegmentIds());

        List<String> records = new ArrayList<>();
        input.records().forEachRemaining(r -> records.add(r.key() + "@" + r.sequence()));
        assertEquals(List.of("alpha@32", "beta@40"), records);
    }

    @Test
    void requiresAFrozenGeneration() {
        assertThrows(NullPointerException.class, () -> new FlushInput(null));
    }

    @Test
    void noSchedulerDoesNothing() {
        assertDoesNotThrow(() -> {
            FlushScheduler.NONE.flushNeeded();
            FlushScheduler.NONE.checkAutoCompaction();
        });
    }
}
