package rs.raf.dlsm.server.http;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ApiRequestTest {

    private static ApiRequest withQuery(String rawQuery) throws ApiException {
        return ApiRequest.of("GET", "/v1/scan", Map.of(), rawQuery, null);
    }

    @Test
    void parsesQueryParameters() throws ApiException {
        ApiRequest r = withQuery("start=alpha&end=omega&limit=100");

        assertEquals(Optional.of("alpha"), r.queryParam("start"));
        assertEquals(Optional.of("omega"), r.queryParam("end"));
        assertEquals(Optional.of("100"), r.queryParam("limit"));
        assertEquals(Optional.empty(), r.queryParam("missing"));
    }

    @Test
    void decodesPercentEncoding() throws ApiException {
        assertEquals(Optional.of("a~b"), withQuery("start=a%7Eb").queryParam("start"));
    }

    @Test
    void parameterWithoutValueIsEmptyString() throws ApiException {
        assertEquals(Optional.of(""), withQuery("start").queryParam("start"));
        assertEquals(Optional.of(""), withQuery("start=").queryParam("start"));
    }

    @Test
    void repeatedParameterIsRejected() throws ApiException {
        ApiRequest r = withQuery("limit=1&limit=2");

        assertThrows(ApiException.class, () -> r.queryParam("limit"));
    }

    @Test
    void malformedEncodingIsRejected() {
        assertThrows(ApiException.class, () -> withQuery("start=%zz"));
        assertThrows(ApiException.class, () -> withQuery("start=%4"));
    }

    @Test
    void missingQueryAndBodyAreEmpty() throws ApiException {
        ApiRequest r = withQuery(null);

        assertEquals(Optional.empty(), r.queryParam("start"));
        assertEquals(0, r.body().length);
    }

    @Test
    void pathParamMustExistInRoute() throws ApiException {
        ApiRequest r = ApiRequest.of("GET", "/v1/kv/a",
                Map.of("key", "a"), null, null);

        assertEquals("a", r.pathParam("key"));
        assertThrows(IllegalArgumentException.class, () -> r.pathParam("other"));
    }
}
