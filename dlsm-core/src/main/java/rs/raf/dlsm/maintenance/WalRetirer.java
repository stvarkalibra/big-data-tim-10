package rs.raf.dlsm.maintenance;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.IOException;
import java.util.List;

/**
 * Deletes the WAL segments that a published flush covers, then forces
 * {@code lsm/wal/} (CP1 §19.1 steps 12–13). The node passes
 * {@code WalSegments::retire}, so the WAL catalog behind the storage status
 * drops each deleted segment; engine tests over {@code InMemoryWal} have no
 * catalog and use {@link #deletingFiles}.
 */
@FunctionalInterface
public interface WalRetirer {

    /** @throws IOException if a delete or the directory force fails; the flush job reports it */
    void retire(List<Long> segmentIds, OpContext ctx) throws IOException;

    /** Deletes the segment files and forces {@code lsm/wal/}, without a WAL catalog. */
    static WalRetirer deletingFiles(DataLayout layout, StorageIO io) {
        return (segmentIds, ctx) -> {
            for (long segmentId : segmentIds) {
                io.delete(layout.walDir().resolve(WalFileNames.segmentFileName(segmentId)));
            }
            io.fsyncDirectory(layout.walDir(), null, ctx);
        };
    }
}
