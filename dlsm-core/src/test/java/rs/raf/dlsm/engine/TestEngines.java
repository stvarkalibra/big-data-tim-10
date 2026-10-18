package rs.raf.dlsm.engine;

/** Engines for tests that drive rotation and flush by hand. */
final class TestEngines {

    private TestEngines() {
    }

    /**
     * In-memory engine without a background flush worker: frozen
     * generations stay until the test publishes them itself.
     */
    static Engine withoutFlusher(EngineConfig config) {
        InMemoryWal wal = new InMemoryWal();
        return new Engine(config, wal, wal.createSegment(OpContext.background()),
                SequenceAllocator.forNewDatabase(), EngineView.EMPTY);
    }
}
