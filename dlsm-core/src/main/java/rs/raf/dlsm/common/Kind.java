package rs.raf.dlsm.common;

/**
 * Mutation kind of one key version. The byte code is part of the on-disk
 * WAL and SSTable formats (FORMAT.md) and must never change.
 */
public enum Kind {
    PUT((byte) 1),
    DELETE((byte) 2);

    private final byte code;

    Kind(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static Kind fromCode(byte code) {
        return switch (code) {
            case 1 -> PUT;
            case 2 -> DELETE;
            default -> throw new IllegalArgumentException(
                    "Unknown record kind: " + code);
        };
    }
}
