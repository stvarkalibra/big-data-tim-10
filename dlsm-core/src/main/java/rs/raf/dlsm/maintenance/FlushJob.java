package rs.raf.dlsm.maintenance;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.FlushInput;
import rs.raf.dlsm.engine.MaintenanceHost;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.TableSource;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.maintenance.MaintenanceProgress.Operation;
import rs.raf.dlsm.maintenance.MaintenanceProgress.Phase;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.ManifestManager;
import rs.raf.dlsm.manifest.ManifestPublishException;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableDiskWriter;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class FlushJob {

    public record Outcome(long tableId, long manifestGeneration, long flushedThroughSequence) {
    }

    private final DataLayout layout;
    private final StorageIO io;
    private final Failpoints failpoints;
    private final StorageStats stats;
    private final ManifestManager manifests;
    private final TableIdAllocator tableIds;
    private final MaintenanceHost host;
    private final MaintenanceProgress progress;

    public FlushJob(DataLayout layout, StorageIO io, Failpoints failpoints, StorageStats stats,
                    ManifestManager manifests, TableIdAllocator tableIds,
                    MaintenanceHost host, MaintenanceProgress progress) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.io = Objects.requireNonNull(io, "io");
        this.failpoints = Objects.requireNonNull(failpoints, "failpoints");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.tableIds = Objects.requireNonNull(tableIds, "tableIds");
        this.host = Objects.requireNonNull(host, "host");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    public Optional<Outcome> flushOldest(Long targetSequence, OpContext ctx) throws StorageException {
        Objects.requireNonNull(ctx, "ctx");
        Optional<FlushInput> next = host.oldestImmutable();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        FlushInput input = next.get();

        progress.begin(Operation.FLUSH,
                targetSequence != null ? targetSequence : input.maxSequence(), List.of());
        try {
            return Optional.of(flush(input, ctx));
        } finally {
            progress.idle();
        }
    }

    private Outcome flush(FlushInput input, OpContext ctx) throws StorageException {
        stats.flushAttempts.incrementAndGet();
        long tableId = tableIds.next();
        Path temp = layout.tmpDir().resolve(TableIdAllocator.tempFileName(tableId));
        Path output = layout.sstDir().resolve(SSTableMeta.fileName(tableId));

        Manifest published;
        try {
            progress.phase(Phase.WRITE_OUTPUT);
            SSTableMeta meta = new SSTableDiskWriter(io).writeAndFinalize(
                    temp, output, tableId, input.records(), input.entryCount(), ctx);

            progress.phase(Phase.FINALIZE_OUTPUT);
            failpoints.hit(FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST, ctx);
            TableSource table = DiskSSTableTableSource.open(output, meta, stats);

            progress.phase(Phase.PUBLISH_MANIFEST);
            published = publish(input, meta, table, ctx);
        } catch (ManifestPublishException e) {
            if (e.renamed()) {
                throw fatal(input, e);
            }
            throw failed(input, e, temp, output);
        } catch (InstallFailure e) {
            throw fatal(input, e.getCause());
        } catch (Exception e) {
            throw failed(input, e, temp, output);
        }

        stats.flushPublications.incrementAndGet();
        progress.phase(Phase.RETIRE_INPUTS);
        try {
            retireWal(input, ctx);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.STORAGE_IO, null,
                    "Flush of generation " + input.memtableGeneration()
                            + " was published but its WAL segments were not removed", e);
        }
        return new Outcome(tableId, published.generation(), published.flushedThroughSequence());
    }

    private Manifest publish(FlushInput input, SSTableMeta meta, TableSource table, OpContext ctx)
            throws Exception {
        return host.underEngineWriteLock(ctx, () -> {
            Optional<FlushInput> current = host.oldestImmutable();
            if (current.isEmpty() || current.get().memtableGeneration() != input.memtableGeneration()) {
                throw new IllegalStateException("Frozen generation " + input.memtableGeneration()
                        + " is no longer the oldest");
            }
            Manifest manifest = manifests.publish(base -> withTable(base, meta, input.maxSequence()), ctx);
            try {
                host.installFlushResult(input.memtableGeneration(), table);
            } catch (RuntimeException e) {
                throw new InstallFailure(e);
            }
            return manifest;
        });
    }

    private Manifest withTable(Manifest base, SSTableMeta meta, long maxSequence) {
        List<SSTableMeta> tables = new ArrayList<>(base.sstables());
        tables.add(meta);
        return new Manifest(base.formatVersion(), base.generation(),
                Math.max(base.nextTableId(), tableIds.peek()),
                Math.max(base.flushedThroughSequence(), maxSequence),
                tables);
    }

    private void retireWal(FlushInput input, OpContext ctx) throws IOException {
        failpoints.hit(FailpointNames.FLUSH_AFTER_MANIFEST_PUBLISH_BEFORE_WAL_DELETE, ctx);
        for (long segmentId : input.walSegmentIds()) {
            io.delete(layout.walDir().resolve(WalFileNames.segmentFileName(segmentId)));
        }
        io.fsyncDirectory(layout.walDir(), null, ctx);
    }

    private StorageException fatal(FlushInput input, Throwable cause) {
        StorageException error = new StorageException(ErrorCode.STORAGE_IO, null,
                "Flush of generation " + input.memtableGeneration()
                        + " left the durable state uncertain", cause);
        host.reportFatalError(error);
        return error;
    }

    private StorageException failed(FlushInput input, Exception cause, Path temp, Path output) {
        progress.phase(Phase.CLEANUP);
        removeOwn(temp, stats.temporaryFilesRemoved, cause);
        removeOwn(output, stats.orphanFilesRemoved, cause);
        if (cause instanceof StorageException storage) {
            return storage;
        }
        ErrorCode code = cause instanceof IOException ? ErrorCode.STORAGE_IO : ErrorCode.INTERNAL_ERROR;
        return new StorageException(code, null,
                "Flush of generation " + input.memtableGeneration() + " failed", cause);
    }

    private void removeOwn(Path file, AtomicLong counter, Exception failure) {
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                io.delete(file);
                counter.incrementAndGet();
            }
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    private static final class InstallFailure extends Exception {
        InstallFailure(RuntimeException cause) {
            super(cause);
        }
    }
}
