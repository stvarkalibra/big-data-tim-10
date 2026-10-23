package rs.raf.dlsm.maintenance;

import rs.raf.dlsm.stats.StatusBuilder;

import java.util.List;
import java.util.Objects;

public final class MaintenanceProgress {

    public enum Operation { NONE, FLUSH, COMPACTION }

    public enum Phase {
        IDLE, CAPTURE, WRITE_OUTPUT, SYNC_OUTPUT, FINALIZE_OUTPUT, PUBLISH_MANIFEST, RETIRE_INPUTS, CLEANUP
    }

    private static final StatusBuilder.Maintenance IDLE =
            new StatusBuilder.Maintenance(Operation.NONE.name(), Phase.IDLE.name(), null, List.of());

    private volatile StatusBuilder.Maintenance current = IDLE;

    public synchronized void begin(Operation operation, Long targetSequence, List<Long> inputTableIds) {
        Objects.requireNonNull(operation, "operation");
        if (operation == Operation.NONE) {
            throw new IllegalArgumentException("Use idle() to end a job");
        }
        current = new StatusBuilder.Maintenance(operation.name(), Phase.CAPTURE.name(),
                targetSequence, inputTableIds);
    }

    public synchronized void phase(Phase phase) {
        Objects.requireNonNull(phase, "phase");
        StatusBuilder.Maintenance job = current;
        if (job.operation().equals(Operation.NONE.name())) {
            throw new IllegalStateException("No maintenance job is running");
        }
        current = new StatusBuilder.Maintenance(job.operation(), phase.name(),
                job.targetSequence(), job.inputTableIds());
    }

    public synchronized void inputs(List<Long> inputTableIds) {
        StatusBuilder.Maintenance job = current;
        if (job.operation().equals(Operation.NONE.name())) {
            throw new IllegalStateException("No maintenance job is running");
        }
        current = new StatusBuilder.Maintenance(job.operation(), job.phase(),
                job.targetSequence(), inputTableIds);
    }

    public synchronized void idle() {
        current = IDLE;
    }

    public StatusBuilder.Maintenance snapshot() {
        return current;
    }
}
