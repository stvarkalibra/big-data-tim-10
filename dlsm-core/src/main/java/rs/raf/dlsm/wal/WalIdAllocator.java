package rs.raf.dlsm.wal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * Hands out WAL segment IDs, separate from SSTable IDs (CP1 §16.1).
 *
 * <p>At startup, before any cleanup, the first ID is
 * {@code max(1, 1 + greatest syntactically valid WAL ID in wal/ or tmp/)}.
 * Leftover temporary segments therefore keep their ID reserved, so a crash
 * may leave gaps but an ID is never used twice.
 */
public final class WalIdAllocator {

    private long next;
    private boolean exhausted;

    private WalIdAllocator(long next) {
        if (next < 1) {
            throw new IllegalArgumentException("First segment ID must be positive: " + next);
        }
        this.next = next;
    }

    /** Starts above every WAL ID named in {@code walDir} and {@code tmpDir}. */
    public static WalIdAllocator fromDisk(Path walDir, Path tmpDir) throws IOException {
        long greatest = Math.max(greatestId(walDir), greatestId(tmpDir));

        if (greatest == Long.MAX_VALUE) {
            throw new IllegalStateException("WAL segment IDs are exhausted");
        }
        return new WalIdAllocator(Math.max(1, greatest + 1));
    }

    /** Starts at {@code firstId}, e.g. {@code 1} for a brand-new database (CP1 §14.2). */
    public static WalIdAllocator startingAt(long firstId) {
        return new WalIdAllocator(firstId);
    }

    /** Returns the current ID and advances past it. */
    public synchronized long next() {
        if (exhausted) {
            throw new IllegalStateException("WAL segment IDs are exhausted");
        }

        long id = next;
        if (id == Long.MAX_VALUE) {
            exhausted = true;
        } else {
            next = id + 1;
        }
        return id;
    }

    /** The ID {@link #next()} would return, without reserving it. */
    public synchronized long peek() {
        if (exhausted) {
            throw new IllegalStateException("WAL segment IDs are exhausted");
        }
        return next;
    }

    /**
     * Greatest ID among final ({@code wal-<id>.log}) and temporary
     * ({@code wal-<id>.log.tmp}) segment names in {@code dir}, or {@code 0}.
     * Both forms are accepted in both directories: a misplaced name must
     * still never be reused.
     */
    private static long greatestId(Path dir) throws IOException {
        long greatest = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                OptionalLong id = WalFileNames.parseSegmentId(name);
                if (id.isEmpty()) {
                    id = WalFileNames.parseTempId(name);
                }
                if (id.isPresent()) {
                    greatest = Math.max(greatest, id.getAsLong());
                }
            }
        }

        return greatest;
    }
}
