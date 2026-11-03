package rs.raf.dlsm.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Candidates for one mutation in doubt (CP1 §28.3). */
class CandidateSetTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static ReferenceModel confirmed() {
        ReferenceModel model = new ReferenceModel();
        model.put("alpha", bytes("A"));
        model.put("empty", new byte[0]);
        return model;
    }

    private static TreeMap<String, byte[]> state(String... keysAndValues) {
        TreeMap<String, byte[]> state = new TreeMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            state.put(keysAndValues[i], bytes(keysAndValues[i + 1]));
        }
        return state;
    }

    @Test
    void exactlyAllowsOnlyTheConfirmedModel() {
        CandidateSet set = CandidateSet.exactly(confirmed());

        assertEquals(1, set.size());
        assertTrue(set.match(state("alpha", "A", "empty", "")).isPresent());
        // An empty value stays present: its absence is another state.
        assertTrue(set.match(state("alpha", "A")).isEmpty());
        assertTrue(set.match(state("alpha", "B", "empty", "")).isEmpty());
    }

    @Test
    void absentOrAppliedAllowsBothCompleteModelsAndNothingElse() {
        CandidateSet set = CandidateSet.exactly(confirmed()).absentOrApplied("beta", bytes("BB"));

        assertEquals(2, set.size());
        assertTrue(set.match(state("alpha", "A", "empty", "")).isPresent());
        assertTrue(set.match(state("alpha", "A", "beta", "BB", "empty", "")).isPresent());
        // A partial value is never a candidate.
        assertTrue(set.match(state("alpha", "A", "beta", "B", "empty", "")).isEmpty());
    }

    @Test
    void appliedKeepsOnlyTheModelWithTheMutation() {
        CandidateSet set = CandidateSet.exactly(confirmed()).applied("alpha", null);

        assertEquals(1, set.size());
        assertTrue(set.match(state("empty", "")).isPresent());
        assertTrue(set.match(state("alpha", "A", "empty", "")).isEmpty());
    }

    @Test
    void theMatchIsTheChosenModel() {
        CandidateSet set = CandidateSet.exactly(confirmed()).absentOrApplied("alpha", bytes("Z"));

        ReferenceModel chosen = set.match(state("alpha", "Z", "empty", "")).orElseThrow();
        assertArrayEquals(bytes("Z"), chosen.get("alpha"));
    }

    @Test
    void aSecondMutationInDoubtNeedsAHistoryChecker() {
        CandidateSet set = CandidateSet.exactly(confirmed()).absentOrApplied("beta", bytes("B"));

        assertThrows(IllegalStateException.class, () -> set.absentOrApplied("gamma", bytes("G")));
    }

    @Test
    void candidatesDoNotShareStateWithTheModel() {
        ReferenceModel model = confirmed();
        CandidateSet set = CandidateSet.exactly(model);
        model.put("alpha", bytes("changed later"));

        assertTrue(set.match(state("alpha", "A", "empty", "")).isPresent());
    }

    @Test
    void digestsTellTheCandidatesApart() {
        CandidateSet set = CandidateSet.exactly(confirmed()).absentOrApplied("beta", bytes("B"));

        assertEquals(2, set.digests().size());
        assertNotEquals(set.digests().get(0), set.digests().get(1));
        assertEquals(CandidateSet.digest(state("alpha", "A", "empty", "")), set.digests().get(0));
        // Key and value boundaries are part of the digest.
        assertNotEquals(CandidateSet.digest(state("ab", "c")), CandidateSet.digest(state("a", "bc")));
    }
}