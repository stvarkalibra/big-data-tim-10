package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.util.OptionalLong;

/**
 * Hands out mutation sequence numbers {@code 1..2^63-1} (CP1 §6).
 *
 * <p>A number is consumed as soon as it is allocated, even if the mutation
 * later fails, so gaps are expected. The allocator never wraps around:
 * after {@link Long#MAX_VALUE} has been handed out it is exhausted and every
 * further allocation fails, which the engine turns into {@code FAILED}.
 *
 * <p>Not thread-safe: allocation happens inside the critical section that
 * orders mutations, i.e. under the engine write lock (CP1 §6, §13.1).
 */
public final class SequenceAllocator {

    public static final String EXHAUSTED_CATEGORY = "SEQUENCE_EXHAUSTED";

    private long next;
    private boolean exhausted;

    private SequenceAllocator(long next, boolean exhausted) {
        this.next = next;
        this.exhausted = exhausted;
    }

    /** A brand-new database starts at sequence 1 (CP1 §14.2). */
    public static SequenceAllocator forNewDatabase() {
        return new SequenceAllocator(1, false);
    }

    /**
     * Resumes after recovery, where {@code lastUsedSequence} is
     * {@code max(flushedThroughSequence, greatest valid WAL sequence)}
     * (CP1 §21.1 step 11), or 0 when nothing was ever written.
     */
    public static SequenceAllocator after(long lastUsedSequence) {
        if (lastUsedSequence < 0) {
            throw new IllegalArgumentException("Negative sequence");
        }
        if (lastUsedSequence == Long.MAX_VALUE) {
            return new SequenceAllocator(0, true);
        }
        return new SequenceAllocator(lastUsedSequence + 1, false);
    }

    /**
     * @return a sequence greater than every previously returned one
     * @throws StorageException {@code INTERNAL_ERROR / SEQUENCE_EXHAUSTED}
     *                          once the range is used up
     */
    public long allocate() throws StorageException {
        if (exhausted) {
            throw new StorageException(ErrorCode.INTERNAL_ERROR,
                    EXHAUSTED_CATEGORY, "Mutation sequence range exhausted");
        }

        long sequence = next;
        if (sequence == Long.MAX_VALUE) {
            exhausted = true;
        } else {
            next = sequence + 1;
        }
        return sequence;
    }

    /** Next sequence for the status field {@code nextSequence}; empty once exhausted. */
    public OptionalLong nextSequence() {
        return exhausted ? OptionalLong.empty() : OptionalLong.of(next);
    }

    public boolean isExhausted() {
        return exhausted;
    }
}
