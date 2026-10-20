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

public class NodeController implements AutoCloseable {
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
        String jarPath = "dlsm-server/target/dlsm-server-1.0-SNAPSHOT.jar";

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Dserver.port=" + port);
        command.add("-Dadmin.port=" + adminPort);
        command.add("-Ddata.dir=" + dataDir.toAbsolutePath().toString());
        command.add("-Dtest.mode=" + testMode);
        command.add("-jar");
        command.add(jarPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(Redirect.INHERIT);
        pb.redirectError(Redirect.INHERIT);
        this.process = pb.start();
    }

    // Nova metoda za čekanje da server bude spreman
    public void awaitReady(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        URI uri = URI.create("http://localhost:" + adminPort + "/health/ready");
        while (System.currentTimeMillis() < deadline) {
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