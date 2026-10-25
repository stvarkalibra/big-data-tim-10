package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import static org.junit.jupiter.api.Assertions.*;

class Cp1Cmp004Test {

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Cmp004.ID));
    }
}
