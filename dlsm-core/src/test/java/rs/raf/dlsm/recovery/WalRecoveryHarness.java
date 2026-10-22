package rs.raf.dlsm.recovery;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.stats.StorageStats;
import rs.raf.dlsm.wal.RecordingStorageIO;
import rs.raf.dlsm.wal.WalCodec;
import rs.raf.dlsm.wal.WalFileNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

/**
 * A data directory with all required subdirectories and a {@link WalRecovery}
 * wired to a recording {@code StorageIO}, a real failpoint registry in test
 * mode, fresh counters and a captured JSON log.
 */
final class WalRecoveryHarness {

    final DataLayout layout;
    final RecordingStorageIO io;
    final FailpointRegistry failpoints = new FailpointRegistry(true);
    final StorageStats stats = new StorageStats();
    final ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
    final JsonLog log;
    final WalRecovery recovery;

    WalRecoveryHarness(Path root) throws IOException {
        layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        io = new RecordingStorageIO(failpoints);
        log = new JsonLog("test",
                new PrintStream(logOutput, true, StandardCharsets.UTF_8), Clock.systemUTC());
        recovery = new WalRecovery(layout, io, failpoints, stats, log);
    }

    /** Segment bytes: the header of {@code id} followed by one frame per record. */
    static byte[] segment(long id, InternalRecord... records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(WalCodec.encodeSegmentHeader(id));
        for (InternalRecord record : records) {
            out.writeBytes(WalCodec.encodeFrame(record));
        }
        return out.toByteArray();
    }

    static int frameLength(InternalRecord record) {
        return WalCodec.encodeFrame(record).length;
    }

    static InternalRecord put(String key, long sequence, String value) {
        return InternalRecord.put(key, sequence, value.getBytes(StandardCharsets.US_ASCII));
    }

    Path segmentFile(long id) {
        return layout.walDir().resolve(WalFileNames.segmentFileName(id));
    }

    Path writeSegment(long id, byte[] bytes) throws IOException {
        return Files.write(segmentFile(id), bytes);
    }

    long counter(String name) {
        return stats.snapshot().get(name);
    }

    String log() {
        return logOutput.toString(StandardCharsets.UTF_8);
    }

    /** Names in {@code lsm/wal/}, sorted. */
    List<String> walFiles() throws IOException {
        try (Stream<Path> files = Files.list(layout.walDir())) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
