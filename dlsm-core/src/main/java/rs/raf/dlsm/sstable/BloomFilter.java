
package rs.raf.dlsm.sstable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public final class BloomFilter {

    public static final int HASH_FUNCTIONS = 7;
    public static final int HASH_VERSION = 1;

    private final int bitCount;
    private final byte[] bits;

    public BloomFilter(long expectedEntries) {
        if (expectedEntries < 0 ||
                expectedEntries > (Integer.MAX_VALUE - 7L) / 10) {
            throw new IllegalArgumentException(
                    "Invalid expected entry count");
        }

        long requestedBits = Math.max(64L, expectedEntries * 10L);
        this.bitCount = (int) ((requestedBits + 7) / 8 * 8);
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

    private byte[] sha256(String key) {
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

    private void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Invalid key");
        }

        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(key)) {
            throw new IllegalArgumentException(
                    "Key must contain ASCII characters");
        }
    }

    public byte[] toByteArray() {
        return bits.clone();
    }

    public static BloomFilter fromByteArray(byte[] data) {
        return new BloomFilter(data);
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
