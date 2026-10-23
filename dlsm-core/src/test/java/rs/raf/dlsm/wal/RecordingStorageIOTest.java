package rs.raf.dlsm.wal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.failpoint.FailpointRegistry.State;
import rs.raf.dlsm.io.InjectedIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The test helper must behave like {@code DefaultStorageIO} on every OS,
 * including Windows where the directory force itself is skipped: the
 * failpoint before it still fires. Other modules' tests rely on this.
 */
class RecordingStorageIOTest {

    private static final OpContext CTX = OpContext.background();
    private static final String FAILPOINT = FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC;

    @TempDir
    Path dir;

    private final FailpointRegistry failpoints = new FailpointRegistry(true);
    private final RecordingStorageIO io = new RecordingStorageIO(failpoints);

    @AfterEach
    void releasePauses() {
        failpoints.clear();
    }

    private void arm(Action action) {
        failpoints.addRule(new FailpointRegistry.Rule("tok", FAILPOINT, action, 0, 1, 0, Map.of()));
    }

    @Test
    void directoryForceWithoutFailpointSucceedsAndIsRecorded() throws IOException {
        io.fsyncDirectory(dir, null, CTX);

        assertEquals(List.of("fsyncDirectory " + dir.getFileName()), io.calls());
    }

    // Regression: on Windows the injected error used to be skipped with the force.
    @Test
    void injectedIoErrorBeforeDirectoryForceFiresOnEveryOs() {
        arm(Action.IO_ERROR);

        InjectedIOException e = assertThrows(InjectedIOException.class,
                () -> io.fsyncDirectory(dir, FAILPOINT, CTX));

        assertEquals(FAILPOINT, e.getfailpointName());
        assertEquals(State.TRIGGERED, failpoints.stateOf("tok"));
    }

    @Test
    void pauseBeforeDirectoryForceWaitsForRelease() throws Exception {
        arm(Action.PAUSE);

        CompletableFuture<Void> force = CompletableFuture.runAsync(() -> {
            try {
                io.fsyncDirectory(dir, FAILPOINT, CTX);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (failpoints.stateOf("tok") != State.TRIGGERED) {
            assertTrue(System.nanoTime() < deadline, "failpoint was not reached");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertFalse(force.isDone());

        assertTrue(failpoints.releaseRule("tok"));
        force.get(10, TimeUnit.SECONDS);
    }

    @Test
    void failpointOfAnotherNameDoesNotFire() throws IOException {
        arm(Action.IO_ERROR);

        assertDoesNotThrow(() -> io.fsyncDirectory(dir,
                FailpointNames.SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, CTX));
        assertEquals(State.ARMED, failpoints.stateOf("tok"));
    }

    @Test
    void pathThatIsNotADirectoryFails() throws IOException {
        Path file = Files.write(dir.resolve("plain.txt"), new byte[]{1});

        assertThrows(IOException.class, () -> io.fsyncDirectory(file, null, CTX));
    }

    @Test
    void failOnStillFailsBeforeTheFailpoint() {
        arm(Action.IO_ERROR);
        io.failOn("fsyncDirectory");

        IOException e = assertThrows(IOException.class, () -> io.fsyncDirectory(dir, FAILPOINT, CTX));

        assertFalse(e instanceof InjectedIOException);
        assertEquals(State.ARMED, failpoints.stateOf("tok"));
    }

    @Test
    void failpointsOutsideTestModeDoNothing() {
        FailpointRegistry disabled = new FailpointRegistry(false);
        disabled.addRule(new FailpointRegistry.Rule("tok", FAILPOINT, Action.IO_ERROR, 0, 1, 0, Map.of()));
        RecordingStorageIO quiet = new RecordingStorageIO(disabled);

        assertDoesNotThrow(() -> quiet.fsyncDirectory(dir, FAILPOINT, CTX));
    }
}
