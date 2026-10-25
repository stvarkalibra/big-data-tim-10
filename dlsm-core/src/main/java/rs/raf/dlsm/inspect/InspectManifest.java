package rs.raf.dlsm.inspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.manifest.Manifest;
import rs.raf.dlsm.manifest.TableIdAllocator;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class InspectManifest {

    static final String MANIFEST_FILE = "lsm/MANIFEST.json";

    public static final int EXIT_OK = 0;
    public static final int EXIT_CORRUPT = 1;
    public static final int EXIT_MISSING = 2;
    public static final int EXIT_IO_ERROR = 3;

    public static final String REFERENCED_FILE_MISSING = "REFERENCED_FILE_MISSING";
    public static final String MANIFEST_TABLE_MISMATCH = "MANIFEST_TABLE_MISMATCH";

    private static final String TEMP_PREFIX = "MANIFEST-";
    private static final String TEMP_SUFFIX = ".tmp";

    private static final ObjectWriter JSON = new ObjectMapper().writerWithDefaultPrettyPrinter();

    private InspectManifest() {
    }

    public static int run(Path dataDir, PrintStream out) {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(out, "out");

        DataLayout layout = new DataLayout(dataDir);
        Map<String, Object> report;
        int exitCode;
        try {
            report = inspect(layout);
            exitCode = "VALID".equals(report.get("status")) ? EXIT_OK : EXIT_CORRUPT;
        } catch (NoSuchFileException e) {
            report = failure("MISSING", null, "Manifest not found: " + MANIFEST_FILE);
            exitCode = EXIT_MISSING;
        } catch (IOException e) {
            report = failure("IO_ERROR", null, "Reading the manifest failed: " + e.getClass().getSimpleName());
            exitCode = EXIT_IO_ERROR;
        }

        try {
            out.println(JSON.writeValueAsString(report));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Inspection report is not serializable", e);
        }
        return exitCode;
    }

    static Map<String, Object> inspect(DataLayout layout) throws IOException {
        Path file = layout.manifestFile();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(MANIFEST_FILE);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return failure("CORRUPT", Manifest.CATEGORY, "MANIFEST.json is not a regular file");
        }
        byte[] bytes = Files.readAllBytes(file);

        Manifest manifest;
        try {
            manifest = Manifest.parse(bytes);
        } catch (StorageException e) {
            Map<String, Object> report = failure("CORRUPT", e.category(), e.getMessage());
            report.put("sizeBytes", bytes.length);
            return report;
        }

        List<SSTableMeta> tables = new ArrayList<>(manifest.sstables());
        tables.sort(Comparator.comparingLong(SSTableMeta::tableId));
        Set<String> referenced = new HashSet<>();
        List<Map<String, Object>> tableReports = new ArrayList<>();
        Map<String, Object> firstProblem = null;
        for (SSTableMeta table : tables) {
            referenced.add(table.file());
            Path tableFile = layout.sstDir().resolve(table.file());
            Long sizeOnDisk = Files.isRegularFile(tableFile, LinkOption.NOFOLLOW_LINKS)
                    ? Files.size(tableFile) : null;
            String fileStatus = sizeOnDisk == null ? "MISSING"
                    : sizeOnDisk == table.sizeBytes() ? "PRESENT" : "SIZE_MISMATCH";

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tableId", table.tableId());
            item.put("file", table.file());
            item.put("minKey", table.minKey());
            item.put("maxKey", table.maxKey());
            item.put("minSequence", table.minSequence());
            item.put("maxSequence", table.maxSequence());
            item.put("entryCount", table.entryCount());
            item.put("sizeBytes", table.sizeBytes());
            item.put("fileStatus", fileStatus);
            item.put("sizeOnDisk", sizeOnDisk);
            tableReports.add(item);

            if (firstProblem == null && !"PRESENT".equals(fileStatus)) {
                firstProblem = problem(
                        sizeOnDisk == null ? REFERENCED_FILE_MISSING : MANIFEST_TABLE_MISMATCH,
                        sizeOnDisk == null
                                ? "Referenced table " + table.tableId() + " is missing"
                                : "Table " + table.tableId() + " has " + sizeOnDisk
                                        + " bytes, the manifest says " + table.sizeBytes());
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("manifestFile", MANIFEST_FILE);
        report.put("status", firstProblem == null ? "VALID" : "CORRUPT");
        report.put("sizeBytes", bytes.length);
        report.put("formatVersion", manifest.formatVersion());
        report.put("generation", manifest.generation());
        report.put("nextTableId", manifest.nextTableId());
        report.put("flushedThroughSequence", manifest.flushedThroughSequence());
        report.put("sstableCount", tables.size());
        report.put("error", firstProblem);
        report.put("sstables", tableReports);
        report.put("orphanTables", orphanTables(layout.sstDir(), referenced));
        report.put("temporaryManifests", temporaryManifests(layout.tmpDir()));
        return report;
    }

    private static List<String> orphanTables(Path sstDir, Set<String> referenced) throws IOException {
        List<String> orphans = new ArrayList<>();
        for (String name : names(sstDir)) {
            if (TableIdAllocator.parseTableId(name).isPresent() && !referenced.contains(name)) {
                orphans.add(name);
            }
        }
        return orphans;
    }

    private static List<String> temporaryManifests(Path tmpDir) throws IOException {
        List<String> temporary = new ArrayList<>();
        for (String name : names(tmpDir)) {
            if (name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)) {
                temporary.add(name);
            }
        }
        return temporary;
    }

    private static List<String> names(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                names.add(entry.getFileName().toString());
            }
        }
        names.sort(null);
        return names;
    }

    private static Map<String, Object> problem(String category, String message) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("category", category);
        problem.put("message", message);
        return problem;
    }

    private static Map<String, Object> failure(String status, String category, String message) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("manifestFile", MANIFEST_FILE);
        report.put("status", status);
        report.put("error", problem(category, message));
        return report;
    }
}
