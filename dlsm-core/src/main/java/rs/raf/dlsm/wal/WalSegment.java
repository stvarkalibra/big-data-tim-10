package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.MutationLog;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One durable WAL segment opened for appending: the engine's
 * {@link MutationLog} (CP1 §9 steps 5–7). Created only by
 * {@link WalSegments}, after the segment header is durable.
 *
 * <p>Frames are written only through {@link StorageIO}. The engine calls
 * {@link #append} and {@link #force} under its write lock, one mutation at
 * a time.
 */
public final class WalSegment implements MutationLog {

    private final long segmentId;
    private final FileChannel channel;
    private final StorageIO io;
    private final Failpoints failpoints;
    private final StorageStats stats;
    private final JsonLog log;

    WalSegment(long segmentId, FileChannel channel, StorageIO io, Failpoints failpoints,
               StorageStats stats, JsonLog log) {
        this.segmentId = segmentId;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.io = Objects.requireNonNull(io, "io");
        this.failpoints = Objects.requireNonNull(failpoints, "failpoints");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public long segmentId() {
        return segmentId;
    }

    @Override
    public void append(InternalRecord record, OpContext ctx) throws IOException {
        io.writeFully(channel, ByteBuffer.wrap(WalCodec.encodeFrame(record)), null, ctx);
    }

    @Override
    public void force(OpContext ctx) throws IOException {
        io.force(channel, null, ctx);
    }

    /**
     * Releases the file handle after rotation. Everything was forced
     * already, so a failure cannot lose data; it is logged, not thrown.
     */
    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("walSegmentId", segmentId);
            fields.put("message", "Closing WAL segment failed: " + e.getClass().getSimpleName());
            log.warn("WAL_SEGMENT_CLOSE_FAILED", fields);
        }
    }
}
