package rs.raf.dlsm.harness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class AdminClient {
    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    public AdminClient(int adminPort) {
        this.baseUrl = "http://localhost:" + adminPort;
    }

    public HttpResponse<String> flush() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/storage/flush"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> compact() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/storage/compact"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> setFailpoint(String token, String name, String action, int afterHits, int maxTriggers, long pauseTimeoutMs) throws Exception {
        String jsonBody = String.format(
                "{\"name\":\"%s\",\"action\":\"%s\",\"afterHits\":%d,\"maxTriggers\":%d,\"pauseTimeoutMs\":%d}",
                name, action, afterHits, maxTriggers, pauseTimeoutMs
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/failpoints/" + token))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Arms a rule with a raw JSON body, e.g. with {@code filters} or {@code errorCode}. */
    public HttpResponse<String> setFailpoint(String token, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/failpoints/" + token))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** {@code state} in the body: ARMED, TRIGGERED, RELEASED, EXHAUSTED or UNKNOWN. */
    public HttpResponse<String> getFailpoint(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/failpoints/" + token))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> deleteFailpoint(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/failpoints/" + token))
                .DELETE()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Removes every rule and releases every pause; call it in scenario cleanup. */
    public HttpResponse<String> clearFailpoints() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/failpoints"))
                .DELETE()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> releaseFailpoint(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/failpoints/" + token + "/release"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> getFailpointCatalog() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/v1/failpoints/catalog"))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}