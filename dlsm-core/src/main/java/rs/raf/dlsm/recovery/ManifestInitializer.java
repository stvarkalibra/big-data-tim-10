package rs.raf.dlsm.recovery;

import rs.raf.dlsm.engine.OpContext;

import java.io.IOException;

/**
 * Step 3 of first initialization (CP1 §14.2): atomically publish the empty
 * manifest of generation 0,
 * {@code {"formatVersion":1,"generation":0,"nextTableId":1,"flushedThroughSequence":0,"sstables":[]}}.
 *
 * <p>The manifest belongs to the manifest module (plan §4.3, Ilija); its
 * {@code ManifestManager} provides this step, so this package never writes
 * {@code MANIFEST.json} itself.
 */
@FunctionalInterface
public interface ManifestInitializer {

    /**
     * @throws IOException if the manifest is not durably published; startup
     *                     then fails and the next start resumes at step 3
     */
    void publishInitialManifest(OpContext ctx) throws IOException;
}
