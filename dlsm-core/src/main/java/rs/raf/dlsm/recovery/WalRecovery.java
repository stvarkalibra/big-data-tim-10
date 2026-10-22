package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.failpoint.Failpoints;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.WalFileNames;
import rs.raf.dlsm.wal.WalLogScan;
import rs.raf.dlsm.wal.WalReader;
import rs.raf.dlsm.wal.WalScanResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
}
