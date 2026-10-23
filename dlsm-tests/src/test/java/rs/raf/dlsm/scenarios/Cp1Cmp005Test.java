package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** The inputs of CP1-CMP-005; the scenario itself runs against a real node. */
class Cp1Cmp005Test {

    @Test
    void valuesArePairwiseDifferentSoEveryVersionCanBeTold() {
        byte[][] values = Cp1Cmp005.values(12345);
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertFalse(Arrays.equals(values[i], values[j]), i + " vs " + j);
            }
        }
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Cmp005.ID));
    }
}
