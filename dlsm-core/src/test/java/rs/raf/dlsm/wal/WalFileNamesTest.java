package rs.raf.dlsm.wal;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class WalFileNamesTest {

    @Test
    void formatsSegmentNameWithTwentyDigits() {
        assertEquals("wal-00000000000000000001.log",
                WalFileNames.segmentFileName(1));
        assertEquals("wal-00000000000000000042.log",
                WalFileNames.segmentFileName(42));
        assertEquals("wal-09223372036854775807.log",
                WalFileNames.segmentFileName(Long.MAX_VALUE));
    }

    @Test
    void formatsTempNameWithSameId() {
        assertEquals("wal-00000000000000000007.log.tmp",
                WalFileNames.tempFileName(7));
    }

    @Test
    void rejectsNonPositiveIdsWhenFormatting() {
        assertThrows(IllegalArgumentException.class,
                () -> WalFileNames.segmentFileName(0));
        assertThrows(IllegalArgumentException.class,
                () -> WalFileNames.segmentFileName(-1));
        assertThrows(IllegalArgumentException.class,
                () -> WalFileNames.tempFileName(0));
    }

    @Test
    void parsesWhatItFormats() {
        for (long id : new long[]{1, 9, 10, 123_456_789, Long.MAX_VALUE}) {
            assertEquals(OptionalLong.of(id), WalFileNames.parseSegmentId(
                    WalFileNames.segmentFileName(id)));
            assertEquals(OptionalLong.of(id), WalFileNames.parseTempId(
                    WalFileNames.tempFileName(id)));
        }
    }

    @Test
    void zeroIdIsSyntacticallyValid() {
        assertEquals(OptionalLong.of(0), WalFileNames.parseSegmentId(
                "wal-00000000000000000000.log"));
    }

    @Test
    void segmentAndTempNamesDoNotMatchEachOther() {
        assertTrue(WalFileNames.parseSegmentId(
                "wal-00000000000000000003.log.tmp").isEmpty());
        assertTrue(WalFileNames.parseTempId(
                "wal-00000000000000000003.log").isEmpty());
    }

    @Test
    void rejectsWrongDigitCount() {
        assertTrue(WalFileNames.parseSegmentId("wal-1.log").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "wal-0000000000000000001.log").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "wal-000000000000000000001.log").isEmpty());
    }

    @Test
    void rejectsNonDigitCharacters() {
        assertTrue(WalFileNames.parseSegmentId(
                "wal-+0000000000000000001.log").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "wal--0000000000000000001.log").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "wal-0000000000000000000a.log").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "wal-0000000000 000000001.log").isEmpty());
    }

    @Test
    void rejectsWrongPrefixOrSuffix() {
        assertTrue(WalFileNames.parseSegmentId(
                "WAL-00000000000000000001.log").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "sst-00000000000000000001.log").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "wal-00000000000000000001.LOG").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "wal-00000000000000000001.sst").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "xwal-0000000000000000001.log").isEmpty());
    }

    @Test
    void rejectsIdsAboveLongRange() {
        assertTrue(WalFileNames.parseSegmentId(
                "wal-09223372036854775808.log").isEmpty());
        assertTrue(WalFileNames.parseSegmentId(
                "wal-99999999999999999999.log").isEmpty());
    }

    @Test
    void rejectsNullAndEmptyNames() {
        assertTrue(WalFileNames.parseSegmentId(null).isEmpty());
        assertTrue(WalFileNames.parseSegmentId("").isEmpty());
        assertTrue(WalFileNames.parseTempId(null).isEmpty());
    }
}
