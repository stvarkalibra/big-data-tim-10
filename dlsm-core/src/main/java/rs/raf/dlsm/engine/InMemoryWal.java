package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A WAL that keeps nothing: mutations live only in memory and are lost on
 * restart. For the in-memory milestone (M1) only, never for durability
 * tests. Segment ids still start at 1 and increase, so rotation and flush
 * bookkeeping behave as with the real WAL.
 */
public final class InMemoryWal implements WalFactory {

    private final AtomicLong nextSegmentId = new AtomicLong(1);

    @Override
    public MutationLog createSegment(OpContext ctx) {
        return new Segment(nextSegmentId.getAndIncrement());
    }

    private record Segment(long segmentId) implements MutationLog {

        @Override
        public void append(InternalRecord record, OpContext ctx) {
        }

        @Override
        public void force(OpContext ctx) {
        }

        @Override
        public void close() {
        }
    }
}
