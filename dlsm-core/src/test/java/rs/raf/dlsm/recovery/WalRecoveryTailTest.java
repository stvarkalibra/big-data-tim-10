package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.wal.WalFormat;
import rs.raf.dlsm.wal.WalLogScan;
import rs.raf.dlsm.wal.WalScanResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static rs.raf.dlsm.recovery.WalRecoveryHarness.*;

/** CP1 §21.1 steps 7–9: corruption stops startup, only a torn tail is cut. */
class WalRecoveryTailTest {

    private static final OpContext CTX = OpContext.background();

    private static final InternalRecord BASE1 = put("base1", 1, "V1");
    private static final InternalRecord BASE2 = put("base2", 2, "V2");
    private static final InternalRecord TORN = put("torn", 3, "T".repeat(20));

    @TempDir
    Path root;

    private WalRecoveryHarness h;
    /** Length L of the segment before the interrupted append (CP1-WAL-002). */
    private int baseLength;
    private byte[] full;

    @BeforeEach
    void setUp() throws Exception {
        h = new WalRecoveryHarness(root);
        baseLength = segment(1, BASE1, BASE2).length;
        full = segment(1, BASE1, BASE2, TORN);
    }

    // ===== Steps 7–8: corruption stops startup =====

    // CP1-WAL-003: a complete frame with a bad checksum is never cut, even at EOF.
    @Test
    void corruptLastFrameStopsStartupWithLocation() throws Exception {
        byte[] bytes = full.clone();
        bytes[bytes.length - 1] ^= 0x01;
        Path file = h.writeSegment(1, bytes);

        StorageException e = assertThrows(StorageException.class, () -> h.recovery.scanLog());

        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(WalFormat.CATEGORY_CHECKSUM, e.category());
        assertTrue(e.getMessage().contains("wal-00000000000000000001.log"), e.getMessage());
        assertTrue(e.getMessage().contains("offset " + baseLength), e.getMessage());
        assertTrue(h.log().contains("\"event\":\"WAL_CORRUPTION\""), h.log());
        assertTrue(h.log().contains("\"corruptionCategory\":\"CHECKSUM\""), h.log());
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test
    void corruptionCategoryIsKeptForEveryKind() throws Exception {
        byte[] header = full.clone();
        header[0] ^= 0x01;
        byte[] guard = full.clone();
        guard[baseLength + 1] ^= 0x01;

        h.writeSegment(1, header);
        assertEquals(WalFormat.CATEGORY_HEADER,
                assertThrows(StorageException.class, () -> h.recovery.scanLog()).category());

        h.writeSegment(1, guard);
        assertEquals(WalFormat.CATEGORY_LENGTH_GUARD,
                assertThrows(StorageException.class, () -> h.recovery.scanLog()).category());
    }

    @Test
    void validLogNeedsNoRepair() throws Exception {
        Path file = h.writeSegment(1, full);

        WalLogScan scan = h.recovery.scanLog();

        assertFalse(h.recovery.truncateTornTail(scan, CTX));
        assertEquals(List.of(), h.io.calls());
        assertEquals(0, h.counter("walTornTailsTruncated"));
        assertArrayEquals(full, Files.readAllBytes(file));
    }

    // ===== Step 9: torn tail =====

    // CP1-WAL-002 cut points: inside the guarded header, right after it, one byte before the end.
    @Test
    void cutsTornTailExactlyAtFrameStartAndForces() throws Exception {
        for (int prefix : new int[]{1, 7, 8, 9, frameLength(TORN) - 1}) {
            Path file = h.writeSegment(1, Arrays.copyOf(full, baseLength + prefix));
            h.io.clearCalls();
            h.stats.reset();

            WalLogScan scan = h.recovery.scanLog();
            assertTrue(h.recovery.truncateTornTail(scan, CTX), "prefix " + prefix);

            assertArrayEquals(Arrays.copyOf(full, baseLength), Files.readAllBytes(file), "prefix " + prefix);
            assertEquals(List.of("truncate", "force"), h.io.calls(), "prefix " + prefix);
            assertEquals(1, h.counter("walTornTailsTruncated"), "prefix " + prefix);
            assertEquals(List.of(BASE1, BASE2), scan.records(), "prefix " + prefix);
        }
    }

    @Test
    void logsTheRepair() throws Exception {
        h.writeSegment(1, Arrays.copyOf(full, baseLength + 5));

        h.recovery.truncateTornTail(h.recovery.scanLog(), CTX);

        assertTrue(h.log().contains("\"event\":\"WAL_TORN_TAIL_TRUNCATED\""), h.log());
        assertTrue(h.log().contains("\"fileOffset\":" + baseLength), h.log());
    }

    // CP1-WAL-002 / WAL-008: a second recovery finds nothing more to cut.
    @Test
    void secondRecoveryCutsNothingMore() throws Exception {
        Path file = h.writeSegment(1, Arrays.copyOf(full, baseLength + 12));
        h.recovery.truncateTornTail(h.recovery.scanLog(), CTX);
        h.io.clearCalls();

        WalLogScan second = h.recovery.scanLog();

        assertEquals(WalScanResult.Status.VALID, second.status());
        assertFalse(h.recovery.truncateTornTail(second, CTX));
        assertEquals(List.of(), h.io.calls());
        assertEquals(1, h.counter("walTornTailsTruncated"));
        assertEquals(baseLength, Files.size(file));
    }

    @Test
    void tornFirstFrameLeavesHeaderOnlySegment() throws Exception {
        byte[] only = segment(1, TORN);
        Path file = h.writeSegment(1, Arrays.copyOf(only, WalFormat.SEGMENT_HEADER_BYTES + 3));

        h.recovery.truncateTornTail(h.recovery.scanLog(), CTX);

        assertEquals(WalFormat.SEGMENT_HEADER_BYTES, Files.size(file));
    }

    @Test
    void onlyTheTornSegmentIsTouched() throws Exception {
        byte[] first = segment(1, BASE1);
        h.writeSegment(1, first);
        byte[] second = segment(2, BASE2, TORN);
        int secondBase = segment(2, BASE2).length;
        h.writeSegment(2, Arrays.copyOf(second, secondBase + 10));
        byte[] emptyNext = segment(3);
        h.writeSegment(3, emptyNext);

        h.recovery.truncateTornTail(h.recovery.scanLog(), CTX);

        assertArrayEquals(first, Files.readAllBytes(h.segmentFile(1)));
        assertEquals(secondBase, Files.size(h.segmentFile(2)));
        assertArrayEquals(emptyNext, Files.readAllBytes(h.segmentFile(3)));
    }

    // A torn frame followed by records is corruption: nothing is cut.
    @Test
    void tornFrameBeforeLaterRecordsIsNotCut() throws Exception {
        byte[] torn = Arrays.copyOf(full, baseLength + 10);
        Path file = h.writeSegment(1, torn);
        h.writeSegment(2, segment(2, put("later", 4, "x")));

        StorageException e = assertThrows(StorageException.class, () -> h.recovery.scanLog());

        assertEquals(WalFormat.CATEGORY_FRAME, e.category());
        assertArrayEquals(torn, Files.readAllBytes(file));
    }
}
