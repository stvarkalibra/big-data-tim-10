package rs.raf.dlsm.recovery;

import java.nio.file.Path;

/**
 * Child process for {@link DirectoryLockTest}: takes the directory lock,
 * prints {@code LOCKED}, then holds it until it is killed.
 */
public final class LockHolder {

    private LockHolder() {
    }

    public static void main(String[] args) throws Exception {
        DirectoryLock lock = DirectoryLock.acquire(Path.of(args[0]));
        System.out.println(lock.isHeld() ? "LOCKED" : "NOT_HELD");
        System.out.flush();

        // Hold the lock until the parent kills this process.
        while (System.in.read() != -1) {
            // ignore input
        }
    }
}
