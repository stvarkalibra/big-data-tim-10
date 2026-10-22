package rs.raf.dlsm.wal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.log.JsonLog;
import rs.raf.dlsm.stats.StorageStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Appending and forcing records, checked against an independent read with WalReader. */
class WalSegmentTest {

    private static final OpContext CTX = OpContext.background();

    @TempDir
    Path root;

    private Path walDir;
    private RecordingStorageIO io;
    private StorageStats stats;
    private WalSegments segments;
    private final List<WalSegment> opened = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        walDir = Files.createDirectories(root.resolve("lsm/wal"));
        Path tmpDir = Files.createDirectories(root.resolve("lsm/tmp"));
        io = new RecordingStorageIO(new NoOpFailpoints());
        stats = new StorageStats();
        JsonLog log = new JsonLog("test",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        segments = new WalSegments(walDir, tmpDir, io, new NoOpFailpoints(), stats,
                WalIdAllocator.startingAt(1), log);
    }

    @AfterEach
    void closeSegments() {
        opened.forEach(WalSegment::close);
    }

    private WalSegment newSegment() throws IOException {
        WalSegment segment = segments.createSegment(CTX);
        opened.add(segment);
        io.clearCalls();
        return segment;
    }

    private Path fileOf(WalSegment segment) {
        return walDir.resolve(WalFileNames.segmentFileName(segment.segmentId()));
    }

    private long counter(String name) {
        return stats.snapshot().get(name);
    }

    // ===== Written bytes read back =====

    @Test
    void appendedRecordsReadBackInOrder() throws IOException {
        WalSegment segment = newSegment();
        List<InternalRecord> records = List.of(
                InternalRecord.put("alpha", 1, "A".getBytes(StandardCharsets.US_ASCII)),
                InternalRecord.delete("beta", 2),
                InternalRecord.put("gamma", 3, new byte[0]),
                InternalRecord.put("k".repeat(128), 4, new byte[InternalRecord.MAX_VALUE_BYTES]));

        for (InternalRecord record : records) {
            segment.append(record, CTX);
            segment.force(CTX);
        }

        WalScanResult scan = WalReader.scanSegment(fileOf(segment));
        assertEquals(WalScanResult.Status.VALID, scan.status());
        assertEquals(records, scan.records());
    }

    @Test
    void writesExactlyTheEncodedFrames() throws IOException {
        WalSegment segment = newSegment();
        InternalRecord put = InternalRecord.put("alpha", 1, "A".getBytes(StandardCharsets.US_ASCII));
        InternalRecord delete = InternalRecord.delete("beta", 2);

        segment.append(put, CTX);
        segment.append(delete, CTX);
        segment.force(CTX);

        byte[] expected = concat(WalCodec.encodeSegmentHeader(1),
                WalCodec.encodeFrame(put), WalCodec.encodeFrame(delete));
        assertArrayEquals(expected, Files.readAllBytes(fileOf(segment)));
    }

    @Test
    void severalAppendsCanShareOneForce() throws IOException {
        WalSegment segment = newSegment();

        for (int i = 1; i <= 10; i++) {
            segment.append(InternalRecord.put("k" + i, i, new byte[]{(byte) i}), CTX);
        }
        segment.force(CTX);

        assertEquals(10, WalReader.scanSegment(fileOf(segment)).entries().size());
        assertEquals(1, counter("walForcesSucceeded"));
    }

    @Test
    void rotatedSegmentsFormOneLog() throws IOException {
        WalSegment first = newSegment();
        first.append(InternalRecord.put("a", 1, new byte[]{1}), CTX);
        first.force(CTX);
        WalSegment second = newSegment();
        second.append(InternalRecord.put("a", 2, new byte[]{2}), CTX);
        second.force(CTX);

        WalLogScan log = WalReader.scanLog(walDir);

        assertEquals(WalScanResult.Status.VALID, log.status());
        assertEquals(List.of(1L, 2L), log.segmentIds());
        assertEquals(2, log.lastSequence().getAsLong());
    }

    // ===== StorageIO calls =====

    @Test
    void appendIsTwoWritesAndForceIsOneForce() throws IOException {
        WalSegment segment = newSegment();
        InternalRecord record = InternalRecord.put("alpha", 1, new byte[5]);
        int frameLength = WalCodec.encodeFrame(record).length;

        segment.append(record, CTX);
        segment.force(CTX);

        assertEquals(List.of("writeFully", "writeFully", "force"), io.calls());
        // Without a configured prefix the first write is the guarded header.
        assertEquals(List.of(WalSegment.DEFAULT_PREFIX_BYTES,
                frameLength - WalSegment.DEFAULT_PREFIX_BYTES), io.writeSizes());
    }

    // ===== Counters (CP1 §24) =====

    @Test
    void countsAppendsForcesAndFrameBytes() throws IOException {
        WalSegment segment = newSegment();
        long frameBytes = 0;

        for (int i = 1; i <= 5; i++) {
            InternalRecord record = i % 2 == 0
                    ? InternalRecord.delete("key" + i, i)
                    : InternalRecord.put("key" + i, i, new byte[i * 10]);
            segment.append(record, CTX);
            segment.force(CTX);
            frameBytes += WalCodec.encodeFrame(record).length;
        }

        assertEquals(5, counter("walAppendAttempts"));
        assertEquals(5, counter("walAppendsCompleted"));
        assertEquals(5, counter("walForceAttempts"));
        assertEquals(5, counter("walForcesSucceeded"));
        assertEquals(frameBytes, counter("walBytesWritten"));
    }

    // Segment headers are not record appends: creating segments counts nothing.
    @Test
    void creatingSegmentsCountsNoWalBytes() throws IOException {
        newSegment();
        newSegment();

        assertEquals(0, counter("walBytesWritten"));
        assertEquals(0, counter("walAppendAttempts"));
        assertEquals(0, counter("walForceAttempts"));
    }

    // ===== Lifecycle =====

    @Test
    void closedSegmentRefusesAppendAndForce() throws IOException {
        WalSegment segment = newSegment();
        segment.close();

        assertThrows(IOException.class,
                () -> segment.append(InternalRecord.put("a", 1, new byte[1]), CTX));
        assertThrows(IOException.class, () -> segment.force(CTX));
        assertEquals(0, counter("walAppendAttempts"));
    }

    @Test
    void closeIsSafeToRepeat() throws IOException {
        WalSegment segment = newSegment();

        segment.close();
        assertDoesNotThrow(segment::close);
    }

    @Test
    void healthySegmentHasNoUncertainTail() throws IOException {
        WalSegment segment = newSegment();
        segment.append(InternalRecord.put("a", 1, new byte[1]), CTX);
        segment.force(CTX);

        assertFalse(segment.hasUncertainTail());
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
