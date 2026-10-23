package rs.raf.dlsm.recovery;

import rs.raf.dlsm.engine.RecoveredState;
import rs.raf.dlsm.engine.TableSource;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.TableIdAllocator;

import java.util.List;
import java.util.Objects;

/**
 * Everything startup recovery proved, CP1 §21.1 steps 1–14, ready for the
 * engine to open over it (step 15).
 *
 * @param foundState the directory state before any initialization step ran
 * @param manifest   the authoritative manifest, loaded and validated
 * @param tables     a validated reader for every table the manifest references
 * @param tableIds   built before cleanup; the flush and compaction worker
 *                   reserves new table IDs from it (CP1 §18.3)
 * @param wal        the WAL part: next sequence, recovered generation, new
 *                   active segment and segment factory
 */
public record RecoveryResult(
        StartupState foundState,
        Manifest manifest,
        List<TableSource> tables,
        TableIdAllocator tableIds,
        WalRecoveryResult wal) {

    public RecoveryResult {
        Objects.requireNonNull(foundState, "foundState");
        Objects.requireNonNull(manifest, "manifest");
        tables = List.copyOf(tables);
        Objects.requireNonNull(tableIds, "tableIds");
        Objects.requireNonNull(wal, "wal");
    }

    /** The engine's view of this result (plan §5.1, {@code Engine.open}). */
    public RecoveredState toRecoveredState() {
        return new RecoveredState(wal.lastUsedSequence(), wal.recoveredGeneration(),
                tables, wal.activeLog(), wal.walFactory());
    }
}
