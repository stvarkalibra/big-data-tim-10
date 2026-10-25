package rs.raf.dlsm.scenarios;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.harness.ScenarioRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Cp1Cmp002Test {

    @Test
    void tablesFollowTheSpecificationLayout() {
        List<List<Cp1Fls001.Mutation>> tables = Cp1Cmp002.tables(1);

        assertEquals(List.of("alpha", "beta"), tables.get(0).stream().map(Cp1Fls001.Mutation::key).toList());
        assertEquals(List.of("alpha", "ghost", "beta"), tables.get(1).stream().map(Cp1Fls001.Mutation::key).toList());
        assertTrue(tables.get(1).get(0).isDelete());
        assertTrue(tables.get(1).get(1).isDelete());
        assertFalse(tables.get(1).get(2).isDelete());
        assertTrue(tables.get(0).stream().noneMatch(Cp1Fls001.Mutation::isDelete));
    }

    @Test
    void isRegistered() {
        assertTrue(ScenarioRegistry.isImplemented(Cp1Cmp002.ID));
    }
}
