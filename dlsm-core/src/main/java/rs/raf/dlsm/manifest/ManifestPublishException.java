package rs.raf.dlsm.manifest;

import java.io.IOException;

public final class ManifestPublishException extends IOException {

    private final long generation;
    private final boolean renamed;

    public ManifestPublishException(long generation, boolean renamed, IOException cause) {
        super((renamed
                ? "Manifest generation " + generation + " was renamed but its directory sync failed"
                : "Manifest generation " + generation + " was not published"), cause);
        this.generation = generation;
        this.renamed = renamed;
    }

    public long generation() {
        return generation;
    }

    public boolean renamed() {
        return renamed;
    }
}
