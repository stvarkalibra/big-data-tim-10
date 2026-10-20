package rs.raf.dlsm.harness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

public class ApiClient {
    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public HttpResponse<String> put(String key, byte[] value, String requestId, long timeoutMs) throws Exception {
        String encodedValue = Base64.getEncoder().encodeToString(value != null ? value : new byte[0]);
        String jsonBody = "{\"value\":\"" + encodedValue + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/kv/" + key))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", requestId)
                .header("X-Request-Timeout-Ms", String.valueOf(timeoutMs))
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String key, String requestId, long timeoutMs) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/kv/" + key))
                .header("X-Request-Id", requestId)
                .header("X-Request-Timeout-Ms", String.valueOf(timeoutMs))
                .GET()
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** {@code GET /v1/scan?start=&end=&limit=} (PI §18.6); keys need no escaping. */
    public HttpResponse<String> scan(String start, String end, int limit,
                                     String requestId, long timeoutMs) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/scan?start=" + start + "&end=" + end
                        + "&limit=" + limit))
                .header("X-Request-Id", requestId)
                .header("X-Request-Timeout-Ms", String.valueOf(timeoutMs))
                .GET()
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> delete(String key, String requestId, long timeoutMs) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/kv/" + key))
                .header("X-Request-Id", requestId)
                .header("X-Request-Timeout-Ms", String.valueOf(timeoutMs))
                .DELETE()
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
