package rs.raf.dlsm.server.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps method + path to a handler. Patterns are literal segments and
 * {@code {name}} placeholders, e.g. {@code /v1/kv/{key}}; a placeholder
 * matches exactly one non-empty segment. Matching is exact: no trailing
 * slash, no prefix matching.
 */
public final class Router {

    public sealed interface Match {
    }

    public record Found(ApiHandler handler, Map<String, String> pathParams)
            implements Match {
    }

    /** The path exists, but not for this method. */
    public record MethodNotAllowed(Set<String> allowedMethods) implements Match {
    }

    public record NotFound() implements Match {
    }

    private record Route(String method, String[] segments, ApiHandler handler) {
    }

    private final List<Route> routes = new ArrayList<>();

    public Router add(String method, String pattern, ApiHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        String[] segments = split(Objects.requireNonNull(pattern, "pattern"));

        for (Route route : routes) {
            if (route.method.equals(method)
                    && samePattern(route.segments, segments)) {
                throw new IllegalStateException(
                        "Duplicate route: " + method + " " + pattern);
            }
        }

        routes.add(new Route(method, segments, handler));
        return this;
    }

    public Match match(String method, String path) {
        String[] actual = split(path);
        Set<String> allowed = new TreeSet<>();

        for (Route route : routes) {
            Map<String, String> params = bind(route.segments, actual);
            if (params == null) {
                continue;
            }
            if (route.method.equals(method)) {
                return new Found(route.handler, params);
            }
            allowed.add(route.method);
        }

        return allowed.isEmpty()
                ? new NotFound()
                : new MethodNotAllowed(Set.copyOf(allowed));
    }

    private static Map<String, String> bind(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) {
            return null;
        }

        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (isPlaceholder(p)) {
                if (actual[i].isEmpty()) {
                    return null;
                }
                params.put(p.substring(1, p.length() - 1), actual[i]);
            } else if (!p.equals(actual[i])) {
                return null;
            }
        }
        return params;
    }

    private static boolean samePattern(String[] a, String[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            boolean bothPlaceholders = isPlaceholder(a[i]) && isPlaceholder(b[i]);
            if (!bothPlaceholders && !a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlaceholder(String segment) {
        return segment.length() > 2
                && segment.startsWith("{") && segment.endsWith("}");
    }

    private static String[] split(String path) {
        if (!path.startsWith("/")) {
            return new String[] {path};
        }
        // Keep trailing empty segments so "/a/" never matches "/a".
        return path.substring(1).split("/", -1);
    }
}
