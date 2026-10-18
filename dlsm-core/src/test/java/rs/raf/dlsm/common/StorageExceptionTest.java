package rs.raf.dlsm.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageExceptionTest {

    @Test
    void errorCodeNamesMatchSharedApi() {
        // Wire names from PI §18.7; renaming any of them breaks clients.
        assertEquals(
                List.of("INVALID_REQUEST", "VALUE_TOO_LARGE", "NOT_FOUND",
                        "STORAGE_IO", "INTERNAL_ERROR", "NODE_NOT_READY",
                        "DEADLINE_EXCEEDED"),
                Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    @Test
    void carriesCodeAndMessageWithoutCategory() {
        StorageException e = new StorageException(
                ErrorCode.DEADLINE_EXCEEDED, "Deadline expired");

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.code());
        assertNull(e.category());
        assertEquals("Deadline expired", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void carriesCategoryAndCause() {
        IOException cause = new IOException("disk");
        StorageException e = new StorageException(
                ErrorCode.STORAGE_IO, "WAL_FRAME", "Corrupt WAL frame", cause);

        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals("WAL_FRAME", e.category());
        assertSame(cause, e.getCause());
    }

    @Test
    void acceptsPlannedCategories() {
        for (String category : new String[] {
                "WAL_FRAME", "WAL_LENGTH_GUARD", "CHECKSUM", "WAL_HEADER",
                "WAL_SEQUENCE_ORDER", "MANIFEST_INVALID",
                "REFERENCED_FILE_MISSING", "MANIFEST_TABLE_MISMATCH",
                "DATA_BLOCK", "INDEX", "BLOOM", "FOOTER", "TRUNCATED_REGION",
                "NODE_IDENTITY_MISMATCH", "DIRECTORY_LOCKED"}) {
            assertEquals(category, new StorageException(
                    ErrorCode.STORAGE_IO, category, "x").category());
        }
    }

    @Test
    void rejectsMalformedCategory() {
        for (String category : new String[] {
                "", "wal_frame", "Wal", "_WAL", "1WAL", "WAL-FRAME", "WAL FRAME"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new StorageException(
                            ErrorCode.STORAGE_IO, category, "x"),
                    category);
        }
    }

    @Test
    void requiresCodeAndMessage() {
        assertThrows(NullPointerException.class,
                () -> new StorageException(null, "x"));
        assertThrows(NullPointerException.class,
                () -> new StorageException(ErrorCode.STORAGE_IO, null));
    }

    @Test
    void isCheckedException() {
        assertFalse(RuntimeException.class.isAssignableFrom(
                StorageException.class));
        assertTrue(Exception.class.isAssignableFrom(StorageException.class));
    }
}
