package rs.raf.dlsm.harness;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

public class NodeController implements AutoCloseable {
    public static final String SERVER_JAR_ENV = "DLSM_SERVER_JAR";
    static final String DEFAULT_SERVER_JAR = "dlsm-server/target/dlsm-server-1.0-SNAPSHOT.jar";

    private Process process;
    private final Path dataDir;
    private final int port;
    private final int adminPort;
    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<String, String> environment = new LinkedHashMap<>();

    public NodeController(Path dataDir, int port, int adminPort) {
        this.dataDir = dataDir;
        this.port = port;
        this.adminPort = adminPort;
    }

    public static NodeController startStandalone() throws IOException {
        return startStandalone(18080, 18082);
    }

    public static NodeController startStandalone(int port, int adminPort) throws IOException {
        Path tempDir = Files.createTempDirectory("dlsm-test-node-");
        NodeController controller = new NodeController(tempDir, port, adminPort);
        controller.start(true);
        return controller;
    }

    public int publicPort() {
        return port;
    }

    public int adminPort() {
        return adminPort;
    }

    public NodeController withEnvironment(String name, String value) {
        environment.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return this;
    }

    public NodeController withoutEnvironment(String name) {
        environment.remove(Objects.requireNonNull(name, "name"));
        return this;
    }

    public void start(boolean testMode) throws IOException {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        Path jar = serverJar(System.getenv(SERVER_JAR_ENV));

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-jar");
        command.add(jar.toString());

        ProcessBuilder pb = new ProcessBuilder(command);

        Map<String, String> env = pb.environment();
        env.putAll(environment);
        env.put("DLSM_PUBLIC_PORT", String.valueOf(port));
        env.put("DLSM_ADMIN_PORT", String.valueOf(adminPort));
        env.put("DLSM_DATA_DIR", dataDir.toAbsolutePath().toString());
        env.put("TEST_MODE", String.valueOf(testMode));

        pb.redirectOutput(Redirect.INHERIT);
        pb.redirectError(Redirect.INHERIT);
        this.process = pb.start();
    }

    static Path serverJar(String configured) throws IOException {
        Path jar = Path.of(configured == null || configured.isBlank() ? DEFAULT_SERVER_JAR : configured);
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Server jar not found: " + jar.toAbsolutePath()
                    + " (build it with ./mvnw package or ./dbctl build, or set "
                    + SERVER_JAR_ENV + ")");
        }
        return jar;
    }

    public void awaitReady(long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        URI uri = URI.create("http://localhost:" + adminPort + "/health/ready");
        while (System.nanoTime() - deadline < 0) {
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException("Node exited with code " + process.exitValue() + " before it became ready");
            }
            try {
                HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(200);
        }
        throw new RuntimeException("Node did not become ready within " + timeoutMs + " ms");
    }

    public void restart() throws IOException {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        start(true);
    }
    public void crash() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Exit code of the last process; empty before a start and while it runs. */
    public OptionalInt exitCode() {
        return process == null || process.isAlive()
                ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    @Override
    public void close() {
        crash();
    }
    public Path dataDir() {
        return dataDir;
    }
    public boolean awaitExit(long timeoutMs) {
        if (process == null) {
            return true;
        }
        try {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    public boolean awaitLifecycle(String expectedLifecycle, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (checkLifecycleState(expectedLifecycle)) {
                    return true;
                }
            } catch (Exception e) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
    private boolean checkLifecycleState(String expectedLifecycle) throws Exception {
        URI uri = URI.create("http://localhost:" + adminPort + "/health/ready");
        HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (expectedLifecycle.equalsIgnoreCase("READY") && response.statusCode() == 200) {
            return true;
        }
        return response.body() != null && response.body().contains(expectedLifecycle);
    }
}