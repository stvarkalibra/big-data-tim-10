
package rs.raf.dlsm.sstable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32C;

public final class BloomFilter {

    public static final int HASH_FUNCTIONS = 7;
    public static final int HASH_VERSION = 1;

    private static final int MAGIC = 0x424C4D31;
    private static final int HEADER_BYTES = 20;
    private static final int CRC_BYTES = 4;

    private final int bitCount;
    private final byte[] bits;

    public BloomFilter(long expectedEntries) {
        if (expectedEntries < 0 ||
                expectedEntries > (Integer.MAX_VALUE - 7L) / 10) {
            throw new IllegalArgumentException(
                    "Invalid expected entry count");
        }

        long requestedBits = Math.max(64L, expectedEntries * 10L);
        this.bitCount = (int) (((requestedBits + 7) / 8) * 8);
        this.bits = new byte[bitCount / 8];
    }

    private BloomFilter(byte[] serializedBits) {
        if (serializedBits == null ||
                serializedBits.length < 8 ||
                serializedBits.length > Integer.MAX_VALUE / 8) {
            throw new IllegalArgumentException("Invalid bit array");
        }

        this.bits = serializedBits.clone();
        this.bitCount = bits.length * 8;
    }

    public void add(String key) {
        validateKey(key);

        for (int position : positions(key)) {
            int byteIndex = position / 8;
            int bitIndex = position % 8;

            bits[byteIndex] |= (byte) (1 << bitIndex);
        }
    }

    public boolean mightContain(String key) {
        validateKey(key);

        for (int position : positions(key)) {
            int byteIndex = position / 8;
            int bitIndex = position % 8;

            if ((bits[byteIndex] & (1 << bitIndex)) == 0) {
                return false;
            }
        }

        return true;
    }

    private int[] positions(String key) {
        byte[] hash = sha256(key);

        ByteBuffer buffer = ByteBuffer.wrap(hash)
                .order(ByteOrder.BIG_ENDIAN);

        long h1 = buffer.getLong();
        long h2 = buffer.getLong() | 1L;

        int[] positions = new int[HASH_FUNCTIONS];

        for (int i = 0; i < HASH_FUNCTIONS; i++) {
            long combined = h1 + i * h2;
            positions[i] = (int) Long.remainderUnsigned(
                    combined, bitCount);
        }

        return positions;
    }

    private static byte[] sha256(String key) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return digest.digest(
                    key.getBytes(StandardCharsets.US_ASCII));

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 not available", e);
        }
    }

    private static void validateKey(String key) {
        if (key == null ||
                key.length() < 1 ||
                key.length() > 128 ||
                !key.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Invalid key");
        }
    }

    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer
                .allocate(HEADER_BYTES + bits.length + CRC_BYTES)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(MAGIC);
        buffer.putInt(HASH_VERSION);
        buffer.putInt(bitCount);
        buffer.putInt(HASH_FUNCTIONS);
        buffer.putInt(bits.length);
        buffer.put(bits);

        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, buffer.position());

        buffer.putInt((int) crc.getValue());

        return buffer.array();
    }

    public static BloomFilter deserialize(byte[] data) {
        if (data == null ||
                data.length < HEADER_BYTES + 8 + CRC_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid Bloom filter data");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data)
                .order(ByteOrder.BIG_ENDIAN);

        int magic = buffer.getInt();
        int version = buffer.getInt();
        int m = buffer.getInt();
        int k = buffer.getInt();
        int byteLength = buffer.getInt();

        if (magic != MAGIC ||
                version != HASH_VERSION ||
                k != HASH_FUNCTIONS ||
                m < 64 ||
                m % 8 != 0 ||
                byteLength <= 0 ||
                (long) byteLength * 8 != m ||
                (long) HEADER_BYTES + byteLength + CRC_BYTES
                        != data.length) {
            throw new IllegalArgumentException(
                    "Invalid Bloom filter metadata");
        }

        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length - CRC_BYTES);

        long expectedCrc = Integer.toUnsignedLong(
                ByteBuffer.wrap(data, data.length - CRC_BYTES, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt());

        if (crc.getValue() != expectedCrc) {
            throw new IllegalArgumentException(
                    "Bloom filter checksum mismatch");
        }

        byte[] restoredBits = new byte[byteLength];
        buffer.get(restoredBits);

        return new BloomFilter(restoredBits);
    }

    public byte[] toByteArray() {
        return serialize();
    }

    public static BloomFilter fromByteArray(byte[] data) {
        return deserialize(data);
    }

    public int bitCount() {
        return bitCount;
    }

    public int hashFunctionCount() {
        return HASH_FUNCTIONS;
    }

    public void clear() {
        Arrays.fill(bits, (byte) 0);
    }
}
