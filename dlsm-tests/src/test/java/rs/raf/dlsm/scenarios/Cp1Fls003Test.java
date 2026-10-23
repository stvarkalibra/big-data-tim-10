package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class Cp1Fls003Test {

    @Test
    void unflushedMixOverwritesPublishedKeysAndHasEmptyValueAndDeletes() {
        for (long seed : new long[] {1, 12345, -7}) {
            Set<String> published = Cp1Fls003.published(seed).stream()
                    .map(Cp1Fls001.Mutation::key).collect(Collectors.toSet());
            List<Cp1Fls001.Mutation> later = Cp1Fls003.unflushed(seed);

            assertTrue(later.stream().anyMatch(m -> !m.isDelete() && published.contains(m.key())),
                    "an overwrite of a published key");
            assertTrue(later.stream().anyMatch(m -> m.isDelete() && published.contains(m.key())),
                    "a tombstone over a published key");
            assertTrue(later.stream().anyMatch(m -> !m.isDelete() && m.value().length == 0), "an empty value");
            assertTrue(Cp1Fls003.published(seed).stream().noneMatch(Cp1Fls001.Mutation::isDelete));
        }
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Fls003.ID));
    }
}
