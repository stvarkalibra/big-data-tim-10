package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;

import java.io.IOException;

/**
 * The engine's view of the active WAL segment: append one complete frame,
 * then force it (CP1 §9 steps 5–7). The real implementation wraps the WAL
 * module's segment writer; until it exists the engine runs on
 * {@link #NONE} (plan §11, stub rule).
 *
 * <p>Any {@link IOException} means the WAL tail is uncertain. The engine
 * then enters {@code FAILED} and never calls this log again (CP1 §9.3).
 */
public interface MutationLog {

    /** Writes the complete frame of {@code record}; it is not durable yet. */
    void append(InternalRecord record, OpContext ctx) throws IOException;

    /** Forces every appended frame to stable storage: the durability point. */
    void force(OpContext ctx) throws IOException;

    /**
     * Keeps nothing: mutations live only in memory and are lost on restart.
     * For the in-memory milestone (M1) only, never for durability tests.
     */
    MutationLog NONE = new MutationLog() {
        @Override
        public void append(InternalRecord record, OpContext ctx) {
        }

        @Override
        public void force(OpContext ctx) {
        }
    };
}
