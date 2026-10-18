package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextTest {

    @Test
    void acceptsValidRequestIds() throws ApiException {
        for (String id : new String[] {
                "put-001", "a", "CP1-API-003-12345-7", "x.y_z:1",
                "r".repeat(64)}) {
            assertEquals(id, RequestContext.requestId(List.of(id), true));
        }
    }

    @Test
    void rejectsInvalidRequestIds() {
        for (String id : new String[] {
                "", "r".repeat(65), "a b", "a/b", "a~b", "a+b", "č", "a,b"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> RequestContext.requestId(List.of(id), false), id);
            assertEquals(ErrorCode.INVALID_REQUEST, e.code());
        }
    }

    @Test
    void missingRequestIdIsErrorOnlyWhenRequired() throws ApiException {
        assertThrows(ApiException.class,
                () -> RequestContext.requestId(null, true));
        assertThrows(ApiException.class,
                () -> RequestContext.requestId(List.of(), true));
        assertNull(RequestContext.requestId(null, false));
    }

    @Test
    void repeatedRequestIdIsRejected() {
        assertThrows(ApiException.class,
                () -> RequestContext.requestId(List.of("a", "b"), false));
    }

    @Test
    void timeoutDefaultsTo5000() throws ApiException {
        assertEquals(5000, RequestContext.timeoutMillis(null));
        assertEquals(5000, RequestContext.timeoutMillis(List.of()));
    }

    @Test
    void timeoutAcceptsInclusiveBounds() throws ApiException {
        assertEquals(100, RequestContext.timeoutMillis(List.of("100")));
        assertEquals(10000, RequestContext.timeoutMillis(List.of("10000")));
        assertEquals(250, RequestContext.timeoutMillis(List.of("0250")));
    }

    @Test
    void timeoutRejectsOutOfRangeAndMalformed() {
        for (String value : new String[] {
                "99", "10001", "0", "-5", "+500", "", " 500", "5e3", "1.5",
                "abc", "99999999999999999999"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> RequestContext.timeoutMillis(List.of(value)), value);
            assertEquals(ErrorCode.INVALID_REQUEST, e.code());
        }

        assertThrows(ApiException.class,
                () -> RequestContext.timeoutMillis(List.of("500", "600")));
    }
}
