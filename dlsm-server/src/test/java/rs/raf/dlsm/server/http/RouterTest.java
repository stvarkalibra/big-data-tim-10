package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    private static final ApiHandler GET_KV = (r, c) -> ApiResponse.ok(Map.of());
    private static final ApiHandler PUT_KV = (r, c) -> ApiResponse.ok(Map.of());
    private static final ApiHandler SCAN = (r, c) -> ApiResponse.ok(Map.of());

    private static Router router() {
        return new Router()
                .add("GET", "/v1/kv/{key}", GET_KV)
                .add("PUT", "/v1/kv/{key}", PUT_KV)
                .add("GET", "/v1/scan", SCAN);
    }

    @Test
    void matchesLiteralRoute() {
        Router.Found found = assertInstanceOf(Router.Found.class,
                router().match("GET", "/v1/scan"));

        assertSame(SCAN, found.handler());
        assertTrue(found.pathParams().isEmpty());
    }

    @Test
    void bindsPathParameterAndPicksMethod() {
        Router.Found get = assertInstanceOf(Router.Found.class,
                router().match("GET", "/v1/kv/user.1"));
        Router.Found put = assertInstanceOf(Router.Found.class,
                router().match("PUT", "/v1/kv/user.1"));

        assertSame(GET_KV, get.handler());
        assertSame(PUT_KV, put.handler());
        assertEquals(Map.of("key", "user.1"), get.pathParams());
    }

    @Test
    void wrongMethodListsAllowedMethods() {
        Router.MethodNotAllowed result = assertInstanceOf(
                Router.MethodNotAllowed.class,
                router().match("POST", "/v1/kv/a"));

        assertEquals(Set.of("GET", "PUT"), result.allowedMethods());
    }

    @Test
    void unknownPathsAreNotFound() {
        Router router = router();

        for (String path : new String[] {
                "/", "/v1", "/v1/kv", "/v1/kv/", "/v1/kv/a/b", "/v1/scan/",
                "/v2/scan", "/V1/scan"}) {
            assertInstanceOf(Router.NotFound.class,
                    router.match("GET", path), path);
        }
    }

    @Test
    void duplicateRouteIsRejected() {
        Router router = router();

        assertThrows(IllegalStateException.class,
                () -> router.add("GET", "/v1/kv/{name}", GET_KV));
        assertDoesNotThrow(() -> router.add("DELETE", "/v1/kv/{key}", GET_KV));
    }
}
