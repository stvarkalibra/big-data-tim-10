package rs.raf.dlsm.manifest;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalLong;

public final class TableIdAllocator {

    public static final String PREFIX = "sst-";
    public static final String TABLE_SUFFIX = ".sst";
    public static final String TEMP_SUFFIX = ".sst.tmp";
    public static final int ID_DIGITS = 20;

    private long next;
    private boolean exhausted;

    private TableIdAllocator(long next) {
        if (next < 1) {
            throw new IllegalArgumentException("First table ID must be positive: " + next);
        }
        this.next = next;
    }

    public static TableIdAllocator fromDisk(Manifest manifest, Path sstDir, Path tmpDir) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        long greatest = Math.max(greatestId(sstDir), greatestId(tmpDir));
        if (greatest == Long.MAX_VALUE) {
            throw new IllegalStateException("SSTable IDs are exhausted");
        }
        return new TableIdAllocator(Math.max(manifest.nextTableId(), greatest + 1));
    }

    public static TableIdAllocator startingAt(long firstId) {
        return new TableIdAllocator(firstId);
    }

    public synchronized long next() {
        if (exhausted) {
            throw new IllegalStateException("SSTable IDs are exhausted");
        }
        long id = next;
        if (id == Long.MAX_VALUE) {
            exhausted = true;
        } else {
            next = id + 1;
        }
        return id;
    }

    public synchronized long peek() {
        if (exhausted) {
            throw new IllegalStateException("SSTable IDs are exhausted");
        }
        return next;
    }

    public static String tempFileName(long tableId) {
        return PREFIX + formatId(tableId) + TEMP_SUFFIX;
    }

    public static OptionalLong parseTableId(String fileName) {
        return parseId(fileName, TABLE_SUFFIX);
    }

    public static OptionalLong parseTempId(String fileName) {
        return parseId(fileName, TEMP_SUFFIX);
    }

    private static long greatestId(Path dir) throws IOException {
        long greatest = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                OptionalLong id = parseTableId(name);
                if (id.isEmpty()) {
                    id = parseTempId(name);
                }
                if (id.isPresent()) {
                    greatest = Math.max(greatest, id.getAsLong());
                }
            }
        }
        return greatest;
    }

    private static String formatId(long tableId) {
        if (tableId <= 0) {
            throw new IllegalArgumentException("Table ID must be positive: " + tableId);
        }
        return "%020d".formatted(tableId);
    }

    private static OptionalLong parseId(String fileName, String suffix) {
        if (fileName == null
                || fileName.length() != PREFIX.length() + ID_DIGITS + suffix.length()
                || !fileName.startsWith(PREFIX)
                || !fileName.endsWith(suffix)) {
            return OptionalLong.empty();
        }
        String digits = fileName.substring(PREFIX.length(), PREFIX.length() + ID_DIGITS);
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return OptionalLong.empty();
            }
        }
        try {
            return OptionalLong.of(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
