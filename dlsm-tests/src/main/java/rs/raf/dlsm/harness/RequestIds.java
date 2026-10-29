package rs.raf.dlsm.harness;

public final class RequestIds {
    private RequestIds() {}

    public static String of(String scenarioId, long seed, int index) {
        return scenarioId + "-" + seed + "-" + index;
    }
}