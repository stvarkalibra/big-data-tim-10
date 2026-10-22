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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class NodeController implements AutoCloseable {
    /** Env variable with the server jar; the student-tests image sets it (PI §25.2). */
    public static final String SERVER_JAR_ENV = "DLSM_SERVER_JAR";
    /** The jar that {@code ./mvnw package} builds, relative to the repository root. */
    static final String DEFAULT_SERVER_JAR = "dlsm-server/target/dlsm-server-1.0-SNAPSHOT.jar";

    private Process process;
    private final Path dataDir;
    private final int port;
    private final int adminPort;
    private final HttpClient client = HttpClient.newHttpClient();

    public NodeController(Path dataDir, int port, int adminPort) {
        this.dataDir = dataDir;
        this.port = port;
        this.adminPort = adminPort;
    }

    public static NodeController startStandalone(int port, int adminPort) throws IOException {
        Path tempDir = Files.createTempDirectory("dlsm-test-node-");
        NodeController controller = new NodeController(tempDir, port, adminPort);
        controller.start(true);
        return controller;
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
        env.put("DLSM_PUBLIC_PORT", String.valueOf(port));
        env.put("DLSM_ADMIN_PORT", String.valueOf(adminPort));
        env.put("DLSM_DATA_DIR", dataDir.toAbsolutePath().toString());
        env.put("TEST_MODE", String.valueOf(testMode));

        pb.redirectOutput(Redirect.INHERIT);
        pb.redirectError(Redirect.INHERIT);
        this.process = pb.start();
    }

    /**
     * The production server jar: {@code DLSM_SERVER_JAR} when set, otherwise
     * the jar built in this repository.
     *
     * @throws IOException if the jar does not exist, so a missing build fails
     *                     at once instead of as a readiness timeout
     */
    static Path serverJar(String configured) throws IOException {
        Path jar = Path.of(configured == null || configured.isBlank()
                ? DEFAULT_SERVER_JAR : configured);
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Server jar not found: " + jar.toAbsolutePath()
                    + " (build it with ./mvnw package or ./dbctl build, or set "
                    + SERVER_JAR_ENV + ")");
        }
        return jar;
    }

    // Nova metoda za čekanje da server bude spreman
    public void awaitReady(long timeoutMs) throws Exception {
        // Monotonic clock: a wall-clock jump must not stretch or cut the deadline.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        URI uri = URI.create("http://localhost:" + adminPort + "/health/ready");
        while (System.nanoTime() - deadline < 0) {
            if (process != null && !process.isAlive()) {
                // E.g. a taken port or a bad configuration: no point in waiting.
                throw new IllegalStateException("Node exited with code "
                        + process.exitValue() + " before it became ready");
            }
            try {
                HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // Server se još uvek diže
            }
            Thread.sleep(200);
        }
        throw new RuntimeException("Node did not become ready within " + timeoutMs + " ms");
    }

    // Nova metoda za restart
    public void restart() throws IOException {
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

    @Override
    public void close() {
        crash();
    }
}