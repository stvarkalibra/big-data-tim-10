
package rs.raf.dlsm.inspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.sstable.SSTableWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class InspectSSTableCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_CORRUPT = 1;
    public static final int EXIT_MISSING = 2;
    public static final int EXIT_IO_ERROR = 3;

    private static final int FOOTER_BYTES = 384;
    private static final int FOOTER_MAGIC = 0x46545231;
    private static final int FORMAT_VERSION = 1;
    private static final int KEY_BYTES = 128;

    private static final ObjectWriter JSON =
            new ObjectMapper().writerWithDefaultPrettyPrinter();

    private InspectSSTableCommand() {
    }

    public static int run(
            Path dataDir,
            long tableId,
            PrintStream out) {

        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(out, "out");

        if (tableId <= 0) {
            print(out, error(
                    "INVALID_TABLE_ID",
                    "Table ID must be positive"
            ));
            return EXIT_CORRUPT;
        }

        Path file = dataDir.resolve("lsm")
                .resolve("sst")
                .resolve(SSTableMeta.fileName(tableId));

        if (!Files.isRegularFile(file)) {
            print(out, error(
                    "MISSING",
                    "Requested SSTable does not exist"
            ));
            return EXIT_MISSING;
        }

        try {
            SSTableMeta metadata = readMetadata(file, tableId);

            // Production full validation is Path-based and streaming.
            SSTableValidator.validateFully(file, metadata);

            // The existing inspector returns detailed record metadata.
            // It currently uses an in-memory representation.
            Map<String, Object> report =
                    InspectSSTable.inspect(
                            Files.readAllBytes(file),
                            metadata
                    );

            print(out, report);
            return EXIT_OK;

        } catch (SSTableValidator.ValidationException e) {
            print(out, corruption(
                    e.category().name(),
                    e.getMessage()
            ));
            return EXIT_CORRUPT;

        } catch (IllegalArgumentException
                 | IndexOutOfBoundsException e) {
            print(out, corruption(
                    "FOOTER",
                    "Invalid SSTable structure"
            ));
            return EXIT_CORRUPT;

        } catch (IOException e) {
            print(out, error(
                    "IO_ERROR",
                    "Cannot read SSTable"
            ));
            return EXIT_IO_ERROR;
        }
    }

    private static SSTableMeta readMetadata(
            Path file,
            long requestedId) throws IOException {

        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.READ)) {

            long size = channel.size();

            if (size < SSTableWriter.HEADER_BYTES
                    + FOOTER_BYTES) {
                throw new SSTableValidator.ValidationException(
                        SSTableValidator.Category.TRUNCATED_REGION,
                        "SSTable is truncated",
                        null
                );
            }

            byte[] footerBytes = new byte[FOOTER_BYTES];
            ByteBuffer target = ByteBuffer.wrap(footerBytes);

            long offset = size - FOOTER_BYTES;

            while (target.hasRemaining()) {
                int read = channel.read(target, offset);

                if (read <= 0) {
                    throw new IOException(
                            "Incomplete footer read"
                    );
                }

                offset += read;
            }

            ByteBuffer footer =
                    ByteBuffer.wrap(footerBytes)
                            .order(ByteOrder.BIG_ENDIAN);

            int magic = footer.getInt();
            int version = footer.getInt();
            long tableId = footer.getLong();

            if (magic != FOOTER_MAGIC
                    || version != FORMAT_VERSION
                    || tableId != requestedId) {
                throw new SSTableValidator.ValidationException(
                        SSTableValidator.Category.FOOTER,
                        "Invalid SSTable footer identity",
                        null
                );
            }

            // Region offsets and lengths.
            for (int i = 0; i < 6; i++) {
                footer.getLong();
            }

            long minSequence = footer.getLong();
            long maxSequence = footer.getLong();
            long entryCount = footer.getLong();

            String minKey = readKey(footer);
            String maxKey = readKey(footer);

            return new SSTableMeta(
                    tableId,
                    SSTableMeta.fileName(tableId),
                    minKey,
                    maxKey,
                    minSequence,
                    maxSequence,
                    entryCount,
                    size
            );
        }
    }

    private static String readKey(ByteBuffer buffer) {
        int length = buffer.getInt();

        if (length < 1 || length > KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid footer key length"
            );
        }

        byte[] padded = new byte[KEY_BYTES];
        buffer.get(padded);

        for (int i = length; i < KEY_BYTES; i++) {
            if (padded[i] != 0) {
                throw new IllegalArgumentException(
                        "Invalid footer key padding"
                );
            }
        }

        String key = new String(
                padded,
                0,
                length,
                StandardCharsets.US_ASCII
        );

        if (!key.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException(
                    "Invalid SSTable key"
            );
        }

        return key;
    }

    private static Map<String, Object> error(
            String status,
            String message) {

        Map<String, Object> report =
                new LinkedHashMap<>();

        report.put("status", status);
        report.put("valid", false);
        report.put("message", message);

        return report;
    }

    private static Map<String, Object> corruption(
            String category,
            String message) {

        Map<String, Object> report = error(
                "CORRUPT",
                message
        );

        report.put("corruptionCategory", category);

        return report;
    }

    private static void print(
            PrintStream out,
            Map<String, Object> report) {

        try {
            out.println(JSON.writeValueAsString(report));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Cannot serialize SSTable inspection", e
            );
        }
    }
}
