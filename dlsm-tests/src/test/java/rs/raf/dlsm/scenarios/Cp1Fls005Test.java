package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class Cp1Fls005Test {

    @Test
    void unflushedMixChangesPublishedKeys() {
        for (long seed : new long[] {1, 12345, -7}) {
            Set<String> earlier = Cp1Fls005.earlier(seed).stream()
                    .map(Cp1Fls001.Mutation::key).collect(Collectors.toSet());
            List<Cp1Fls001.Mutation> later = Cp1Fls005.unflushed(seed);

            assertTrue(later.stream().anyMatch(m -> m.isDelete() && earlier.contains(m.key())));
            assertTrue(later.stream().anyMatch(m -> !m.isDelete() && earlier.contains(m.key())));
            assertTrue(later.stream().anyMatch(m -> !m.isDelete() && m.value().length == 0));
        }
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Fls005.ID));
    }
}
