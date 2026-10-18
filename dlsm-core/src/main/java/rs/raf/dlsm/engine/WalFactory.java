package rs.raf.dlsm.engine;

import java.io.IOException;

/**
 * Creates the next WAL segment for rotation (CP1 §10 step 1). Implemented
 * by the WAL module with the segment creation protocol of CP1 §16.1: write
 * the header to a temporary file, force it, atomically move it to its final
 * name, force {@code lsm/wal/}. Only then may the segment take mutations.
 */
@FunctionalInterface
public interface WalFactory {

    /**
     * @return a durable, empty segment with an id above every id used so far
     * @throws IOException if the segment cannot be created durably; nothing
     *                     may then be appended to it
     */
    MutationLog createSegment(OpContext ctx) throws IOException;
}
