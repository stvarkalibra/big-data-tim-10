package rs.raf.dlsm.engine;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class SequenceAllocatorTest {

    @Test
    void newDatabaseStartsAtOne() throws StorageException {
        SequenceAllocator allocator = SequenceAllocator.forNewDatabase();

        assertEquals(OptionalLong.of(1), allocator.nextSequence());
        assertEquals(1, allocator.allocate());
        assertEquals(2, allocator.allocate());
        assertEquals(3, allocator.allocate());
        assertEquals(OptionalLong.of(4), allocator.nextSequence());
    }

    @Test
    void resumesAfterLastUsedSequence() throws StorageException {
        assertEquals(1, SequenceAllocator.after(0).allocate());
        assertEquals(41, SequenceAllocator.after(40).allocate());
    }

    @Test
    void rejectsNegativeStart() {
        assertThrows(IllegalArgumentException.class,
                () -> SequenceAllocator.after(-1));
    }

    @Test
    void handsOutMaxValueOnceThenFails() throws StorageException {
        SequenceAllocator allocator = SequenceAllocator.after(Long.MAX_VALUE - 2);

        assertEquals(Long.MAX_VALUE - 1, allocator.allocate());
        assertEquals(Long.MAX_VALUE, allocator.allocate());
        assertTrue(allocator.isExhausted());
        assertEquals(OptionalLong.empty(), allocator.nextSequence());

        StorageException e = assertThrows(StorageException.class,
                allocator::allocate);
        assertEquals(ErrorCode.INTERNAL_ERROR, e.code());
        assertEquals(SequenceAllocator.EXHAUSTED_CATEGORY, e.category());

        // Stays exhausted: never wraps to a negative or small number.
        assertThrows(StorageException.class, allocator::allocate);
    }

    @Test
    void recoveryAtMaxValueIsAlreadyExhausted() {
        SequenceAllocator allocator = SequenceAllocator.after(Long.MAX_VALUE);

        assertTrue(allocator.isExhausted());
        assertEquals(OptionalLong.empty(), allocator.nextSequence());
        assertThrows(StorageException.class, allocator::allocate);
    }
}
