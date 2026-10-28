package rs.raf.dlsm.fixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.fixtures.WalFixtureWriter.Mutation;
import rs.raf.dlsm.wal.WalReader;
import rs.raf.dlsm.wal.WalScanResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** The fixture writer against the byte layout documented in FORMAT.md §3. */
class WalFixtureWriterTest {

    /** FORMAT.md §3.6, "Ceo fajl": segment 1 with PUT(alpha,"A"), DELETE(beta), PUT(gamma,""). */
    private static final String FORMAT_EXAMPLE = """
            00000000  4C 53 4D 57 41 4C 30 31 00 01 00 00 00 00 00 00
            00000010  00 00 00 01 E8 56 78 A9 00 00 00 24 FF FF FF DB
            00000020  00 01 01 00 00 00 00 00 00 00 00 01 00 05 00 00
            00000030  00 01 61 6C 70 68 61 41 F2 20 F3 D8 00 00 00 22
            00000040  FF FF FF DD 00 01 02 00 00 00 00 00 00 00 00 02
            00000050  00 04 00 00 00 00 62 65 74 61 18 19 3F 8C 00 00
            00000060  00 23 FF FF FF DC 00 01 01 00 00 00 00 00 00 00
            00000070  00 03 00 05 00 00 00 00 67 61 6D 6D 61 7E 1E 14
            00000080  BF
            """;

    @TempDir
    Path walDir;

    /** The bytes of a hex dump: every line without its leading offset column. */
    private static byte[] hexDump(String dump) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String line : dump.strip().split("\n")) {
            String bytes = line.strip().substring(line.strip().indexOf(' ')).replace(" ", "");
            out.writeBytes(HexFormat.of().parseHex(bytes));
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static Mutation[] formatExampleMutations() {
        return new Mutation[] {
                Mutation.put(1, "alpha", ascii("A")),
                Mutation.delete(2, "beta"),
                Mutation.put(3, "gamma", new byte[0])};
    }

    @Test
    void segmentIsByteForByteTheFormatExample() {
        byte[] expected = hexDump(FORMAT_EXAMPLE);

        assertEquals(129, expected.length);
        assertArrayEquals(expected, WalFixtureWriter.segment(1, formatExampleMutations()));
    }

    // FORMAT.md §3.6: the header and each frame on their own.
    @Test
    void headerAndFramesHaveTheDocumentedLengths() {
        assertEquals(24, WalFixtureWriter.segmentHeader(1).length);
        assertEquals(36, WalFixtureWriter.frame(Mutation.put(1, "alpha", ascii("A"))).length);
        assertEquals(34, WalFixtureWriter.frame(Mutation.delete(2, "beta")).length);
        assertEquals(35, WalFixtureWriter.frame(Mutation.put(3, "gamma", new byte[0])).length);
    }

    // FORMAT.md §3.3: an empty PUT and a DELETE differ only in the kind byte.
    @Test
    void emptyPutAndDeleteDifferOnlyInKindAndCrc() {
        byte[] put = WalFixtureWriter.frame(Mutation.put(7, "key", new byte[0]));
        byte[] delete = WalFixtureWriter.frame(Mutation.delete(7, "key"));

        assertEquals(put.length, delete.length);
        assertEquals(1, put[10]);
        assertEquals(2, delete[10]);
        for (int i = 0; i < put.length - 4; i++) {
            if (i != 10) {
                assertEquals(put[i], delete[i], "byte " + i);
            }
        }
    }

    @Test
    void writeSegmentCreatesTheNamedFile() throws Exception {
        Path file = WalFixtureWriter.writeSegment(walDir, 5, Mutation.put(3, "alpha", ascii("A2")));

        assertEquals("wal-00000000000000000005.log", file.getFileName().toString());
        assertArrayEquals(WalFixtureWriter.segment(5, Mutation.put(3, "alpha", ascii("A2"))),
                Files.readAllBytes(file));
    }

    @Test
    void writeSegmentNeverReplacesAFile() throws Exception {
        WalFixtureWriter.writeSegment(walDir, 2, Mutation.put(1, "a", ascii("x")));

        assertThrows(FileAlreadyExistsException.class,
                () -> WalFixtureWriter.writeSegment(walDir, 2, Mutation.put(1, "a", ascii("y"))));
    }

    @Test
    void rejectsWhatNoValidFrameHolds() {
        assertThrows(IllegalArgumentException.class, () -> Mutation.put(0, "a", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Mutation.put(1, "", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Mutation.put(1, "k".repeat(129), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Mutation.put(1, "no space", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Mutation.put(1, "a", new byte[262_145]));
    }

    // Both sides of the FORMAT.md contract agree: the production reader accepts the fixture.
    @Test
    void productionReaderReadsTheFixtureAsValid() throws Exception {
        Path file = WalFixtureWriter.writeSegment(walDir, 1, formatExampleMutations());

        WalScanResult scan = WalReader.scanSegment(file);

        assertEquals(WalScanResult.Status.VALID, scan.status());
        assertEquals(3, scan.entries().size());
        assertEquals(3, scan.lastSequence().getAsLong());
    }
}
