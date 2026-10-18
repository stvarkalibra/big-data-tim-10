
package rs.raf.dlsm.sstable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class SSTableIterator
        implements Iterator<BlockCodec.Record>, AutoCloseable {

    private final byte[] file;
    private final SparseIndex index;
    private final String startInclusive;

    private int blockIndex;
    private List<BlockCodec.Record> currentRecords = List.of();
    private int recordIndex;
    private boolean closed;

    public SSTableIterator(
            byte[] file,
            SSTableMeta meta,
            String startInclusive) {

        Objects.requireNonNull(file);
        Objects.requireNonNull(meta);
        Objects.requireNonNull(startInclusive);

        if (!startInclusive.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new IllegalArgumentException("Invalid start key");
        }

        SSTableReader.open(file, meta);

        this.file = file.clone();
        this.startInclusive = startInclusive;

        int footerStart =
                this.file.length - SSTableWriter.FOOTER_BYTES;

        ByteBuffer footer = ByteBuffer.wrap(this.file)
                .order(ByteOrder.BIG_ENDIAN);

        long indexOffset = footer.getLong(footerStart + 32);
        long indexLength = footer.getLong(footerStart + 40);

        if (indexOffset < 0 || indexLength <= 0 ||
                indexOffset > this.file.length ||
                indexLength > this.file.length - indexOffset) {
            throw new IllegalArgumentException("Invalid index region");
        }

        byte[] indexBytes = Arrays.copyOfRange(
                this.file,
                Math.toIntExact(indexOffset),
                Math.toIntExact(indexOffset + indexLength));

        this.index = SparseIndex.deserialize(indexBytes);

        SparseIndex.Entry candidate = index.findBlock(startInclusive);

        this.blockIndex = candidate == null
                ? 0
                : index.entries().indexOf(candidate);
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }

        while (recordIndex >= currentRecords.size()) {
            if (blockIndex >= index.size()) {
                return false;
            }

            loadNextBlock();
        }

        return true;
    }

    @Override
    public BlockCodec.Record next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        return currentRecords.get(recordIndex++);
    }

    private void loadNextBlock() {
        SparseIndex.Entry entry = index.entries().get(blockIndex++);

        byte[] block = Arrays.copyOfRange(
                file,
                Math.toIntExact(entry.offset()),
                Math.toIntExact(entry.offset() + entry.length()));

        currentRecords = BlockCodec.decode(block);
        recordIndex = 0;

        while (recordIndex < currentRecords.size() &&
                currentRecords.get(recordIndex).key()
                        .compareTo(startInclusive) < 0) {
            recordIndex++;
        }
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        closed = true;
        currentRecords = List.of();
    }
}
