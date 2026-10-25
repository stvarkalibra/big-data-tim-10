package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import static org.junit.jupiter.api.Assertions.*;

/** The inputs of CP1-FLS-007; the scenario itself runs against a real node. */
class Cp1Fls007Test {

    @Test
    void everyValueAloneFillsAGeneration() {
        for (byte[] value : Cp1Fls007.values(12345)) {
            // Logical size of a record: 32 + key + value bytes (CP1 §10).
            assertTrue(32 + "waiting".length() + value.length >= Cp1Fls007.MEMTABLE_MAX_BYTES);
        }
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Fls007.ID));
    }
}
