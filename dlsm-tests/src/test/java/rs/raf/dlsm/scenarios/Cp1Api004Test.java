package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import static org.junit.jupiter.api.Assertions.*;

/** The inputs of CP1-API-004; the scenario itself runs against a real node. */
class Cp1Api004Test {

    @Test
    void valuesHaveDifferentLengthsSoTheirFramesCanBeTold() {
        for (long seed : new long[] {1, 12345, -7}) {
            byte[][] values = Cp1Api004.values(seed);
            assertNotEquals(values[0].length, values[1].length);
            assertArrayEquals(values[1], Cp1Api004.values(seed)[1], "same seed, same bytes");
        }
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Api004.ID));
    }
}
