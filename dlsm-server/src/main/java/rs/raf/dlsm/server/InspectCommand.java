
package rs.raf.dlsm.server;

import rs.raf.dlsm.inspect.InspectSSTableCommand;
import rs.raf.dlsm.inspect.InspectWal;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class InspectCommand {

    public static final Path DEFAULT_DATA_DIR =
            Path.of("/var/lib/dlsm");

    public static final int EXIT_USAGE = 64;

    private static final String USAGE =
            "usage: inspect wal [--data-dir PATH]\n"
                    + "       inspect sstable <tableId> [--data-dir PATH]";

    private InspectCommand() {
    }

    public static int run(
            List<String> args,
            PrintStream out,
            PrintStream err) {

        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        if (args.isEmpty()) {
            return usage(err, "Missing inspect target");
        }

        String target = args.get(0);

        return switch (target) {

            case "wal" -> {
                Path dataDir = parseDataDir(
                        args.subList(1, args.size()),
                        err
                );

                yield dataDir == null
                        ? EXIT_USAGE
                        : InspectWal.run(dataDir, out);
            }

            case "sstable" -> inspectSSTable(
                    args.subList(1, args.size()),
                    out,
                    err
            );

            default -> usage(
                    err,
                    "Unknown inspect target: " + target
            );
        };
    }

    private static int inspectSSTable(
            List<String> args,
            PrintStream out,
            PrintStream err) {

        if (args.isEmpty()) {
            return usage(err, "Missing SSTable ID");
        }

        long tableId;

        try {
            String id = args.get(0);

            if (!id.matches("[0-9]+")) {
                return usage(err, "Invalid SSTable ID");
            }

            tableId = Long.parseLong(id);

            if (tableId <= 0) {
                return usage(err, "Invalid SSTable ID");
            }

        } catch (NumberFormatException e) {
            return usage(err, "Invalid SSTable ID");
        }

        Path dataDir = parseDataDir(
                args.subList(1, args.size()),
                err
        );

        if (dataDir == null) {
            return EXIT_USAGE;
        }

        return InspectSSTableCommand.run(
                dataDir,
                tableId,
                out
        );
    }

    private static Path parseDataDir(
            List<String> args,
            PrintStream err) {

        if (args.isEmpty()) {
            return DEFAULT_DATA_DIR;
        }

        if (args.size() != 2
                || !"--data-dir".equals(args.get(0))
                || args.get(1).isBlank()) {

            usage(err, "Invalid inspect arguments");
            return null;
        }

        try {
            return Path.of(args.get(1));
        } catch (InvalidPathException e) {
            usage(err, "Invalid data directory");
            return null;
        }
    }

    private static int usage(
            PrintStream err,
            String problem) {

        err.println(problem);
        err.println(USAGE);

        return EXIT_USAGE;
    }
}
