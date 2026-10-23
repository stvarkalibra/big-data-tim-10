package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** The inputs of CP1-API-005; the scenario itself runs against a real node. */
class Cp1Api005Test {

    @Test
    void valuesArePairwiseDifferent() {
        for (long seed : new long[] {1, 12345, -7}) {
            byte[][] values = Cp1Api005.values(seed);
            assertFalse(Arrays.equals(values[0], values[1]));
            assertFalse(Arrays.equals(values[0], values[2]));
            assertFalse(Arrays.equals(values[1], values[2]));
            assertArrayEquals(values[2], Cp1Api005.values(seed)[2], "same seed, same bytes");
        }
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Api005.ID));
    }
}
