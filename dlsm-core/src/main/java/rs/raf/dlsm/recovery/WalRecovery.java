package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.ImmutableMemTable;
import rs.raf.dlsm.engine.MemTable;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.WalFileNames;
import rs.raf.dlsm.wal.WalIdAllocator;
import rs.raf.dlsm.wal.WalLogScan;
import rs.raf.dlsm.wal.WalReader;
import rs.raf.dlsm.wal.WalScanResult;
import rs.raf.dlsm.wal.WalSegment;
import rs.raf.dlsm.wal.WalSegments;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The WAL part of startup recovery, CP1 §21.1 steps 7–13: read every segment
 * in ID order, repair only a torn final frame, replay what the manifest does
 * not already cover, pick the next sequence, open a new active segment and
 * retire segments that protect nothing.
 *
 * <p>It never appends to an old segment and never writes a replayed record
 * again, so running it twice after a crash gives the same logical state
 * (I6, CP1-WAL-008).
 */
public final class WalRecovery {

    /** Process-local generation of the recovered MemTable (CP1 §14.2). */
    public static final long RECOVERED_GENERATION = 1;

    private final DataLayout layout;
    private final StorageIO io;
    private final Failpoints failpoints;
    private final StorageStats stats;
    private final JsonLog log;

    public WalRecovery(DataLayout layout, StorageIO io, Failpoints failpoints,
                       StorageStats stats, JsonLog log) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.io = Objects.requireNonNull(io, "io");
        this.failpoints = Objects.requireNonNull(failpoints, "failpoints");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Runs CP1 §21.1 steps 7–12 in order.
     *
     * @param flushedThroughSequence the high-water mark of the loaded manifest
     * @param ids                    built from {@code wal/} and {@code tmp/}
     *                               before any cleanup (CP1 §16.1), so the new
     *                               segment's ID is above every segment found
     * @param memtableMaxBytes       {@code MEMTABLE_MAX_BYTES} for the recovered
     *                               generation; replay may exceed it
     * @throws StorageException with the corruption's category when the WAL is corrupt
     * @throws IOException      when a repair or the new segment cannot be made
     *                          durable; startup must then fail (CP1 §21.4)
     */
    public WalRecoveryResult run(long flushedThroughSequence, WalIdAllocator ids,
                                 long memtableMaxBytes, OpContext ctx)
            throws IOException, StorageException {

        Objects.requireNonNull(ids, "ids");

        WalLogScan scan = scanLog();                                                    // 7–8
        truncateTornTail(scan, ctx);                                                    // 9
        ImmutableMemTable recovered = replay(scan, flushedThroughSequence, memtableMaxBytes); // 10
        long lastUsed = lastUsedSequence(scan, flushedThroughSequence);                // 11

        // Step 12: never append to a segment used before the crash.
        WalSegments segments = new WalSegments(
                layout.walDir(), layout.tmpDir(), io, failpoints, stats, ids, log);
        WalSegment active = segments.createSegment(ctx);

        return new WalRecoveryResult(lastUsed, recovered, active, segments, scan);
    }

    /**
     * Steps 7–8: validates every segment header and frame in increasing
     * segment ID, with strictly increasing sequences across the whole log
     * (FORMAT.md §3.4–§3.5). A torn tail is allowed and returned; anything
     * else that is not valid stops startup.
     *
     * @throws StorageException the corruption's own category, with segment
     *                          and offset, when any segment is corrupt
     */
    WalLogScan scanLog() throws IOException, StorageException {
        WalLogScan scan = WalReader.scanLog(layout.walDir());

        if (scan.status() == WalScanResult.Status.CORRUPT) {
            WalScanResult segment = scan.corruptSegment().orElseThrow();
            WalScanResult.Corruption corruption = segment.corruption();

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("walSegmentId", segment.segmentId());
            fields.put("fileOffset", corruption.offset());
            fields.put("corruptionCategory", corruption.category());
            fields.put("message", corruption.message());
            log.error("WAL_CORRUPTION", fields);

            throw new StorageException(ErrorCode.STORAGE_IO, corruption.category(),
                    "WAL segment " + WalFileNames.segmentFileName(segment.segmentId())
                            + " is corrupt at offset " + corruption.offset()
                            + ": " + corruption.message());
        }

        return scan;
    }

    /**
     * Step 9: when, and only when, the last frame of the whole log is a
     * strict prefix (a torn tail), truncates that segment to the frame's
     * start and forces the repair, so a second crash cannot bring the stray
     * bytes back. A complete frame is never cut (CP1 §16.4).
     *
     * @return true if a torn tail was truncated
     */
    boolean truncateTornTail(WalLogScan scan, OpContext ctx) throws IOException {
        Optional<WalScanResult> torn = scan.tornSegment();
        if (torn.isEmpty()) {
            return false;
        }

        WalScanResult segment = torn.get();
        long cut = segment.tornFrameOffset();
        Path file = layout.walDir().resolve(WalFileNames.segmentFileName(segment.segmentId()));

        // StorageIO has no call to open an existing file; the repair itself
        // (truncate and force) goes through it.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            io.truncate(channel, cut);
            io.force(channel, null, ctx);
        }
        stats.increment("walTornTailsTruncated");

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("walSegmentId", segment.segmentId());
        fields.put("fileOffset", cut);
        fields.put("message", "Truncated torn WAL tail of "
                + (segment.fileSize() - cut) + " bytes; file length was " + segment.fileSize());
        log.info("WAL_TORN_TAIL_TRUNCATED", fields);

        return true;
    }

    /**
     * Step 10 (and CP1 §21.3): applies every valid record with a sequence
     * above {@code flushedThroughSequence} to one recovered generation, in
     * WAL order so the greatest sequence wins, tombstones included. Records
     * are never appended to the WAL again; each applied one counts once in
     * {@code walRecordsReplayed}.
     *
     * <p>The generation may exceed {@code MEMTABLE_MAX_BYTES}: replay never
     * drops a valid record. It is frozen with the segments that hold its
     * records, which protect it until a flush covers it.
     *
     * @return the frozen recovered generation, or {@code null} when nothing
     *         lies above the high-water mark
     */
    ImmutableMemTable replay(WalLogScan scan, long flushedThroughSequence, long memtableMaxBytes) {
        if (flushedThroughSequence < 0) {
            throw new IllegalArgumentException(
                    "flushedThroughSequence must not be negative: " + flushedThroughSequence);
        }

        MemTable recovered = new MemTable(RECOVERED_GENERATION, memtableMaxBytes);
        List<Long> protectingSegments = new ArrayList<>();

        for (WalScanResult segment : scan.segments()) {
            boolean replayedFromSegment = false;

            for (WalScanResult.Entry entry : segment.entries()) {
                InternalRecord record = entry.record();
                if (record.sequence() > flushedThroughSequence) {
                    recovered.apply(record);
                    stats.increment("walRecordsReplayed");
                    replayedFromSegment = true;
                }
            }

            if (replayedFromSegment) {
                protectingSegments.add(segment.segmentId());
            }
        }

        return protectingSegments.isEmpty() ? null : recovered.freeze(protectingSegments);
    }

    /**
     * Step 11: {@code max(flushedThroughSequence, greatest valid WAL sequence)};
     * the next mutation gets this plus one. Every valid frame counts, also a
     * version that a later one replaced, so no durable sequence is reused.
     * A torn final frame never held its sequence and does not count
     * (CP1 §21.2 step 11).
     *
     * @return the value for {@code RecoveredState.lastUsedSequence}
     */
    static long lastUsedSequence(WalLogScan scan, long flushedThroughSequence) {
        if (flushedThroughSequence < 0) {
            throw new IllegalArgumentException(
                    "flushedThroughSequence must not be negative: " + flushedThroughSequence);
        }
        return Math.max(flushedThroughSequence, scan.lastSequence().orElse(0));
    }
}
