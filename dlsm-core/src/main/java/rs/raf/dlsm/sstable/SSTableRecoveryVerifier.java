
package rs.raf.dlsm.sstable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies every SSTable explicitly referenced by the manifest.
 *
 * This class:
 * - never discovers tables by listing a directory;
 * - never changes or deletes files;
 * - rejects duplicate or non-canonical table references;
 * - validates table contents through the streaming validator;
 * - stops immediately on the first invalid table.
 *
 * Recovery must not enter READY if verification fails.
 */
public final class SSTableRecoveryVerifier {

    private SSTableRecoveryVerifier() {
    }

    /**
     * @param sstDirectory directory containing published SSTables
     * @param publishedTables exact table list from the manifest
     */
    public static void verifyAll(
            Path sstDirectory,
            List<SSTableMeta> publishedTables) {

        if (sstDirectory == null || publishedTables == null) {
            fail("Missing SSTable directory or manifest table list");
        }

        Path root = sstDirectory.toAbsolutePath().normalize();

        Set<Long> tableIds = new HashSet<>();
        Set<String> fileNames = new HashSet<>();

        // Validate all manifest references before touching any file.
        for (SSTableMeta meta : publishedTables) {
            if (meta == null) {
                fail("Null SSTable metadata in manifest");
            }

            long tableId = meta.tableId();

            if (tableId <= 0) {
                fail("Invalid SSTable table ID");
            }

            String canonical = SSTableMeta.fileName(tableId);

            if (!canonical.equals(meta.file())) {
                fail("Non-canonical SSTable filename for table "
                        + tableId);
            }

            if (!tableIds.add(tableId)) {
                fail("Duplicate SSTable table ID: " + tableId);
            }

            if (!fileNames.add(meta.file())) {
                fail("Duplicate SSTable filename: " + meta.file());
            }
        }

        // Only files referenced by the manifest are validated.
        for (SSTableMeta meta : publishedTables) {
            Path file = root.resolve(meta.file()).normalize();

            if (!file.startsWith(root)) {
                fail("SSTable path escapes its directory");
            }

            if (!Files.isRegularFile(
                    file, LinkOption.NOFOLLOW_LINKS)) {
                throw new SSTableValidator.ValidationException(
                        SSTableValidator.Category.TRUNCATED_REGION,
                        "Published SSTable is missing or not a regular file: "
                                + meta.file(),
                        null
                );
            }

            // Streaming validation; never load the full file.
            SSTableValidator.validateFully(file, meta);
        }
    }

    private static void fail(String message) {
        throw new SSTableValidator.ValidationException(
                SSTableValidator.Category.MANIFEST_TABLE_MISMATCH,
                message,
                null
        );
    }
}
