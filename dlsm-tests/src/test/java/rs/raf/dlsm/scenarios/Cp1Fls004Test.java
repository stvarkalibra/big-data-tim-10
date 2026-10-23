package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class Cp1Fls004Test {

    @Test
    void coveredMixHasTombstoneEmptyValueAndOverwrite() {
        for (long seed : new long[] {1, 12345, -7}) {
            List<Cp1Fls001.Mutation> covered = Cp1Fls004.covered(seed);
            Set<String> earlier = Cp1Fls004.earlier(seed).stream()
                    .map(Cp1Fls001.Mutation::key).collect(Collectors.toSet());

            assertTrue(covered.stream().anyMatch(m -> m.isDelete() && earlier.contains(m.key())),
                    "a tombstone over a published key");
            assertTrue(covered.stream().anyMatch(m -> !m.isDelete() && m.value().length == 0), "an empty value");
            assertTrue(covered.stream().map(Cp1Fls001.Mutation::key).distinct().count() < covered.size(),
                    "an overwrite inside the flushed generation");
        }
    }

    @Test
    void uncoveredMixChangesCoveredKeys() {
        Set<String> covered = Cp1Fls004.covered(1).stream()
                .map(Cp1Fls001.Mutation::key).collect(Collectors.toSet());

        assertTrue(Cp1Fls004.uncovered(1).stream().anyMatch(m -> covered.contains(m.key())));
        assertTrue(Cp1Fls004.uncovered(1).stream().anyMatch(Cp1Fls001.Mutation::isDelete));
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Fls004.ID));
    }
}
