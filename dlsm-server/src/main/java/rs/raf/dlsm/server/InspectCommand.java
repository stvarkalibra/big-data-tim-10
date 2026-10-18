package rs.raf.dlsm.server;

import rs.raf.dlsm.inspect.InspectWal;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code inspect} subcommand behind {@code dbctl inspect} (CP1 §25):
 *
 * <pre>
 * inspect wal [--data-dir PATH]
 * </pre>
 *
 * <p>Offline and read-only: it runs over a stopped volume, starts no
 * server, takes no {@code LOCK}, creates nothing and writes no log lines.
 * Standard output carries only the inspector's JSON report, so
 * {@code dbctl} can parse it; usage errors go to standard error.
 *
 * <p>Exit codes are those of the inspector (see {@code FORMAT.md}), plus
 * {@link #EXIT_USAGE} for a malformed command line. {@code inspect manifest}
 * and {@code inspect sstable <id>} are added here by their owners.
 */
public final class InspectCommand {

    /** The persistent volume (PI §23.1). */
    public static final Path DEFAULT_DATA_DIR = Path.of("/var/lib/dlsm");

    /** Malformed command line; distinct from every inspector exit code. */
    public static final int EXIT_USAGE = 64;

    private static final String USAGE = "usage: inspect wal [--data-dir PATH]";

    private InspectCommand() {
    }

    /**
     * @param args the arguments after {@code inspect}
     * @return the process exit code
     */
    public static int run(List<String> args, PrintStream out, PrintStream err) {
        if (args.isEmpty()) {
            return usage(err, "Missing inspect target");
        }

        String target = args.get(0);
        List<String> options = args.subList(1, args.size());

        return switch (target) {
            case "wal" -> {
                Path dataDir = dataDir(options, err);
                yield dataDir == null ? EXIT_USAGE : InspectWal.run(dataDir, out);
            }
            default -> usage(err, "Unknown inspect target: " + target);
        };
    }

    /** {@code [--data-dir PATH]}; null after reporting a usage error. */
    private static Path dataDir(List<String> options, PrintStream err) {
        if (options.isEmpty()) {
            return DEFAULT_DATA_DIR;
        }
        if (options.size() != 2 || !options.get(0).equals("--data-dir")) {
            usage(err, "Unexpected arguments: " + String.join(" ", options));
            return null;
        }
        try {
            return Path.of(options.get(1));
        } catch (InvalidPathException e) {
            usage(err, "Invalid data directory");
            return null;
        }
    }

    private static int usage(PrintStream err, String problem) {
        err.println(problem);
        err.println(USAGE);
        return EXIT_USAGE;
    }
}
