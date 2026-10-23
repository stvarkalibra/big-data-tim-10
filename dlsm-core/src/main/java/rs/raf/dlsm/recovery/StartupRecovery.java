package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.Engine;
import rs.raf.dlsm.engine.EngineConfig;
import rs.raf.dlsm.engine.FlushScheduler;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.RecoveredState;
import rs.raf.dlsm.engine.TableSource;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.DefaultStorageIO;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableRecoveryVerifier;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.WalIdAllocator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Startup over a data directory (CP1 §21.1). It only composes the existing
 * steps: lock and identity, manifest, table validation, WAL recovery, and
 * opening the engine over the result. Each step keeps its own rules.
 *
 * <p>The maintenance worker that flushes to disk is not wired yet, so the
 * engine gets {@link FlushScheduler#NONE}. Restart and WAL replay work;
 * flush does not.
 */
public final class StartupRecovery {

    /** The running engine and the directory lock, which the caller closes at shutdown. */
    public record Opened(Engine engine, DataDirectory directory) {
    }

    private StartupRecovery() {
    }

    /**
     * @throws IOException if startup fails; the directory lock is released
     *                     and the next start classifies the state again
     */
    public static Opened open(Path root, String nodeId, EngineConfig config,
                              StorageStats stats, JsonLog log) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(log, "log");

        Failpoints failpoints = config.failpoints();
        DataLayout layout = new DataLayout(root);
        StorageIO io = new DefaultStorageIO(failpoints);
        ManifestManager manifests = new ManifestManager(layout, io);
        OpContext ctx = OpContext.background();

        DataDirectory directory = null;
        try {
            directory = DataDirectory.open(layout, nodeId, io, manifests, ctx);

            Manifest manifest = manifests.loadAndValidate();
            List<TableSource> tables = openTables(layout, manifest.sstables(), stats);

            WalIdAllocator ids = WalIdAllocator.fromDisk(layout.walDir(), layout.tmpDir());
            WalRecoveryResult result = new WalRecovery(layout, io, failpoints, stats, log)
                    .run(manifest.flushedThroughSequence(), ids,
                            config.memtableMaxBytes(), ctx);

            RecoveredState state = new RecoveredState(
                    result.lastUsedSequence(),
                    result.recoveredGeneration(),
                    tables,
                    result.activeLog(),
                    result.walFactory());

            Engine engine = Engine.open(config, state, host -> FlushScheduler.NONE);
            return new Opened(engine, directory);
        } catch (StorageException e) {
            closeQuietly(directory);
            throw new IOException(e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(directory);
            throw e;
        }
    }

    private static List<TableSource> openTables(DataLayout layout, List<SSTableMeta> metas,
                                                StorageStats stats)
            throws IOException, StorageException {
        SSTableRecoveryVerifier.verifyAll(layout.sstDir(), metas);
        List<TableSource> tables = new ArrayList<>();
        for (SSTableMeta meta : metas) {
            tables.add(DiskSSTableTableSource.open(
                    layout.sstDir().resolve(meta.file()), meta, stats));
        }
        return tables;
    }

    private static void closeQuietly(DataDirectory directory) {
        if (directory == null) {
            return;
        }
        try {
            directory.close();
        } catch (IOException ignored) {
            // The failure that brought us here is the one to report.
        }
    }
}