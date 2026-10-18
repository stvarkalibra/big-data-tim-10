package rs.raf.dlsm.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class InternalRecordTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void kindCodesMatchOnDiskFormat() {
        assertEquals(1, Kind.PUT.code());
        assertEquals(2, Kind.DELETE.code());
        assertEquals(Kind.PUT, Kind.fromCode((byte) 1));
        assertEquals(Kind.DELETE, Kind.fromCode((byte) 2));
    }

    @Test
    void unknownKindCodeIsRejected() {
        for (byte code : new byte[] {0, 3, -1, Byte.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Kind.fromCode(code));
        }
    }

    @Test
    void putKeepsValue() {
        InternalRecord r = InternalRecord.put("user.1", 7, bytes("hello"));

        assertEquals("user.1", r.key());
        assertEquals(7, r.sequence());
        assertEquals(Kind.PUT, r.kind());
        assertFalse(r.isTombstone());
        assertArrayEquals(bytes("hello"), r.value());
        assertEquals(5, r.valueLength());
    }

    @Test
    void emptyPutIsPresentNotTombstone() {
        InternalRecord r = InternalRecord.put("k", 1, new byte[0]);

        assertFalse(r.isTombstone());
        assertNotNull(r.value());
        assertEquals(0, r.value().length);
    }

    @Test
    void deleteHasNoValue() {
        InternalRecord r = InternalRecord.delete("k", 3);

        assertTrue(r.isTombstone());
        assertEquals(Kind.DELETE, r.kind());
        assertNull(r.value());
        assertEquals(0, r.valueLength());
    }

    @Test
    void rejectsInvalidKey() {
        assertThrows(IllegalArgumentException.class,
                () -> InternalRecord.put("bad key", 1, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> InternalRecord.put(null, 1, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> InternalRecord.delete("", 1));
    }

    @Test
    void rejectsNonPositiveSequence() {
        assertThrows(IllegalArgumentException.class,
                () -> InternalRecord.put("k", 0, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> InternalRecord.delete("k", -1));
    }

    @Test
    void acceptsMaximumSequence() {
        assertEquals(Long.MAX_VALUE,
                InternalRecord.delete("k", Long.MAX_VALUE).sequence());
    }

    @Test
    void rejectsKindValueMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new InternalRecord("k", 1, Kind.PUT, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InternalRecord("k", 1, Kind.DELETE, new byte[0]));
        assertThrows(NullPointerException.class,
                () -> new InternalRecord("k", 1, null, new byte[0]));
    }

    @Test
    void valueSizeLimit() {
        byte[] max = new byte[InternalRecord.MAX_VALUE_BYTES];
        assertEquals(InternalRecord.MAX_VALUE_BYTES,
                InternalRecord.put("k", 1, max).valueLength());

        assertThrows(IllegalArgumentException.class,
                () -> InternalRecord.put("k", 1,
                        new byte[InternalRecord.MAX_VALUE_BYTES + 1]));
    }

    @Test
    void valueIsDefensivelyCopied() {
        byte[] input = bytes("abc");
        InternalRecord r = InternalRecord.put("k", 1, input);

        input[0] = 'X';
        assertArrayEquals(bytes("abc"), r.value());

        r.value()[1] = 'Y';
        assertArrayEquals(bytes("abc"), r.value());
    }

    @Test
    void equalityComparesValueContents() {
        InternalRecord a = InternalRecord.put("k", 1, bytes("v"));
        InternalRecord b = InternalRecord.put("k", 1, bytes("v"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, InternalRecord.put("k", 1, bytes("w")));
        assertNotEquals(a, InternalRecord.put("k", 2, bytes("v")));
        assertNotEquals(InternalRecord.put("k", 1, new byte[0]),
                InternalRecord.delete("k", 1));
        assertEquals(InternalRecord.delete("k", 4),
                InternalRecord.delete("k", 4));
    }

    @Test
    void toStringDoesNotLeakValue() {
        String text = InternalRecord.put("k", 1, bytes("secret")).toString();

        assertFalse(text.contains("secret"));
        assertTrue(text.contains("valueLength=6"));
    }
}
