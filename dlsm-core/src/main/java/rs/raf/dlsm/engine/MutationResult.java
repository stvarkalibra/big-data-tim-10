package rs.raf.dlsm.engine;

/**
 * Successful PUT or DELETE: the mutation is durable and visible.
 *
 * @param sequence the mutation sequence assigned to it
 */
public record MutationResult(long sequence) {

    public MutationResult {
        if (sequence < 1) {
            throw new IllegalArgumentException("Sequence must be positive");
        }
    }
}
