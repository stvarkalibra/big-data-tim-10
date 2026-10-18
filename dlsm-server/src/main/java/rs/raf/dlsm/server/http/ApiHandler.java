package rs.raf.dlsm.server.http;

import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;

/**
 * One route's logic. Throwing {@link ApiException} or
 * {@link StorageException} produces the shared error envelope; any other
 * exception becomes {@code INTERNAL_ERROR} without details.
 */
@FunctionalInterface
public interface ApiHandler {

    ApiResponse handle(ApiRequest request, OpContext ctx)
            throws ApiException, StorageException;
}
