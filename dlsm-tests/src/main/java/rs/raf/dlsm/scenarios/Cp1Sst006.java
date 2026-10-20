
package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableRecoveryVerifier;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.sstable.SSTableWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Cp1Sst006 {

    public static final String ID = "CP1-SST-006";

    private Cp1Sst006() {
    }

    public static void run(long seed, Report report)
            throws Exception {

        Path directory = Files.createTempDirectory(
                "cp1-sst-006-" + seed + "-"
        );

        try {
            SSTableMeta healthy = writeTable(
                    directory, 1, seed
            );

            SSTableMeta published = writeTable(
                    directory, 2, seed
            );

            SSTableMeta orphan = writeTable(
                    directory, 3, seed
            );

            // Healthy published tables must validate.
            SSTableRecoveryVerifier.verifyAll(
                    directory,
                    List.of(healthy, published)
            );

            // Corrupt an orphan. It is not in the
            // published table list.
            corruptDataBlock(
                    directory.resolve(orphan.file())
            );

            SSTableRecoveryVerifier.verifyAll(
                    directory,
                    List.of(healthy, published)
            );

            // Corrupt a published table.
            corruptDataBlock(
                    directory.resolve(published.file())
            );

            expectRejected(
                    directory,
                    List.of(healthy, published),
                    SSTableValidator.Category.DATA_BLOCK
            );

            // A missing published table must also
            // prevent successful recovery.
            Files.delete(
                    directory.resolve(published.file())
            );

            expectRejected(
                    directory,
                    List.of(healthy, published),
                    SSTableValidator.Category.TRUNCATED_REGION
            );

            // The original healthy file must remain.
            if (!Files.isRegularFile(
                    directory.resolve(healthy.file()))) {
                throw new AssertionError(
                        "Healthy SSTable was unexpectedly removed"
                );
            }

            report.pass();

        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(
                        java.util.Comparator.reverseOrder()
                ).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static SSTableMeta writeTable(
            Path directory,
            long id,
            long seed) throws Exception {

        List<BlockCodec.Record> records =
                new ArrayList<>();

        for (int i = 0; i < 150; i++) {
            byte[] value = new byte[100];

            for (int j = 0; j < value.length; j++) {
                value[j] = (byte) (seed + id + i + j);
            }

            records.add(
                    new BlockCodec.Record(
                            String.format("key%05d", i),
                            i + 1L,
                            BlockCodec.PUT,
                            value
                    )
            );
        }

        SSTableWriter.Result result =
                SSTableWriter.build(
                        id,
                        records.iterator(),
                        records.size()
                );

        Files.write(
                directory.resolve(result.meta().file()),
                result.bytes()
        );

        return result.meta();
    }

    private static void corruptDataBlock(
            Path file) throws Exception {

        byte[] bytes = Files.readAllBytes(file);

        int offset = SSTableWriter.HEADER_BYTES + 25;

        if (offset >= bytes.length) {
            throw new AssertionError(
                    "SSTable is too small to corrupt"
            );
        }

        bytes[offset] ^= 0x01;
        Files.write(file, bytes);
    }

    private static void expectRejected(
            Path directory,
            List<SSTableMeta> published,
            SSTableValidator.Category expectedCategory) {

        try {
            SSTableRecoveryVerifier.verifyAll(
                    directory,
                    published
            );

        } catch (
                SSTableValidator.ValidationException e
        ) {
            if (e.category() != expectedCategory) {
                throw new AssertionError(
                        "Expected " + expectedCategory
                                + " but got " + e.category(),
                        e
                );
            }

            return;
        }

        throw new AssertionError(
                "Corrupted published SSTable was accepted"
        );
    }
}
