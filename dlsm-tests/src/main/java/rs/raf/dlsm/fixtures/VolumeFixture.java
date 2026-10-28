package rs.raf.dlsm.fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * An initialized, empty standalone volume written from {@code FORMAT.md}
 * §2, §4.1 and §5.1 alone, with no production code (plan §6.5, §14): the
 * four directories, {@code node.json} and the initial manifest of
 * generation 0. A test then adds WAL segments with {@link WalFixtureWriter}
 * in {@link #walDir} (CP1-WAL-007).
 */
public final class VolumeFixture {

    /** The default {@code NODE_ID} of the standalone node (CP1 §14). */
    public static final String NODE_ID = "standalone";

    /** FORMAT.md §4.1, exactly as the engine writes it. */
    static final String NODE_JSON = "{\"formatVersion\":1,\"nodeId\":\"" + NODE_ID + "\"}";
    /** FORMAT.md §5.1: the manifest of a new database. */
    static final String INITIAL_MANIFEST =
            "{\"formatVersion\":1,\"generation\":0,\"nextTableId\":1,"
                    + "\"flushedThroughSequence\":0,\"sstables\":[]}";

    private VolumeFixture() {
    }

    /**
     * Creates {@code lsm/}, {@code lsm/wal/}, {@code lsm/sst/} and
     * {@code lsm/tmp/} under {@code root}, then writes and forces
     * {@code node.json} and {@code lsm/MANIFEST.json}. An existing file is
     * never replaced.
     */
    public static void initialize(Path root) throws IOException {
        for (String dir : new String[] {"lsm/wal", "lsm/sst", "lsm/tmp"}) {
            Files.createDirectories(root.resolve(dir));
        }
        write(root.resolve("node.json"), NODE_JSON);
        write(root.resolve("lsm").resolve("MANIFEST.json"), INITIAL_MANIFEST);
    }

    /** {@code lsm/wal/} of the volume at {@code root}. */
    public static Path walDir(Path root) {
        return root.resolve("lsm").resolve("wal");
    }

    private static void write(Path file, String content) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }
}
