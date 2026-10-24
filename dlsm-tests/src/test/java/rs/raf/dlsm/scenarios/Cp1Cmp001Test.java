package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Cp1Cmp001Test {

    @Test
    void tablesFollowTheSpecificationLayout() {
        List<List<Cp1Fls001.Mutation>> tables = Cp1Cmp001.tables(1);

        assertEquals(List.of("alpha", "beta", "delta"), tables.get(0).stream().map(Cp1Fls001.Mutation::key).toList());
        assertEquals(List.of("alpha", "gamma"), tables.get(1).stream().map(Cp1Fls001.Mutation::key).toList());
        assertEquals(List.of("beta", "delta", "epsilon"), tables.get(2).stream().map(Cp1Fls001.Mutation::key).toList());
        assertTrue(tables.stream().flatMap(List::stream).noneMatch(Cp1Fls001.Mutation::isDelete));
    }

    @Test
    void winnersAreTheLatestSequencePerKey() {
        Map<String, Long> winners = Cp1Cmp001.winningSequences(Cp1Cmp001.tables(7));

        assertEquals(Map.of("alpha", 4L, "beta", 6L, "delta", 7L, "gamma", 5L, "epsilon", 8L), winners);
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Cmp001.ID));
    }
}
