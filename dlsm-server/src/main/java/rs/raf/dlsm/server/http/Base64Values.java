package rs.raf.dlsm.server.http;

import java.util.Base64;

/**
 * Values in JSON are standard Base64 with padding (PI §18.1): one byte
 * {@code A} is {@code "QQ=="}, the empty value is {@code ""}.
 *
 * <p>Decoding is strict. The JDK decoder alone would accept input without
 * padding and non-canonical trailing bits ({@code "QR=="} also decodes to
 * {@code A}), so the input must have a length divisible by 4 and must
 * re-encode to exactly itself.
 */
public final class Base64Values {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private Base64Values() {
    }

    public static String encode(byte[] value) {
        return ENCODER.encodeToString(value);
    }

    /** @return the decoded bytes, or {@code null} when not strict standard Base64 */
    public static byte[] decodeStrict(String text) {
        if (text == null || text.length() % 4 != 0) {
            return null;
        }

        byte[] decoded;
        try {
            decoded = DECODER.decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }

        return ENCODER.encodeToString(decoded).equals(text) ? decoded : null;
    }

    /** Longest Base64 text that can decode to at most {@code maxBytes}. */
    public static long maxEncodedLength(long maxBytes) {
        return 4 * ((maxBytes + 2) / 3);
    }
}
