package rs.raf.dlsm.common;

/**
 * User key rules shared by the API, WAL, MemTable and SSTable (PI §18.1).
 *
 * <p>A key is 1..128 characters from {@code [A-Za-z0-9._~-]}. Keys are
 * ordered lexicographically by unsigned bytes (CP1 §8.1). Because every
 * valid key is ASCII, that order is exactly {@link String#compareTo}.
 */
public final class Keys {

    public static final int MAX_LENGTH = 128;

    private Keys() {
    }

    public static boolean isValid(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_LENGTH) {
            return false;
        }

        for (int i = 0; i < key.length(); i++) {
            if (!isAllowed(key.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    public static String requireValid(String key) {
        if (!isValid(key)) {
            throw new IllegalArgumentException("Invalid key");
        }
        return key;
    }

    /**
     * Unsigned-byte order of two valid keys. For ASCII strings UTF-16 code
     * unit order equals unsigned byte order, so {@code compareTo} is exact.
     */
    public static int compare(String a, String b) {
        return a.compareTo(b);
    }

    private static boolean isAllowed(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '~' || c == '-';
    }
}
