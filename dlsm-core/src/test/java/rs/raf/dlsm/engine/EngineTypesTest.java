package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.Lifecycle;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EngineTypesTest {

    @Test
    void engineModesMapToSharedLifecycle() {
        assertEquals(Lifecycle.RECOVERING, EngineMode.RECOVERING.lifecycle());
        assertEquals(Lifecycle.READY, EngineMode.SERVING.lifecycle());
        assertEquals(Lifecycle.READY, EngineMode.BACKPRESSURED.lifecycle());
        assertEquals(Lifecycle.FAILED, EngineMode.FAILED.lifecycle());
    }

    @Test
    void configDefaultsToOneMebibyte() {
        assertEquals(1_048_576, EngineConfig.defaults().memtableMaxBytes());
        assertThrows(IllegalArgumentException.class, () -> new EngineConfig(0));
    }

    @Test
    void mutationResultNeedsPositiveSequence() {
        assertEquals(7, new MutationResult(7).sequence());
        assertThrows(IllegalArgumentException.class, () -> new MutationResult(0));
    }

    @Test
    void getResultDistinguishesEmptyValueFromNotFound() {
        GetResult empty = GetResult.found(new byte[0]);

        assertTrue(empty.isFound());
        assertEquals(0, empty.value().length);
        assertFalse(GetResult.notFound().isFound());
        assertThrows(IllegalStateException.class, () -> GetResult.notFound().value());
    }

    @Test
    void getResultCopiesValue() {
        byte[] input = {1, 2, 3};
        GetResult result = GetResult.found(input);

        input[0] = 9;
        result.value()[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, result.value());
    }

    @Test
    void scanResultIsImmutableAndRejectsTombstones() {
        List<InternalRecord> items = new ArrayList<>(List.of(
                InternalRecord.put("a", 1, new byte[0])));
        ScanResult result = new ScanResult(items);

        items.clear();
        assertEquals(1, result.items().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.items().clear());
        assertThrows(IllegalArgumentException.class, () -> new ScanResult(
                List.of(InternalRecord.delete("a", 2))));
    }
}
