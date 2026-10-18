package rs.raf.dlsm.engine;

import rs.raf.dlsm.common.InternalRecord;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.util.Iterator;
import java.util.Optional;

/**
 * One published, immutable SSTable as the engine reads it (CP1 §11, §12).
 * The SSTable module's reader implements this; the engine never touches
 * file formats itself.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@link #get} returns this table's version of the key, which may be
 *       a tombstone, or empty when the table does not hold the key. It may
 *       use the Bloom filter and must read at most the candidate block.</li>
 *   <li>{@link #iterator} starts at the first record {@code >=
 *       startInclusive}, positioned through the sparse index, never from
 *       the first block (CP1 §12, §17.4).</li>
 *   <li>Corruption or an I/O failure is a {@link StorageException}
 *       ({@code STORAGE_IO} with a category such as {@code DATA_BLOCK}),
 *       never an empty result (I7). During iteration it is thrown as an
 *       {@link UncheckedStorageException}.</li>
 *   <li>The table never changes (I5), so it may be read concurrently.</li>
 * </ul>
 */
public interface TableSource {

    /** Identity and ranges, as recorded in the manifest. */
    SSTableMeta meta();

    default long tableId() {
        return meta().tableId();
    }

    Optional<InternalRecord> get(String key) throws StorageException;

    Iterator<InternalRecord> iterator(String startInclusive) throws StorageException;
}
