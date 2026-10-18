package rs.raf.dlsm.server.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-independent view of one HTTP request. Handlers see only this type,
 * never the underlying server's exchange, so the HTTP library can be
 * replaced without touching any handler.
 *
 * <p>Path parameters are taken from the raw path and are not
 * percent-decoded: every valid key is made of unreserved characters, so a
 * {@code %} can only belong to an invalid key.
 */
public final class ApiRequest {

    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, List<String>> query;
    private final byte[] body;

    private ApiRequest(String method, String path,
                       Map<String, String> pathParams,
                       Map<String, List<String>> query, byte[] body) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.query = query;
        this.body = body;
    }

    /**
     * @param rawQuery the undecoded query string, or {@code null}
     * @throws ApiException {@code INVALID_REQUEST} for a malformed query
     */
    public static ApiRequest of(String method, String path,
                                Map<String, String> pathParams,
                                String rawQuery, byte[] body)
            throws ApiException {
        return new ApiRequest(
                Objects.requireNonNull(method, "method"),
                Objects.requireNonNull(path, "path"),
                Map.copyOf(pathParams),
                parseQuery(rawQuery),
                body == null ? new byte[0] : body);
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    /** Value of a {@code {name}} segment of the matched route pattern. */
    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Route has no parameter " + name);
        }
        return value;
    }

    /**
     * @return the decoded parameter, empty when absent
     * @throws ApiException {@code INVALID_REQUEST} when repeated
     */
    public Optional<String> queryParam(String name) throws ApiException {
        List<String> values = query.get(name);
        if (values == null) {
            return Optional.empty();
        }
        if (values.size() > 1) {
            throw ApiException.invalid("Repeated query parameter: " + name);
        }
        return Optional.of(values.get(0));
    }

    /** Raw body bytes; empty, never {@code null}. Not copied: do not modify. */
    public byte[] body() {
        return body;
    }

    private static Map<String, List<String>> parseQuery(String rawQuery)
            throws ApiException {

        Map<String, List<String>> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }

        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }

            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));

            result.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static String decode(String text) throws ApiException {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("Malformed query string");
        }
    }
}
