package rs.raf.dlsm.server.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.common.UncheckedStorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.log.JsonLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a {@link Router} on the JDK {@code HttpServer}. This is the only
 * class that touches the HTTP library; everything else uses
 * {@link ApiRequest} and {@link ApiResponse}.
 *
 * <p>For every request it: parses the shared headers, routes, reads the
 * bounded body, calls the handler and turns every failure into the shared
 * error envelope without stack traces or paths (PI §17.4). Each request
 * runs on its own virtual thread, so handlers may block (backpressure,
 * PAUSE failpoints) without starving other requests.
 */
public final class HttpServerHost implements AutoCloseable {

    /**
     * Largest accepted body. A maximal PUT is 262 144 bytes, which is
     * 349 528 Base64 characters plus a few bytes of JSON; anything above
     * this limit is rejected before it is fully read.
     */
    public static final int MAX_BODY_BYTES = 512 * 1024;

    /**
     * {@code TCP_NODELAY} on every accepted connection, a documented property
     * of the JDK server (module {@code jdk.httpserver}, default
     * {@code false}). A response leaves in more than one write, so with
     * Nagle's algorithm its last part waits for the client's delayed ACK:
     * about 40 ms per request (CP1-MDL-001 took 734 s instead of 47 s). The
     * JDK reads it once, when its first server is created, so it is set
     * before any server exists; an explicit {@code -D} setting wins.
     */
    static final String NO_DELAY_PROPERTY = "sun.net.httpserver.nodelay";

    static {
        if (System.getProperty(NO_DELAY_PROPERTY) == null) {
            System.setProperty(NO_DELAY_PROPERTY, "true");
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Router router;
    private final boolean requireRequestId;
    private final PartitionRef storageErrorPartition;
    private final JsonLog log;

    private HttpServerHost(InetSocketAddress address, Router router,
                           boolean requireRequestId,
                           PartitionRef storageErrorPartition,
                           JsonLog log) throws IOException {
        this.router = Objects.requireNonNull(router, "router");
        this.requireRequestId = requireRequestId;
        this.storageErrorPartition = storageErrorPartition;
        this.log = Objects.requireNonNull(log, "log");

        this.server = HttpServer.create(address, 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    /**
     * Public API server (port 8080): {@code X-Request-Id} is required and
     * storage errors carry the standalone partition context.
     */
    public static HttpServerHost publicApi(InetSocketAddress address,
                                           Router router, JsonLog log)
            throws IOException {
        return new HttpServerHost(address, router, true,
                PartitionRef.STANDALONE, log);
    }

    /**
     * Admin/test server (port 8082): {@code X-Request-Id} is optional and
     * errors carry no partition context.
     */
    public static HttpServerHost admin(InetSocketAddress address,
                                       Router router, JsonLog log)
            throws IOException {
        return new HttpServerHost(address, router, false, null, log);
    }

    public void start() {
        server.start();
    }

    /** Bound port; useful when the server was created on port 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        String requestId = null;
        ApiResponse response;
        Error fatal = null;

        try {
            Headers headers = exchange.getRequestHeaders();
            requestId = RequestContext.requestId(
                    headers.get(RequestContext.REQUEST_ID_HEADER),
                    requireRequestId);
            long timeoutMillis = RequestContext.timeoutMillis(
                    headers.get(RequestContext.TIMEOUT_HEADER));
            // The budget starts when the request arrives (PI §17.3).
            OpContext ctx = OpContext.withTimeout(requestId, timeoutMillis);

            URI uri = exchange.getRequestURI();
            String method = exchange.getRequestMethod();
            String path = uri.getRawPath();

            response = switch (router.match(method, path)) {
                case Router.NotFound notFound -> ApiResponse.error(
                        ErrorCode.NOT_FOUND, "Unknown route", requestId, null);
                case Router.MethodNotAllowed notAllowed -> new ApiResponse(
                        405,
                        ErrorEnvelope.body(ErrorCode.INVALID_REQUEST,
                                "Method not allowed", requestId, null),
                        Map.of("Allow", String.join(", ",
                                notAllowed.allowedMethods())));
                case Router.Found found -> found.handler().handle(
                        ApiRequest.of(method, path, found.pathParams(),
                                uri.getRawQuery(), readBody(exchange)),
                        ctx);
            };
        } catch (ApiException e) {
            response = ApiResponse.error(
                    e.code(), e.getMessage(), requestId, e.partition());
        } catch (StorageException e) {
            response = ApiResponse.error(
                    e.code(), e.getMessage(), requestId, storageErrorPartition);
        } catch (UncheckedStorageException e) {
            // A storage error carried out of code that cannot throw checked
            // exceptions (e.g. a Supplier): answer with its real code.
            StorageException cause = e.getCause();
            response = ApiResponse.error(
                    cause.code(), cause.getMessage(), requestId, storageErrorPartition);
        } catch (RuntimeException | Error e) {
            logFailure("HTTP_INTERNAL_ERROR", requestId, e);
            response = ApiResponse.error(ErrorCode.INTERNAL_ERROR,
                    "Internal error", requestId, null);
            if (e instanceof Error error) {
                fatal = error;
            }
        }

        try {
            write(exchange, response, requestId);
        } catch (IOException | RuntimeException e) {
            // The client went away or the response could not be encoded;
            // nothing more can be sent on this exchange.
            logFailure("HTTP_RESPONSE_WRITE_FAILED", requestId, e);
        } finally {
            // Always close, even on an Error: an unclosed exchange leaves
            // the client waiting forever.
            exchange.close();
        }

        if (fatal != null) {
            // The client got its 500; the Error itself is not ours to swallow.
            throw fatal;
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws ApiException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new ApiException(ErrorCode.VALUE_TOO_LARGE,
                        "Request body too large");
            }
            return body;
        } catch (IOException e) {
            throw ApiException.invalid("Cannot read request body");
        }
    }

    private static void write(HttpExchange exchange, ApiResponse response,
                              String requestId) throws IOException {
        byte[] bytes = Json.write(response.body());

        Headers out = exchange.getResponseHeaders();
        out.set("Content-Type", "application/json; charset=utf-8");
        out.set("Cache-Control", "no-store");
        if (requestId != null) {
            out.set(RequestContext.REQUEST_ID_HEADER, requestId);
        }
        response.headers().forEach(out::set);

        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private void logFailure(String event, String requestId, Throwable e) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("requestId", requestId);
        fields.put("errorCode", ErrorCode.INTERNAL_ERROR.name());
        fields.put("message", e.getClass().getName() + ": " + e.getMessage());
        log.error(event, fields);
    }
}
