package rs.raf.dlsm.wal;

import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.WalFactory;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

/**
 * Creates WAL segments with the publish protocol of CP1 §16.1 (FORMAT.md
 * §3.1) and is the engine's {@link WalFactory} for rotation.
 *
 * <ol>
 *   <li>write the complete 24-byte header to {@code lsm/tmp/wal-<id>.log.tmp};</li>
 *   <li>force the temporary file;</li>
 *   <li>atomically move it to {@code lsm/wal/wal-<id>.log}, never replacing;</li>
 *   <li>force {@code lsm/wal/}.</li>
 * </ol>
 *
 * Only after step 4 is the segment returned and may take records. A crash
 * before step 3 leaves a temporary file for recovery to remove; after it, a
 * valid header-only segment. Every step goes through {@link StorageIO}.
 *
 * <p>It also deletes segments that a flush covered, and keeps the
 * {@link WalCatalog} of every segment it created or deleted.
 */
public final class WalSegments implements WalFactory {

    private final Path walDir;
    private final Path tmpDir;
    private final StorageIO io;
    private final Failpoints failpoints;
    private final StorageStats stats;
    private final WalIdAllocator ids;
    private final JsonLog log;
    private final WalCatalog catalog = new WalCatalog();

    public WalSegments(Path walDir, Path tmpDir, StorageIO io, Failpoints failpoints,
                       StorageStats stats, WalIdAllocator ids, JsonLog log) {
        this.walDir = Objects.requireNonNull(walDir, "walDir");
        this.tmpDir = Objects.requireNonNull(tmpDir, "tmpDir");
        this.io = Objects.requireNonNull(io, "io");
        this.failpoints = Objects.requireNonNull(failpoints, "failpoints");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * @throws IOException if any step fails; the segment is then not created
     *                     and nothing may be appended to it
     */
    @Override
    public WalSegment createSegment(OpContext ctx) throws IOException {
        long segmentId = ids.next();
        Path temporary = tmpDir.resolve(WalFileNames.tempFileName(segmentId));
        Path published = walDir.resolve(WalFileNames.segmentFileName(segmentId));

        FileChannel channel = io.openNew(temporary);
        try {
            io.writeFully(channel,
                    ByteBuffer.wrap(WalCodec.encodeSegmentHeader(segmentId)), null, ctx);
            io.force(channel, null, ctx);
            io.atomicMoveNoReplace(temporary, published, null, ctx);
            // In wal/ from here on, even if the directory force fails.
            catalog.register(new WalCatalog.Segment(segmentId,
                    WalFormat.SEGMENT_HEADER_BYTES, null, null));
            io.fsyncDirectory(walDir, null, ctx);
        } catch (IOException | RuntimeException e) {
            closeAfterFailure(channel, e);
            throw e;
        }

        // The open channel follows the file through the rename, so appends
        // continue right after the header at offset 24.
        return new WalSegment(segmentId, channel, io, failpoints, stats, catalog, log);
    }

    /**
     * Deletes segments whose records are all at or below the manifest's
     * {@code flushedThroughSequence}, then forces {@code lsm/wal/} (CP1 §19.1
     * steps 12–13). Each deleted segment leaves the catalog at once; after a
     * failure the remaining ones stay on disk and in the catalog until
     * recovery deletes them.
     */
    public void retire(Collection<Long> segmentIds, OpContext ctx) throws IOException {
        for (long segmentId : segmentIds) {
            io.delete(walDir.resolve(WalFileNames.segmentFileName(segmentId)));
            catalog.removed(segmentId);
        }
        io.fsyncDirectory(walDir, null, ctx);
    }

    /** The segments this node has on disk, for the storage status (CP1 §24). */
    public WalCatalog catalog() {
        return catalog;
    }

    private static void closeAfterFailure(FileChannel channel, Exception failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
