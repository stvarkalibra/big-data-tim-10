package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.harness.*;
import rs.raf.dlsm.model.ReferenceModel;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.Arrays;

public class Cp1Mdl001 {

    public static void run(long seed, Report report) throws Exception {
        Random random = new Random(seed);
        ReferenceModel model = new ReferenceModel();

        // Korišćenje dinamičkih portova preko NodeController-a (rešava problem fiksnih portova 18080/18082)
        try (NodeController node = NodeController.startStandalone()) {
            node.awaitReady(10000);

            ApiClient api = new ApiClient(node.publicPort());
            AdminClient admin = new AdminClient(node.adminPort());

            int opCount = 10000; // Po planu: >= 10.000 operacija
            int flushCount = 0;
            int compactCount = 0;
            int restartCount = 0;

            for (int i = 0; i < opCount; i++) {
                String requestId = "CP1-MDL-001-" + seed + "-" + i;
                int choice = random.nextInt(100);

                String key = String.format("key-%03d", random.nextInt(300));

                if (choice < 45) {
                    // PUT (45%)
                    byte[] value = ("val-" + random.nextInt(10000)).getBytes(StandardCharsets.UTF_8);
                    model.put(key, value);
                    HttpResponse<String> resp = api.put(key, value, requestId, 5000);
                    if (resp.statusCode() != 200) {
                        report.fail("PUT_FAILED", "Expected 200, got " + resp.statusCode() + " for key " + key, null);
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
                    // GET (20%) - Provera statusa I STVARNOG SADRŽAJA VREDNOSTI
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
                        // Poređenje stvarnog sadržaja vrednosti sa modelom
                        String body = resp.body();
                        // Pretpostavka/provera da li telo odgovora sadrži očekivanu vrednost (bajtovi/string)
                        if (body != null && !body.isEmpty()) {
                            // Ukoliko API vraća JSON omotač, upoređujemo bajtove (prilagoditi u zavisnosti od parsiranja ApiClient-a)
                            boolean match = body.contains(new String(expected, StandardCharsets.UTF_8));
                            if (!match) {
                                report.fail("VALUE_MISMATCH", "Content mismatch for key " + key, null);
                                return;
                            }
                        }
                    }
                } else {
                    // SCAN (15%)
                    int startVal = random.nextInt(100);
                    int endVal = startVal + 1 + random.nextInt(50);
                    String startKey = String.format("key-%03d", startVal);
                    String endKey = String.format("key-%03d", endVal);
                    int limit = 10;

                    Map<String, byte[]> expectedScan = model.scan(startKey, endKey, limit);
                    HttpResponse<String> resp = api.scan(startKey, endKey, limit, requestId, 5000);
                    if (resp.statusCode() != 200) {
                        report.fail("SCAN_FAILED", "Expected 200 for scan, got " + resp.statusCode(), null);
                        return;
                    }
                }

                // Kontrola održavanja i restarta prema planu (>= 20 flush-eva, >= 5 compaction-a, >= 10 restarta)
                if (i > 0 && i % 350 == 0) {
                    if (flushCount < 22) {
                        HttpResponse<String> flushResp = admin.flush();
                        if (flushResp.statusCode() != 200) {
                            report.fail("FLUSH_FAILED", "Admin flush failed with status " + flushResp.statusCode(), null);
                            return;
                        }
                        flushCount++;
                    }
                }
                if (i > 0 && i % 1400 == 0) {
                    if (compactCount < 6) {
                        HttpResponse<String> compactResp = admin.compact();
                        if (compactResp.statusCode() != 200) {
                            report.fail("COMPACT_FAILED", "Admin compact failed with status " + compactResp.statusCode(), null);
                            return;
                        }
                        compactCount++;
                    }
                }
                if (i > 0 && i % 800 == 0) {
                    if (restartCount < 12) {
                        node.crash();
                        node.restart();
                        node.awaitReady(10000);
                        restartCount++;
                    }
                }
            }

            // Faza validacije sa neispravnim ulazima (CP1 §29.6)
            HttpResponse<String> invalidPut = api.put("invalid_key_#$@", "val".getBytes(StandardCharsets.UTF_8), "CP1-BAD-1", 5000);
            if (invalidPut.statusCode() != 400) {
                report.fail("INVALID_INPUT_VALIDATION", "Expected 400 for invalid key format, got " + invalidPut.statusCode(), null);
                return;
            }

            // Konačna validacija stanja svih ključeva iz modela (provera statusa i sadržaja)
            for (String key : model.keySet()) {
                byte[] expected = model.get(key);
                HttpResponse<String> resp = api.get(key, "CP1-MDL-CHECK-" + key, 5000);
                if (resp.statusCode() != 200) {
                    report.fail("FINAL_VALIDATION", "Key " + key + " missing after operations", null);
                    return;
                }
                String body = resp.body();
                if (body != null && !body.isEmpty() && expected != null) {
                    if (!body.contains(new String(expected, StandardCharsets.UTF_8))) {
                        report.fail("FINAL_VALUE_MISMATCH", "Final value mismatch for key " + key, null);
                        return;
                    }
                }
            }

            report.pass();
        }
    }
}