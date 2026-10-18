package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Base64ValuesTest {

    @Test
    void specExamples() {
        // PI §18.1: one byte A is "QQ==", the empty value is "".
        assertEquals("QQ==", Base64Values.encode("A".getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals("A".getBytes(StandardCharsets.US_ASCII),
                Base64Values.decodeStrict("QQ=="));
        assertArrayEquals(new byte[0], Base64Values.decodeStrict(""));
        assertArrayEquals("Hello".getBytes(StandardCharsets.US_ASCII),
                Base64Values.decodeStrict("SGVsbG8="));
    }

    @Test
    void roundTripsArbitraryBytes() {
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        assertArrayEquals(all, Base64Values.decodeStrict(Base64Values.encode(all)));
    }

    @Test
    void rejectsNonStrictInput() {
        for (String text : new String[] {
                "QQ", "QQ=", "Q", "QQ===",    // missing or extra padding
                "QR==",                       // non-canonical trailing bits
                "-_8=", "ab_c",               // URL-safe alphabet
                "QQ==\n", " QQ==", "Q Q=",    // whitespace
                "QQ==QQ==",                   // padding in the middle
                "!!!!", "===="}) {
            assertNull(Base64Values.decodeStrict(text), text);
        }
        assertNull(Base64Values.decodeStrict(null));
    }

    @Test
    void maxEncodedLengthMatchesBase64Expansion() {
        assertEquals(0, Base64Values.maxEncodedLength(0));
        assertEquals(4, Base64Values.maxEncodedLength(1));
        assertEquals(4, Base64Values.maxEncodedLength(3));
        assertEquals(8, Base64Values.maxEncodedLength(4));
        assertEquals(349_528, Base64Values.maxEncodedLength(262_144));
    }
}
