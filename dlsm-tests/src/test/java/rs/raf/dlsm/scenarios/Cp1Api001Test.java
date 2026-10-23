package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** The inputs of CP1-API-001; the scenario itself runs against a real node. */
class Cp1Api001Test {

    @Test
    void valuesAreBinaryDistinctAndSeeded() {
        for (long seed : new long[] {1, 12345, -7}) {
            byte[][] values = Cp1Api001.values(seed);
            byte[] a = values[0];
            byte[] b = values[1];

            assertFalse(Arrays.equals(a, b), "A and B must differ");
            assertNotEquals(a.length, b.length);
            assertFalse(isUtf8(a), "A must not survive a string round trip");
            assertFalse(isUtf8(b), "B must not survive a string round trip");
            assertArrayEquals(a, Cp1Api001.values(seed)[0], "same seed, same bytes");
        }
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Api001.ID));
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
