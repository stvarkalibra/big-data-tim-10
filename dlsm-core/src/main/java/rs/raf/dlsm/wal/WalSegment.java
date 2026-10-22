package rs.raf.dlsm.wal;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.MutationLog;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
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
import java.util.OptionalInt;

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

    /** Failpoint parameter choosing the prefix length (CP1 §26.1). */
    public static final String PREFIX_PARAMETER = "prefixBytes";

    /** First write without a configured prefix: the guarded length header. */
    public static final int DEFAULT_PREFIX_BYTES = WalFormat.FRAME_GUARD_BYTES;

    private final long segmentId;
    private final FileChannel channel;
    private final StorageIO io;
    private final Failpoints failpoints;
    private final StorageStats stats;
    private final JsonLog log;

    /** First append or force failure; the tail is uncertain from then on. */
    private volatile Exception failure;
    private volatile boolean closed;

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

    /**
     * Writes one complete frame in two {@link StorageIO} calls, a strict
     * prefix and then the rest, so {@code wal.after-record-prefix} always
     * sits between them (CP1 §26.1):
     *
     * <ul>
     *   <li>{@code wal.before-append} fires before the first byte; with
     *       {@code IO_ERROR} nothing of the frame is written;</li>
     *   <li>{@code wal.after-record-prefix} fires after exactly
     *       {@code prefixBytes} bytes; with {@code CRASH} only that prefix is
     *       on disk, with {@code IO_ERROR} it stays as an uncertain tail.</li>
     * </ul>
     *
     * The prefix length is the rule's {@code prefixBytes} parameter, kept
     * within {@code 1..frameLength-1}, or {@value #DEFAULT_PREFIX_BYTES}
     * (the guarded length header) without one. Counters follow CP1 §24.
     */
    @Override
    public void append(InternalRecord record, OpContext ctx) throws IOException {
        requireUsable();
        byte[] frame = WalCodec.encodeFrame(record);

        try {
            int prefix = prefixLength(record, frame.length, ctx);

            stats.increment("walAppendAttempts");

            io.writeFully(channel, ByteBuffer.wrap(frame, 0, prefix),
                    FailpointNames.WAL_BEFORE_APPEND, ctx);
            stats.add("walBytesWritten", prefix);

            io.writeFully(channel, ByteBuffer.wrap(frame, prefix, frame.length - prefix),
                    FailpointNames.WAL_AFTER_RECORD_PREFIX, ctx);
            stats.add("walBytesWritten", frame.length - prefix);

            stats.increment("walAppendsCompleted");
        } catch (IOException | RuntimeException e) {
            markUncertain(e);
            throw e;
        }
    }

    /**
     * True after any append or force failed. The bytes at the end of the
     * file are then unknown, so this segment never takes another record or
     * force (CP1 §9.3); only recovery may classify and truncate the tail.
     */
    public boolean hasUncertainTail() {
        return failure != null;
    }

    private void requireUsable() throws IOException {
        if (closed) {
            throw new IOException("WAL segment " + segmentId + " is closed");
        }
        Exception cause = failure;
        if (cause != null) {
            throw new IOException("WAL segment " + segmentId
                    + " has an uncertain tail after an earlier failure", cause);
        }
    }

    private void markUncertain(Exception cause) {
        if (failure == null) {
            failure = cause;
        }
    }

    /**
     * Length of the first write. A configured {@code prefixBytes} is logged
     * with the real frame length, so a test can repeat the exact cut.
     */
    private int prefixLength(InternalRecord record, int frameLength, OpContext ctx)
            throws IOException {

        OptionalInt configured = failpoints.intParameter(
                FailpointNames.WAL_AFTER_RECORD_PREFIX, ctx, PREFIX_PARAMETER);
        if (configured.isEmpty()) {
            return DEFAULT_PREFIX_BYTES;
        }

        int prefix = Math.clamp(configured.getAsInt(), 1, frameLength - 1);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", ctx == null ? null : ctx.requestId());
        fields.put("failpointName", FailpointNames.WAL_AFTER_RECORD_PREFIX);
        fields.put("walSegmentId", segmentId);
        fields.put("sequence", record.sequence());
        fields.put("fileOffset", channel.position());
        fields.put("message", "prefixBytes=" + prefix + " (configured "
                + configured.getAsInt() + "), frameLength=" + frameLength);
        log.info("WAL_RECORD_PREFIX", fields);

        return prefix;
    }

    /**
     * The durability point of a mutation (CP1 §9 step 7, I1).
     * {@code wal.after-append.before-sync} fires right before the force;
     * with {@code IO_ERROR} the force does not happen and the complete but
     * unforced frame has an unknown outcome. {@code walForcesSucceeded}
     * counts only a force that returned normally (CP1 §24).
     */
    @Override
    public void force(OpContext ctx) throws IOException {
        requireUsable();

        try {
            stats.increment("walForceAttempts");
            io.force(channel, FailpointNames.WAL_AFTER_APPEND_BEFORE_SYNC, ctx);
            stats.increment("walForcesSucceeded");
        } catch (IOException | RuntimeException e) {
            markUncertain(e);
            throw e;
        }
    }

    /**
     * Releases the file handle after rotation. Everything was forced
     * already, so a failure cannot lose data; it is logged, not thrown.
     */
    @Override
    public void close() {
        closed = true;
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
