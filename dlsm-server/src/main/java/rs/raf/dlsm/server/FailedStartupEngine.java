package rs.raf.dlsm.server;

import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.Lifecycle;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.EngineMode;
import rs.raf.dlsm.engine.GetResult;
import rs.raf.dlsm.engine.MutationResult;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.engine.ScanResult;
import rs.raf.dlsm.engine.StorageEngine;

/**
 * Stands in for the engine when startup recovery failed (CP1 §21.4): the
 * public API stays up, but every operation answers {@code 503
 * NODE_NOT_READY}. Nothing of the unproven directory state is ever served,
 * not even reads of keys that were valid before the damage (CP1-WAL-003).
 * Only a process restart tries again (CP1 §22).
 */
final class FailedStartupEngine implements StorageEngine {

    private static final String MESSAGE = "Node failed during startup recovery";

    @Override
    public MutationResult put(String key, byte[] value, OpContext ctx) throws StorageException {
        throw notReady();
    }

    @Override
    public MutationResult delete(String key, OpContext ctx) throws StorageException {
        throw notReady();
    }

    @Override
    public GetResult get(String key, OpContext ctx) throws StorageException {
        throw notReady();
    }

    @Override
    public ScanResult scan(String startInclusive, String endExclusive, int limit,
                           OpContext ctx) throws StorageException {
        throw notReady();
    }

    @Override
    public Lifecycle lifecycle() {
        return Lifecycle.FAILED;
    }

    @Override
    public EngineMode mode() {
        return EngineMode.FAILED;
    }

    /** Owns nothing: recovery closed what it opened before it failed. */
    @Override
    public void close() {
    }

    private static StorageException notReady() {
        return new StorageException(ErrorCode.NODE_NOT_READY, MESSAGE);
    }
}
