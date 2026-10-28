package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalCatalogTest {

    private static final int HEADER = WalFormat.SEGMENT_HEADER_BYTES;

    private static WalCatalog.Segment headerOnly(long segmentId) {
        return new WalCatalog.Segment(segmentId, HEADER, null, null);
    }

    @Test
    void newCatalogIsEmpty() {
        assertEquals(List.of(), new WalCatalog().snapshot());
    }

    @Test
    void headerOnlySegmentHasNoSequences() {
        WalCatalog catalog = new WalCatalog();
        catalog.register(headerOnly(1));

        assertEquals(List.of(headerOnly(1)), catalog.snapshot());
    }

    // WalSegment writes a prefix and then the rest of each frame (FORMAT.md §3.3).
    @Test
    void framesGrowSizeAndSequences() {
        WalCatalog catalog = new WalCatalog();
        catalog.register(headerOnly(3));

        catalog.written(3, 8);
        catalog.written(3, 33);
        catalog.framed(3, 7);
        catalog.written(3, 8);
        catalog.written(3, 30);
        catalog.framed(3, 8);

        assertEquals(List.of(new WalCatalog.Segment(3, HEADER + 41 + 38, 7L, 8L)),
                catalog.snapshot());
    }

    // CP1-WAL-004 (b): the prefix stays in the file, but it is not a record.
    @Test
    void prefixOfFailedAppendCountsInSizeOnly() {
        WalCatalog catalog = new WalCatalog();
        catalog.register(headerOnly(3));

        catalog.written(3, 8);

        assertEquals(List.of(new WalCatalog.Segment(3, HEADER + 8, null, null)),
                catalog.snapshot());
    }

    @Test
    void recoveredSegmentKeepsItsSequences() {
        WalCatalog catalog = new WalCatalog();
        WalCatalog.Segment kept = new WalCatalog.Segment(5, 129, 1L, 3L);
        catalog.register(kept);

        assertEquals(List.of(kept), catalog.snapshot());
    }

    // CP1 §24: arrays are ordered by numeric ID, never by registration.
    @Test
    void snapshotIsSortedById() {
        WalCatalog catalog = new WalCatalog();
        catalog.register(headerOnly(9));
        catalog.register(headerOnly(2));
        catalog.register(headerOnly(10));
        catalog.register(headerOnly(5));

        assertEquals(List.of(2L, 5L, 9L, 10L),
                catalog.snapshot().stream().map(WalCatalog.Segment::segmentId).toList());
    }

    @Test
    void removedSegmentLeavesTheCatalog() {
        WalCatalog catalog = new WalCatalog();
        catalog.register(headerOnly(1));
        catalog.register(headerOnly(2));

        catalog.removed(1);
        catalog.removed(42);

        assertEquals(List.of(headerOnly(2)), catalog.snapshot());
    }

    @Test
    void snapshotDoesNotChangeLater() {
        WalCatalog catalog = new WalCatalog();
        catalog.register(headerOnly(1));
        List<WalCatalog.Segment> before = catalog.snapshot();

        catalog.written(1, 40);
        catalog.framed(1, 1);

        assertEquals(List.of(headerOnly(1)), before);
    }

    // CP1 §16.1: a segment ID is never used twice.
    @Test
    void registeringAnIdTwiceFails() {
        WalCatalog catalog = new WalCatalog();
        catalog.register(headerOnly(4));

        assertThrows(IllegalStateException.class, () -> catalog.register(headerOnly(4)));
    }

    @Test
    void updatingAnUnknownSegmentFails() {
        WalCatalog catalog = new WalCatalog();

        assertThrows(IllegalStateException.class, () -> catalog.written(1, 8));
        assertThrows(IllegalStateException.class, () -> catalog.framed(1, 1));
    }

    @Test
    void segmentRejectsImpossibleValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new WalCatalog.Segment(0, HEADER, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WalCatalog.Segment(1, HEADER - 1, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WalCatalog.Segment(1, HEADER, 1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WalCatalog.Segment(1, HEADER, 5L, 4L));
    }
}
