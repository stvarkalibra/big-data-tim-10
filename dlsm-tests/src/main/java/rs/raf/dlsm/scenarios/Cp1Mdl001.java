package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.harness.*;
import rs.raf.dlsm.model.ReferenceModel;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class Cp1Mdl001 {

    public static void run(long seed, Report report) throws Exception {
        Random random = new Random(seed);
        ReferenceModel model = new ReferenceModel();

        // 1. Pokretanje čvora baze preko NodeController-a
        try (NodeController node = NodeController.startStandalone(8080, 8082)) {
            node.awaitReady(10000);

            ApiClient api = new ApiClient(8080);
            AdminClient admin = new AdminClient(8082);

            int opCount = 1000; // Može se povećati na 10000+ prema planu
            for (int i = 0; i < opCount; i++) {
                String requestId = "CP1-MDL-001-" + seed + "-" + i;
                int choice = random.nextInt(100);

                // Definisanje ključa i vrednosti
                String key = "key-" + random.nextInt(100);

                if (choice < 45) {
                    // PUT (45%)
                    byte[] value = ("val-" + random.nextInt(10000)).getBytes(StandardCharsets.UTF_8);
                    model.put(key, value);
                    HttpResponse<String> resp = api.put(key, value, requestId, 5000);
                    if (resp.statusCode() != 200) {
                        report.fail("PUT_FAILED", "Expected 200, got " + resp.statusCode(), null);
                        return;
                    }
                } else if (choice < 65) {
                    // DELETE (20%)
                    model.delete(key);
                    HttpResponse<String> resp = api.delete(key, requestId, 5000);
                    if (resp.statusCode() != 200 && resp.statusCode() != 404) {
                        report.fail("DELETE_FAILED", "Expected 200 or 404, got " + resp.statusCode(), null);
                        return;
                    }
                } else if (choice < 85) {
                    // GET (20%)
                    byte[] expected = model.get(key);
                    HttpResponse<String> resp = api.get(key, requestId, 5000);

                    if (expected == null) {
                        if (resp.statusCode() != 404) {
                            report.fail("GET_MISMATCH", "Key " + key + " should be absent (404), got " + resp.statusCode(), null);
                            return;
                        }
                    } else {
                        if (resp.statusCode() != 200) {
                            report.fail("GET_MISMATCH", "Key " + key + " should be present (200), got " + resp.statusCode(), null);
                            return;
                        }
                    }
                } else {
                    // Održavanje / Restart / Flush (15%)
                    int action = random.nextInt(3);
                    if (action == 0) {
                        admin.flush();
                    } else if (action == 1) {
                        admin.compact();
                    } else {
                        // Kill -9 i restart nad istim volumenom
                        node.crash();
                        node.restart();
                        node.awaitReady(10000);
                    }
                }
            }

            // Konačna validacija stanja ključeva iz modela
            for (String key : model.keySet()) {
                byte[] expected = model.get(key);
                HttpResponse<String> resp = api.get(key, "CP1-MDL-CHECK-" + key, 5000);
                if (resp.statusCode() != 200) {
                    report.fail("FINAL_VALIDATION", "Key " + key + " missing after operations", null);
                    return;
                }
            }

            report.pass();
        }
    }
}
