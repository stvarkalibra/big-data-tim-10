
package rs.raf.dlsm.sstable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class SparseIndex {

    private static final int MAGIC = 0x49445831; // IDX1
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 10_000_000;
    private static final int MAX_INDEX_BYTES = 256 * 1024 * 1024;

    public record Entry(String firstKey, long offset, int length) {
        public Entry {
            validateKey(firstKey);
            if (offset < 0 || length <= 0) {
                throw new IllegalArgumentException(
                        "Invalid block location");
            }
            if (offset > Long.MAX_VALUE - length) {
                throw new IllegalArgumentException(
                        "Block location overflow");
            }
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(String firstKey, long offset, int length) {
        Entry entry = new Entry(firstKey, offset, length);

        if (!entries.isEmpty()) {
            Entry previous = entries.get(entries.size() - 1);

            if (previous.firstKey().compareTo(firstKey) >= 0) {
                throw new IllegalArgumentException(
                        "Index keys must be strictly increasing");
            }

            if (previous.offset() + previous.length() > offset) {
                throw new IllegalArgumentException(
                        "Index blocks must not overlap");
            }
        }

        if (entries.size() >= MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "Too many index entries");
        }

        entries.add(entry);
    }

    public Entry findBlock(String key) {
        validateKey(key);

        int low = 0;
        int high = entries.size() - 1;
        Entry candidate = null;

        while (low <= high) {
            int mid = low + (high - low) / 2;
            Entry current = entries.get(mid);

            if (current.firstKey().compareTo(key) <= 0) {
                candidate = current;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return candidate;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public byte[] serialize() {
        long total = 12L + 4;

        for (Entry entry : entries) {
            total += 4L
                    + entry.firstKey().length()
                    + 8 + 4;
        }

        if (total > MAX_INDEX_BYTES) {
            throw new IllegalStateException("Index too large");
        }

        ByteBuffer buffer = ByteBuffer.allocate((int) total)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(entries.size());

        for (Entry entry : entries) {
            byte[] key = entry.firstKey()
                    .getBytes(StandardCharsets.US_ASCII);

            buffer.putInt(key.length);
            buffer.put(key);
            buffer.putLong(entry.offset());
            buffer.putInt(entry.length());
        }

        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) crc.getValue());

        return buffer.array();
    }

    public static SparseIndex deserialize(byte[] data) {
        if (data == null || data.length < 16
                || data.length > MAX_INDEX_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid index size");
        }

        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length - 4);

        long actualCrc = crc.getValue();

        long storedCrc = Integer.toUnsignedLong(
                ByteBuffer.wrap(data, data.length - 4, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt());

        if (actualCrc != storedCrc) {
            throw new IllegalArgumentException(
                    "Index checksum mismatch");
        }

        ByteBuffer buffer = ByteBuffer.wrap(
                        data, 0, data.length - 4)
                .order(ByteOrder.BIG_ENDIAN);

        if (buffer.getInt() != MAGIC) {
            throw new IllegalArgumentException(
                    "Invalid index magic");
        }

        if (buffer.getInt() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported index version");
        }

        int count = buffer.getInt();

        if (count < 0 || count > MAX_ENTRIES
                || (long) count * 17 > buffer.remaining()) {
            throw new IllegalArgumentException(
                    "Invalid entry count");
        }

        SparseIndex index = new SparseIndex();

        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 4) {
                throw new IllegalArgumentException(
                        "Truncated index entry");
            }

            int keyLength = buffer.getInt();

            if (keyLength < 1 || keyLength > 128
                    || buffer.remaining() < keyLength + 12) {
                throw new IllegalArgumentException(
                        "Invalid index key length");
            }

            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);

            String key = new String(
                    keyBytes, StandardCharsets.US_ASCII);

            if (!java.util.Arrays.equals(
                    keyBytes,
                    key.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException(
                        "Invalid index key encoding");
            }

            long offset = buffer.getLong();
            int length = buffer.getInt();

            index.add(key, offset, length);
        }

        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Unexpected index bytes");
        }

        return index;
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 1
                || key.length() > 128
                || !key.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Invalid key");
        }
    }
}
