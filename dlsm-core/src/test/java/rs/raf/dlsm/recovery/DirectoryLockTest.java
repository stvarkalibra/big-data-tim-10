package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryLockTest {

    @TempDir
    Path root;

    private Path lockFile() {
        return new DataLayout(root).lockFile();
    }

    @Test
    void createsLockFileAndHoldsTheLock() throws Exception {
        try (DirectoryLock lock = DirectoryLock.acquire(lockFile())) {
            assertTrue(lock.isHeld());
            assertTrue(Files.isRegularFile(lockFile()));
            assertEquals(0, Files.size(lockFile()));
        }
    }

    // A LOCK file left behind by a crash proves nothing: only the OS lock counts.
    @Test
    void leftoverLockFileDoesNotBlock() throws Exception {
        Files.write(lockFile(), new byte[0]);

        try (DirectoryLock lock = DirectoryLock.acquire(lockFile())) {
            assertTrue(lock.isHeld());
        }
    }

    @Test
    void secondOwnerInSameProcessIsRefused() throws Exception {
        try (DirectoryLock ignored = DirectoryLock.acquire(lockFile())) {
            assertLocked(() -> DirectoryLock.acquire(lockFile()));
        }
    }

    @Test
    void lockCanBeTakenAgainAfterRelease() throws Exception {
        DirectoryLock first = DirectoryLock.acquire(lockFile());
        first.close();
        assertFalse(first.isHeld());

        try (DirectoryLock second = DirectoryLock.acquire(lockFile())) {
            assertTrue(second.isHeld());
        }
        // The file stays: it is never deleted on shutdown.
        assertTrue(Files.exists(lockFile()));
    }

    @Test
    void unopenableLockFileIsReported() throws IOException {
        Path directoryInTheWay = Files.createDirectory(lockFile());

        assertLocked(() -> DirectoryLock.acquire(directoryInTheWay));
    }

    // CP1 §14.1: another process owning the directory stops startup; the OS
    // frees the lock when that process is killed, without any cleanup.
    @Test
    void anotherProcessOwnsTheDirectoryUntilItIsKilled() throws Exception {
        Process holder = startLockHolder();
        try {
            assertEquals("LOCKED", firstLine(holder));

            assertLocked(() -> DirectoryLock.acquire(lockFile()));
        } finally {
            holder.destroyForcibly();
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));
        }

        try (DirectoryLock lock = acquireOnceReleased()) {
            assertTrue(lock.isHeld());
        }
    }

    /**
     * Windows may close a killed process's handles shortly after the process
     * is reported dead, so the lock is retried until a bounded deadline. The
     * proof is the successful acquire, not the waiting.
     */
    private DirectoryLock acquireOnceReleased() throws StorageException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                return DirectoryLock.acquire(lockFile());
            } catch (StorageException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        }
    }

    private Process startLockHolder() throws IOException, URISyntaxException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = codeSource(LockHolder.class) + File.pathSeparator
                + codeSource(DirectoryLock.class);

        return new ProcessBuilder(java, "-cp", classpath,
                LockHolder.class.getName(), lockFile().toString())
                .redirectErrorStream(true)
                .start();
    }

    private static String codeSource(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String firstLine(Process process) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).get(30, TimeUnit.SECONDS);
    }

    private static void assertLocked(Executable call) {
        StorageException e = assertThrows(StorageException.class, call);
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals(DirectoryLock.CATEGORY, e.category());
    }
}
