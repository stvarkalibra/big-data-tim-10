
package rs.raf.dlsm.sstable;

import java.util.Objects;

public final class SSTableValidator {

    public enum Category {
        HEADER,
        DATA_BLOCK,
        INDEX,
        BLOOM,
        FOOTER,
        TRUNCATED_REGION,
        MANIFEST_TABLE_MISMATCH
    }

    public static final class ValidationException
            extends IllegalArgumentException {

        private final Category category;

        public ValidationException(
                Category category, String message, Throwable cause) {
            super(message, cause);
            this.category = Objects.requireNonNull(category);
        }

        public Category category() {
            return category;
        }
    }

    private SSTableValidator() {
    }

    public static void validateFully(
            byte[] data, SSTableMeta expected) {

        if (expected == null) {
            throw new ValidationException(
                    Category.MANIFEST_TABLE_MISMATCH,
                    "Missing expected table metadata", null);
        }

        if (data == null ||
                data.length < SSTableWriter.HEADER_BYTES
                        + SSTableWriter.FOOTER_BYTES) {
            throw new ValidationException(
                    Category.TRUNCATED_REGION,
                    "SSTable is missing or truncated", null);
        }

        try {
            SSTableReader reader =
                    SSTableReader.open(data, expected);

            reader.validateFully();

        } catch (IllegalArgumentException e) {
            if (e instanceof ValidationException validation) {
                throw validation;
            }

            throw new ValidationException(
                    classify(e.getMessage()),
                    "SSTable validation failed: " + e.getMessage(),
                    e);
        }
    }

    public static boolean isValid(
            byte[] data, SSTableMeta expected) {
        try {
            validateFully(data, expected);
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    private static Category classify(String message) {
        if (message == null) {
            return Category.DATA_BLOCK;
        }

        String text = message.toLowerCase(
                java.util.Locale.ROOT);

        if (text.contains("metadata mismatch")
                || text.contains("table identity")) {
            return Category.MANIFEST_TABLE_MISMATCH;
        }

        if (text.contains("header")) {
            return Category.HEADER;
        }

        if (text.contains("footer")
                || text.contains("reserved")) {
            return Category.FOOTER;
        }

        if (text.contains("index")
                || text.contains("block location")) {
            return Category.INDEX;
        }

        if (text.contains("bloom")) {
            return Category.BLOOM;
        }

        if (text.contains("truncated")
                || text.contains("region boundaries")
                || text.contains("outside data region")) {
            return Category.TRUNCATED_REGION;
        }

        return Category.DATA_BLOCK;
    }
}
