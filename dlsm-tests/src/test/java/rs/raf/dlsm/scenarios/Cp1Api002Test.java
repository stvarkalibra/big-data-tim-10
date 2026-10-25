package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import static org.junit.jupiter.api.Assertions.*;

/** The inputs of CP1-API-002; the scenario itself runs against a real node. */
class Cp1Api002Test {

    @Test
    void valueIsNeverEmptySoATombstoneCannotPassForIt() {
        for (long seed : new long[] {1, 12345, -7}) {
            byte[] a = Cp1Api002.value(seed);
            assertTrue(a.length > 0);
            assertArrayEquals(a, Cp1Api002.value(seed), "same seed, same bytes");
        }
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Api002.ID));
    }
}
