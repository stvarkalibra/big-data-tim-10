
package rs.raf.dlsm.sstable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

public final class BlockCodec {

    public static final int DATA_BLOCK_TARGET_BYTES = 4096;
    public static final int MAX_VALUE_BYTES = 262_144;

    public static final byte PUT = 1;
    public static final byte DELETE = 2;

    private static final int MAGIC = 0x44424C31; // DBL1
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 16;
    private static final int CRC_BYTES = 4;
    private static final int RECORD_HEADER_BYTES = 17;

    private static final int MAX_BLOCK_BYTES =
            MAX_VALUE_BYTES + 1024;

    private BlockCodec() {
    }

    public record Record(
            String key,
            long sequence,
            byte kind,
            byte[] value) {

        public Record {
            validateKey(key);

            if (sequence <= 0) {
                throw new IllegalArgumentException(
                        "Sequence must be positive");
            }

            if (kind != PUT && kind != DELETE) {
                throw new IllegalArgumentException(
                        "Invalid record kind");
            }

            if (kind == DELETE && value != null) {
                throw new IllegalArgumentException(
                        "DELETE must not contain a value");
            }

            if (kind == PUT && value == null) {
                throw new IllegalArgumentException(
                        "PUT requires a value");
            }

            if (value != null && value.length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException(
                        "Value too large");
            }

            value = value == null ? null : value.clone();
        }

        @Override
        public byte[] value() {
            return value == null ? null : value.clone();
        }

        public boolean tombstone() {
            return kind == DELETE;
        }
    }

    public static int encodedRecordSize(Record record) {
        return RECORD_HEADER_BYTES
                + record.key().length()
                + (record.tombstone() ? 0 : record.value().length);
    }

    public static byte[] encode(List<Record> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException(
                    "Data block must not be empty");
        }

        long payloadSize = 0;
        String previousKey = null;

        for (Record record : records) {
            if (record == null) {
                throw new IllegalArgumentException("Null record");
            }

            if (previousKey != null
                    && previousKey.compareTo(record.key()) >= 0) {
                throw new IllegalArgumentException(
                        "Keys must be strictly increasing");
            }

            payloadSize += encodedRecordSize(record);
            previousKey = record.key();
        }

        long totalSize = HEADER_BYTES + payloadSize + CRC_BYTES;

        if (totalSize > MAX_BLOCK_BYTES) {
            throw new IllegalArgumentException(
                    "Data block too large");
        }

        ByteBuffer buffer = ByteBuffer
                .allocate((int) totalSize)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(records.size());
        buffer.putInt((int) payloadSize);

        for (Record record : records) {
            byte[] keyBytes = record.key()
                    .getBytes(StandardCharsets.US_ASCII);

            byte[] value = record.value();

            buffer.putInt(keyBytes.length);
            buffer.putLong(record.sequence());
            buffer.put(record.kind());
            buffer.putInt(value == null ? -1 : value.length);
            buffer.put(keyBytes);

            if (value != null) {
                buffer.put(value);
            }
        }

        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, buffer.position());

        buffer.putInt((int) crc.getValue());

        return buffer.array();
    }

    public static List<Record> decode(byte[] data) {
        if (data == null
                || data.length < HEADER_BYTES + CRC_BYTES
                + RECORD_HEADER_BYTES + 1
                || data.length > MAX_BLOCK_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid data block size");
        }

        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length - CRC_BYTES);

        long storedCrc = Integer.toUnsignedLong(
                ByteBuffer.wrap(data, data.length - CRC_BYTES, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt());

        if (crc.getValue() != storedCrc) {
            throw new IllegalArgumentException(
                    "Data block checksum mismatch");
        }

        ByteBuffer buffer = ByteBuffer.wrap(
                        data, 0, data.length - CRC_BYTES)
                .order(ByteOrder.BIG_ENDIAN);

        if (buffer.getInt() != MAGIC) {
            throw new IllegalArgumentException(
                    "Invalid data block magic");
        }

        if (buffer.getInt() != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported data block version");
        }

        int count = buffer.getInt();
        int payloadLength = buffer.getInt();

        if (count <= 0
                || payloadLength != buffer.remaining()
                || (long) count * (RECORD_HEADER_BYTES + 1)
                > payloadLength) {
            throw new IllegalArgumentException(
                    "Invalid block metadata");
        }

        List<Record> result = new ArrayList<>();
        String previousKey = null;

        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < RECORD_HEADER_BYTES) {
                throw new IllegalArgumentException(
                        "Truncated record header");
            }

            int keyLength = buffer.getInt();
            long sequence = buffer.getLong();
            byte kind = buffer.get();
            int valueLength = buffer.getInt();

            if (keyLength < 1 || keyLength > 128
                    || sequence <= 0
                    || (kind != PUT && kind != DELETE)) {
                throw new IllegalArgumentException(
                        "Invalid record metadata");
            }

            if (kind == PUT
                    && (valueLength < 0
                    || valueLength > MAX_VALUE_BYTES)) {
                throw new IllegalArgumentException(
                        "Invalid PUT value length");
            }

            if (kind == DELETE && valueLength != -1) {
                throw new IllegalArgumentException(
                        "Invalid DELETE value length");
            }

            long needed = (long) keyLength
                    + Math.max(0, valueLength);

            if (needed > buffer.remaining()) {
                throw new IllegalArgumentException(
                        "Truncated record body");
            }

            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);

            String key = new String(
                    keyBytes, StandardCharsets.US_ASCII);

            if (!Arrays.equals(
                    keyBytes,
                    key.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException(
                        "Invalid ASCII key encoding");
            }

            validateKey(key);

            if (previousKey != null
                    && previousKey.compareTo(key) >= 0) {
                throw new IllegalArgumentException(
                        "Unsorted or duplicate block key");
            }

            byte[] value = null;

            if (kind == PUT) {
                value = new byte[valueLength];
                buffer.get(value);
            }

            result.add(new Record(key, sequence, kind, value));
            previousKey = key;
        }

        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Unexpected trailing record bytes");
        }

        return List.copyOf(result);
    }

    public static boolean fitsTarget(
            int currentPayloadBytes,
            Record nextRecord) {

        if (currentPayloadBytes < 0) {
            throw new IllegalArgumentException(
                    "Negative payload size");
        }

        if (currentPayloadBytes == 0) {
            return true;
        }

        return (long) currentPayloadBytes
                + encodedRecordSize(nextRecord)
                <= DATA_BLOCK_TARGET_BYTES;
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 1
                || key.length() > 128
                || !key.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Invalid key");
        }
    }
}
