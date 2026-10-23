package rs.raf.dlsm.maintenance;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.maintenance.MaintenanceProgress.Operation;
import rs.raf.dlsm.maintenance.MaintenanceProgress.Phase;
import rs.raf.dlsm.stats.StatusBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceProgressTest {

    @Test
    void startsIdleWithoutError() {
        StatusBuilder.Maintenance idle = new MaintenanceProgress().snapshot();

        assertEquals(new StatusBuilder.Maintenance("NONE", "IDLE", null, List.of(), null), idle);
    }

    @Test
    void reportsRunningJob() {
        MaintenanceProgress progress = new MaintenanceProgress();

        progress.begin(Operation.COMPACTION, null, List.of(1L, 2L));
        progress.phase(Phase.WRITE_OUTPUT);

        assertEquals(new StatusBuilder.Maintenance("COMPACTION", "WRITE_OUTPUT", null, List.of(1L, 2L), null),
                progress.snapshot());
    }

    @Test
    void lastErrorUsesCategoryOrCodeAndOutlivesJobs() {
        MaintenanceProgress progress = new MaintenanceProgress();

        progress.failed(new StorageException(ErrorCode.STORAGE_IO, null, "disk gone"));
        assertEquals(new StatusBuilder.FatalError("STORAGE_IO", "disk gone"), progress.snapshot().lastError());

        progress.begin(Operation.FLUSH, 7L, List.of());
        progress.idle();
        assertEquals(new StatusBuilder.FatalError("STORAGE_IO", "disk gone"), progress.snapshot().lastError());

        progress.failed(new StorageException(ErrorCode.STORAGE_IO, "DATA_BLOCK", "bad block"));
        assertEquals(new StatusBuilder.FatalError("DATA_BLOCK", "bad block"), progress.snapshot().lastError());
        assertEquals("IDLE", progress.snapshot().phase());
    }

    @Test
    void phaseWithoutJobIsRejected() {
        assertThrows(IllegalStateException.class, () -> new MaintenanceProgress().phase(Phase.CLEANUP));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceProgress().begin(Operation.NONE, null, List.of()));
    }
}
