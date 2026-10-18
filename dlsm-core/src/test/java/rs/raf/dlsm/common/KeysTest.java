package rs.raf.dlsm.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class KeysTest {

    @Test
    void acceptsEveryAllowedCharacter() {
        assertTrue(Keys.isValid(
                "ABCXYZabcxyz0189._~-"));
        assertTrue(Keys.isValid("a"));
        assertTrue(Keys.isValid("-"));
    }

    @Test
    void acceptsMaximumLength() {
        assertTrue(Keys.isValid("k".repeat(Keys.MAX_LENGTH)));
    }

    @Test
    void rejectsNullEmptyAndTooLong() {
        assertFalse(Keys.isValid(null));
        assertFalse(Keys.isValid(""));
        assertFalse(Keys.isValid("k".repeat(Keys.MAX_LENGTH + 1)));
    }

    @Test
    void rejectsForbiddenCharacters() {
        for (String key : new String[] {
                "a b", "a/b", "a+b", "a=b", "a%20", "a:b",
                "a\u0000", "a\n", "č", "aé", "😀"}) {
            assertFalse(Keys.isValid(key), key);
        }
    }

    @Test
    void requireValidReturnsKeyOrThrows() {
        assertEquals("user.1", Keys.requireValid("user.1"));
        assertThrows(IllegalArgumentException.class,
                () -> Keys.requireValid("bad key"));
        assertThrows(IllegalArgumentException.class,
                () -> Keys.requireValid(null));
    }

    @Test
    void compareMatchesUnsignedByteOrder() {
        String[] keys = {
                "-", ".", "0", "9", "A", "Z", "_", "a", "z", "~",
                "a-", "a0", "aa", "ab", "b"};

        for (String a : keys) {
            for (String b : keys) {
                assertEquals(
                        Integer.signum(unsignedByteCompare(a, b)),
                        Integer.signum(Keys.compare(a, b)),
                        a + " vs " + b);
            }
        }
    }

    @Test
    void prefixSortsBeforeLongerKey() {
        assertTrue(Keys.compare("user", "user.1") < 0);
        assertTrue(Keys.compare("user.1", "user") > 0);
        assertEquals(0, Keys.compare("user", "user"));
    }

    private static int unsignedByteCompare(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.US_ASCII);
        byte[] y = b.getBytes(StandardCharsets.US_ASCII);

        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int diff = Byte.toUnsignedInt(x[i]) - Byte.toUnsignedInt(y[i]);
            if (diff != 0) {
                return diff;
            }
        }

        return x.length - y.length;
    }
}
