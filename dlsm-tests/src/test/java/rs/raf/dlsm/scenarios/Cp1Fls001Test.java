package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Cp1Fls001Test {

    @Test
    void mixHasPutOverwriteEmptyValueAndDelete() {
        for (long seed : new long[] {1, 12345, -7}) {
            List<Cp1Fls001.Mutation> mix = Cp1Fls001.mutations(seed);

            assertTrue(mix.stream().anyMatch(Cp1Fls001.Mutation::isDelete), "a DELETE");
            assertTrue(mix.stream().anyMatch(m -> !m.isDelete() && m.value().length == 0), "an empty value");
            long distinct = mix.stream().map(Cp1Fls001.Mutation::key).distinct().count();
            assertTrue(distinct < mix.size(), "an overwrite of the same key");
        }
    }

    @Test
    void sameSeedSameMix() {
        List<Cp1Fls001.Mutation> a = Cp1Fls001.mutations(42);
        List<Cp1Fls001.Mutation> b = Cp1Fls001.mutations(42);

        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).key(), b.get(i).key());
            assertArrayEquals(a.get(i).value(), b.get(i).value());
        }
    }

    @Test
    void tableFileNameHasTwentyDigits() {
        assertEquals("sst-00000000000000000003.sst", Cp1Fls001.tableFile(3));
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Fls001.ID));
    }
}
