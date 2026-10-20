package rs.raf.dlsm.io;

import java.io.IOException;

public class InjectedIOException extends IOException {
    private final String failpointName;

    public InjectedIOException(String failpointName, String message) {
        super("Injected I/O error at failpoint [" + failpointName + "]: " + message);
        this.failpointName = failpointName;
    }

    public String getfailpointName() {
        return failpointName;
    }
}
