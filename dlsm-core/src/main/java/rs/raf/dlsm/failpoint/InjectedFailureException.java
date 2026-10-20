package rs.raf.dlsm.failpoint;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;

import java.util.Objects;
public final class InjectedFailureException extends RuntimeException {

    private final String failpointName;
    private final ErrorCode code;
    private final String category;

    public InjectedFailureException(String failpointName, ErrorCode code, String category, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.failpointName = Objects.requireNonNull(failpointName, "failpointName");
        this.code = Objects.requireNonNull(code, "code");
        this.category = Objects.requireNonNull(category, "category");
    }
    public String failpointName() {
        return failpointName;
    }
    public ErrorCode code() {
        return code;
    }
    public String category() {
        return category;
    }
    public StorageException toStorageException() {
        return new StorageException(code, category, getMessage(), this);
    }
}