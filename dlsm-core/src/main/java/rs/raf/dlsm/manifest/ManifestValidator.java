package rs.raf.dlsm.manifest;

import rs.raf.dlsm.common.Keys;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class ManifestValidator {

    private ManifestValidator() {
    }

    public static void validate(Manifest manifest) throws StorageException {
        Objects.requireNonNull(manifest, "manifest");

        if (manifest.formatVersion() != Manifest.FORMAT_VERSION) {
            throw Manifest.invalid("Unsupported formatVersion " + manifest.formatVersion(), null);
        }
        if (manifest.generation() < 0) {
            throw Manifest.invalid("generation is negative: " + manifest.generation(), null);
        }
        if (manifest.nextTableId() < 1) {
            throw Manifest.invalid("nextTableId is below 1: " + manifest.nextTableId(), null);
        }
        if (manifest.flushedThroughSequence() < 0) {
            throw Manifest.invalid("flushedThroughSequence is negative: "
                    + manifest.flushedThroughSequence(), null);
        }

        Set<Long> ids = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (SSTableMeta table : manifest.sstables()) {
            long id = table.tableId();
            if (!ids.add(id)) {
                throw Manifest.invalid("Duplicate tableId " + id, null);
            }
            if (!files.add(table.file())) {
                throw Manifest.invalid("Duplicate file " + table.file(), null);
            }
            if (id >= manifest.nextTableId()) {
                throw Manifest.invalid("tableId " + id + " is not below nextTableId "
                        + manifest.nextTableId(), null);
            }
            if (!table.file().equals(SSTableMeta.fileName(id))) {
                throw Manifest.invalid("tableId " + id + " has file " + table.file(), null);
            }
            if (!Keys.isValid(table.minKey()) || !Keys.isValid(table.maxKey())) {
                throw Manifest.invalid("tableId " + id + " has an invalid key range", null);
            }
            if (Keys.compare(table.minKey(), table.maxKey()) > 0) {
                throw Manifest.invalid("tableId " + id + " has minKey above maxKey", null);
            }
            if (table.maxSequence() > manifest.flushedThroughSequence()) {
                throw Manifest.invalid("tableId " + id + " maxSequence " + table.maxSequence()
                        + " is above flushedThroughSequence " + manifest.flushedThroughSequence(), null);
            }
        }
    }
}
