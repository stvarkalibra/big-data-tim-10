package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.DiskSSTableTableSource;
import rs.raf.dlsm.sstable.SSTableWriter;
import rs.raf.dlsm.stats.StorageStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Writes real SSTable files with the production writer and opens them with
 * the production disk reader, for engine tests over actual files.
 *
 * <p>Files are written plainly, without the force/rename/fsync protocol:
 * these tests are about reading, not about publishing durably.
 */
final class DiskTables {

    private DiskTables() {
    }

    /** Writes {@code records} (key order, one per key) as table {@code tableId} in {@code dir}. */
    static DiskSSTableTableSource write(Path dir, long tableId, List<InternalRecord> records,
                                       StorageStats stats) throws IOException, StorageException {
        return write(dir, tableId, records.iterator(), records.size(), stats);
    }

    static DiskSSTableTableSource write(Path dir, long tableId, Iterator<InternalRecord> records,
                                        long entryCount, StorageStats stats)
            throws IOException, StorageException {
        List<BlockCodec.Record> encoded = new ArrayList<>();
        records.forEachRemaining(r -> encoded.add(new BlockCodec.Record(
                r.key(), r.sequence(), r.kind().code(), r.value())));

        SSTableWriter.Result result = SSTableWriter.build(tableId, encoded.iterator(), entryCount);
        Path file = dir.resolve(result.meta().file());
        Files.write(file, result.bytes());
        return DiskSSTableTableSource.open(file, result.meta(), stats);
    }

    /** The file of a table written by {@link #write}. */
    static Path fileOf(Path dir, TableSource table) {
        return dir.resolve(table.meta().file());
    }
}
