package rs.raf.dlsm.maintenance;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.engine.EngineView;
import rs.raf.dlsm.engine.MaintenanceHost;
import rs.raf.dlsm.engine.MergingIterator;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class CompactionJob {

    public static final int MANUAL_MINIMUM_INPUTS = 2;
    public static final int AUTOMATIC_MINIMUM_INPUTS = 4;

    public record Outcome(List<Long> inputTableIds, Long outputTableId, long manifestGeneration) {
        public Outcome {
            inputTableIds = List.copyOf(inputTableIds);
        }

        public boolean published() {
            return outputTableId != null;
        }
    }

    private final DataLayout layout;
    private final StorageIO io;
    private final Failpoints failpoints;
    private final StorageStats stats;
    private final ManifestManager manifests;
    private final TableIdAllocator tableIds;
    private final MaintenanceHost host;
    private final MaintenanceProgress progress;

    public CompactionJob(DataLayout layout, StorageIO io, Failpoints failpoints, StorageStats stats,
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

    public Outcome compact(int minimumInputs, OpContext ctx) throws StorageException {
        Objects.requireNonNull(ctx, "ctx");
        if (minimumInputs < MANUAL_MINIMUM_INPUTS) {
            throw new IllegalArgumentException("Compaction needs at least two inputs");
        }

        Manifest base = manifests.current();
        List<TableSource> inputs = select(base, host.currentView());
        List<Long> inputIds = inputs.stream().map(TableSource::tableId).toList();
        if (inputs.size() < minimumInputs) {
            return new Outcome(inputIds.size() <= 1 ? inputIds : List.of(), null, base.generation());
        }

        progress.begin(Operation.COMPACTION, null, inputIds);
        try {
            return merge(inputs, inputIds, ctx);
        } finally {
            progress.idle();
        }
    }

    private Outcome merge(List<TableSource> inputs, List<Long> inputIds, OpContext ctx) throws StorageException {
        stats.compactionAttempts.incrementAndGet();
        long tableId = tableIds.next();
        Path temp = layout.tmpDir().resolve(TableIdAllocator.tempFileName(tableId));
        Path output = layout.sstDir().resolve(SSTableMeta.fileName(tableId));

        Manifest published;
        try {
            failpoints.hit(FailpointNames.COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT, ctx);

            progress.phase(Phase.WRITE_OUTPUT);
            long entries = count(inputs);
            SSTableMeta meta = writeOutput(inputs, temp, output, tableId, entries, ctx);

            progress.phase(Phase.FINALIZE_OUTPUT);
            failpoints.hit(FailpointNames.COMPACTION_AFTER_OUTPUT_FINALIZE_BEFORE_MANIFEST, ctx);
            TableSource table = DiskSSTableTableSource.open(output, meta, stats);

            progress.phase(Phase.PUBLISH_MANIFEST);
            published = publish(inputIds, meta, table, ctx);
        } catch (ManifestPublishException e) {
            if (e.renamed()) {
                throw fatal(e);
            }
            throw failed(e, temp, output);
        } catch (InstallFailure e) {
            throw fatal(e.getCause());
        } catch (Exception e) {
            throw failed(e, temp, output);
        }

        stats.compactionPublications.incrementAndGet();
        progress.phase(Phase.RETIRE_INPUTS);
        try {
            retireInputs(inputs, ctx);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.STORAGE_IO, null,
                    "Compaction was published but its inputs were not removed", e);
        }
        return new Outcome(inputIds, tableId, published.generation());
    }

    private static List<TableSource> select(Manifest base, EngineView view) {
        Map<Long, TableSource> byId = new HashMap<>();
        for (TableSource table : view.tables()) {
            byId.put(table.tableId(), table);
        }
        List<TableSource> selected = new ArrayList<>();
        for (SSTableMeta meta : base.sstables()) {
            TableSource table = byId.remove(meta.tableId());
            if (table == null || !table.meta().equals(meta)) {
                throw new IllegalStateException("Engine view does not match manifest table " + meta.tableId());
            }
            selected.add(table);
        }
        if (!byId.isEmpty()) {
            throw new IllegalStateException("Engine view has unpublished tables " + byId.keySet());
        }
        return selected;
    }

    private static long count(List<TableSource> inputs) throws StorageException {
        Iterator<InternalRecord> merged = open(inputs);
        long count = 0;
        try {
            while (merged.hasNext()) {
                merged.next();
                count++;
            }
        } catch (UncheckedStorageException e) {
            throw e.getCause();
        }
        return count;
    }

    private SSTableMeta writeOutput(List<TableSource> inputs, Path temp, Path output, long tableId,
                                    long entries, OpContext ctx) throws IOException, StorageException {
        try {
            return new SSTableDiskWriter(io).writeAndFinalize(temp, output, tableId, open(inputs), entries, ctx);
        } catch (UncheckedStorageException e) {
            throw e.getCause();
        }
    }

    private static Iterator<InternalRecord> open(List<TableSource> inputs) throws StorageException {
        List<Iterator<InternalRecord>> sources = new ArrayList<>(inputs.size());
        for (TableSource input : inputs) {
            sources.add(input.iterator(""));
        }
        return new MergingIterator(sources);
    }

    private Manifest publish(List<Long> inputIds, SSTableMeta meta, TableSource table, OpContext ctx)
            throws Exception {
        Set<Long> selected = new LinkedHashSet<>(inputIds);
        return host.underEngineWriteLock(ctx, () -> {
            Set<Long> inView = new LinkedHashSet<>(host.currentView().tableIds());
            Set<Long> inManifest = new LinkedHashSet<>();
            for (SSTableMeta current : manifests.current().sstables()) {
                inManifest.add(current.tableId());
            }
            if (!inView.containsAll(selected) || !inManifest.containsAll(selected)) {
                throw new IllegalStateException("Compaction inputs " + selected + " are no longer published");
            }
            Manifest manifest = manifests.publish(base -> replace(base, selected, meta), ctx);
            try {
                host.installCompactionResult(selected, table);
            } catch (RuntimeException e) {
                throw new InstallFailure(e);
            }
            return manifest;
        });
    }

    private Manifest replace(Manifest base, Set<Long> selected, SSTableMeta output) {
        List<SSTableMeta> tables = new ArrayList<>();
        for (SSTableMeta meta : base.sstables()) {
            if (!selected.contains(meta.tableId())) {
                tables.add(meta);
            }
        }
        tables.add(output);
        return new Manifest(base.formatVersion(), base.generation(),
                Math.max(base.nextTableId(), tableIds.peek()),
                base.flushedThroughSequence(), tables);
    }

    private void retireInputs(List<TableSource> inputs, OpContext ctx) throws IOException {
        failpoints.hit(FailpointNames.COMPACTION_AFTER_MANIFEST_PUBLISH_BEFORE_INPUT_DELETE, ctx);
        for (TableSource input : inputs) {
            io.delete(layout.sstDir().resolve(input.meta().file()));
        }
        io.fsyncDirectory(layout.sstDir(), null, ctx);
    }

    private StorageException fatal(Throwable cause) {
        StorageException error = new StorageException(ErrorCode.STORAGE_IO, null,
                "Compaction left the durable state uncertain", cause);
        host.reportFatalError(error);
        return error;
    }

    private StorageException failed(Exception cause, Path temp, Path output) {
        progress.phase(Phase.CLEANUP);
        removeOwn(temp, stats.temporaryFilesRemoved, cause);
        removeOwn(output, stats.orphanFilesRemoved, cause);
        if (cause instanceof StorageException storage) {
            return storage;
        }
        ErrorCode code = cause instanceof IOException ? ErrorCode.STORAGE_IO : ErrorCode.INTERNAL_ERROR;
        return new StorageException(code, null, "Compaction failed", cause);
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
