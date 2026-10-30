package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.harness.*;
import rs.raf.dlsm.inspect.InspectManifest;
import rs.raf.dlsm.inspect.InspectWal;
import rs.raf.dlsm.model.ReferenceModel;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class Cp1Mdl001 {
    public static final String ID = "CP1-MDL-001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void run(long seed, Report report) throws Exception {
        Random random = new Random(seed);
        ReferenceModel model = new ReferenceModel();
        Trace trace = new Trace();

        NodeController node = new NodeController(Files.createTempDirectory("dlsm-mdl001-"), 18610, 18611)
                .withEnvironment("MEMTABLE_MAX_BYTES", "65536");

        try {
            node.start(true);
            node.awaitReady(10000);

            ApiClient api = new ApiClient(node.publicPort());
            AdminClient admin = new AdminClient(node.adminPort());

            int opCount = 10000;
            int publishedFlushCount = 0;
            int publishedCompactCount = 0;
            int restartCount = 0;
            int manualCompactCount = 0;
            int accumulatedCompactionPubs = 0; // Akumulirane kompekcije pre svakog pada

            int keySpaceSize = 512;

            for (int i = 0; i < opCount; i++) {
                String requestId = RequestIds.of(ID, seed, i);
                int choice = random.nextInt(100);
                String key = String.format("key-%03d", random.nextInt(keySpaceSize));

                if (choice < 45) {
                    // PUT (45%)
                    byte[] value;
                    int vType = random.nextInt(5);
                    if (vType == 0) {
                        value = new byte[0];
                    } else if (vType == 1) {
                        value = new byte[100];
                        random.nextBytes(value);
                    } else if (vType == 2) {
                        value = "ponovljena-vrednost-abc".getBytes(StandardCharsets.UTF_8);
                    } else if (vType == 3) {
                        value = new byte[random.nextInt(500) + 1];
                        Arrays.fill(value, (byte) 'x');
                    } else {
                        value = ("val-" + random.nextInt(10000)).getBytes(StandardCharsets.UTF_8);
                    }

                    HttpResponse<String> resp = api.put(key, value, requestId, 5000);
                    trace.record(requestId, "PUT", resp.statusCode(), 0, "key=" + key);

                    if (resp.statusCode() != 200) {
                        report.fail("PUT_FAILED", "op " + i + " expected 200, got " + resp.statusCode() + " for key " + key +
                                ". Last 100 operations: " + trace.getEntries(), null);
                        return;
                    }
                    model.put(key, value);

                } else if (choice < 65) {
                    // DELETE (20%)
                    HttpResponse<String> resp = api.delete(key, requestId, 5000);
                    trace.record(requestId, "DELETE", resp.statusCode(), 0, "key=" + key);

                    if (resp.statusCode() != 200) {
                        report.fail("DELETE_FAILED", "op " + i + " expected 200, got " + resp.statusCode() +
                                ". Last 100 operations: " + trace.getEntries(), null);
                        return;
                    }
                    model.delete(key);

                } else if (choice < 85) {
                    // GET (20%)
                    byte[] expected = model.get(key);
                    HttpResponse<String> resp = api.get(key, requestId, 5000);
                    trace.record(requestId, "GET", resp.statusCode(), 0, "key=" + key);

                    if (expected == null) {
                        if (resp.statusCode() != 404) {
                            report.fail("GET_MISMATCH", "op " + i + " key " + key + " should be absent (404), got " + resp.statusCode() +
                                    ". Last 100 operations: " + trace.getEntries(), null);
                            return;
                        }
                    } else {
                        if (resp.statusCode() != 200) {
                            report.fail("GET_MISMATCH", "op " + i + " key " + key + " should be present (200), got " + resp.statusCode() +
                                    ". Last 100 operations: " + trace.getEntries(), null);
                            return;
                        }
                        JsonNode json = MAPPER.readTree(resp.body());
                        byte[] actual = Base64.getDecoder().decode(json.path("value").asText());
                        if (!Arrays.equals(expected, actual)) {
                            report.fail("VALUE_MISMATCH", "op " + i + " key " + key + " content mismatch. Last 100 operations: " + trace.getEntries(), null);
                            return;
                        }
                    }

                } else {
                    // SCAN (15%)
                    int startVal = random.nextInt(keySpaceSize - 10);
                    int endVal = Math.min(keySpaceSize, startVal + random.nextInt(50) + 1);
                    String startKey = String.format("key-%03d", startVal);
                    String endKey = String.format("key-%03d", endVal);
                    int limit = random.nextInt(50) + 1;

                    Map<String, byte[]> expectedScan = model.scan(startKey, endKey, limit);
                    HttpResponse<String> resp = api.scan(startKey, endKey, limit, requestId, 5000);
                    trace.record(requestId, "SCAN", resp.statusCode(), 0, "start=" + startKey + ", end=" + endKey);

                    if (resp.statusCode() != 200) {
                        report.fail("SCAN_FAILED", "op " + i + " expected 200 for scan, got " + resp.statusCode(), null);
                        return;
                    }

                    JsonNode root = MAPPER.readTree(resp.body());
                    int count = root.path("count").asInt();
                    JsonNode items = root.path("items");

                    if (count != expectedScan.size()) {
                        report.fail("SCAN_COUNT_MISMATCH", "op " + i + " expected count " + expectedScan.size() + ", got " + count, null);
                        return;
                    }

                    int idx = 0;
                    for (Map.Entry<String, byte[]> entry : expectedScan.entrySet()) {
                        JsonNode item = items.get(idx++);
                        String actualKey = item.path("key").asText();
                        byte[] actualVal = Base64.getDecoder().decode(item.path("value").asText());
                        if (!actualKey.equals(entry.getKey()) || !Arrays.equals(entry.getValue(), actualVal)) {
                            report.fail("SCAN_ITEM_MISMATCH", "op " + i + " mismatch for key " + entry.getKey(), null);
                            return;
                        }
                    }
                }

                // Održavanje i provere
                if (i > 0 && i % 300 == 0) {
                    HttpResponse<String> flushResp = admin.flush();
                    if (flushResp.statusCode() == 200) {
                        JsonNode j = MAPPER.readTree(flushResp.body());
                        if ("PUBLISHED".equals(j.path("result").asText())) {
                            publishedFlushCount++;
                        }
                    }
                }
                if (i > 0 && i % 1200 == 0) {
                    HttpResponse<String> compactResp = admin.compact();
                    if (compactResp.statusCode() == 200) {
                        JsonNode j = MAPPER.readTree(compactResp.body());
                        if ("PUBLISHED".equals(j.path("result").asText())) {
                            publishedCompactCount++;
                            manualCompactCount++;
                        }
                    }
                }
                if (i > 0 && i % 900 == 0) {
                    // Sabiramo statistiku pre nego što čvor padne jer se brojači resetuju restartom
                    try {
                        JsonNode stBeforeCrash = MAPPER.readTree(admin.storageStatus().body());
                        accumulatedCompactionPubs += stBeforeCrash.path("stats").path("compactionPublications").asInt();
                    } catch (Exception ignored) {}

                    node.crash();
                    node.restart();
                    node.awaitReady(10000);
                    restartCount++;

                    // Provera GET i SCAN posle restarta
                    for (int kIdx = 0; kIdx < keySpaceSize; kIdx++) {
                        String testKey = String.format("key-%03d", kIdx);
                        byte[] exp = model.get(testKey);
                        HttpResponse<String> r = api.get(testKey, RequestIds.of(ID, seed, i + 99999 + kIdx), 5000);
                        if (exp == null) {
                            if (r.statusCode() != 404) {
                                report.fail("POST_RESTART_GET_404", "Key " + testKey + " should be 404 after restart, got " + r.statusCode(), null);
                                return;
                            }
                        } else {
                            if (r.statusCode() != 200) {
                                report.fail("POST_RESTART_GET_200", "Key " + testKey + " should be 200 after restart, got " + r.statusCode(), null);
                                return;
                            }
                            byte[] act = Base64.getDecoder().decode(MAPPER.readTree(r.body()).path("value").asText());
                            if (!Arrays.equals(exp, act)) {
                                report.fail("POST_RESTART_VALUE_MISMATCH", "Value mismatch after restart for key " + testKey, null);
                                return;
                            }
                        }
                    }
                    Map<String, byte[]> postRestartScan = model.scan("key-000", String.format("key-%03d", keySpaceSize), 100);
                    HttpResponse<String> scanResp = api.scan("key-000", String.format("key-%03d", keySpaceSize), 100, RequestIds.of(ID, seed, i + 8888), 5000);
                    if (scanResp.statusCode() != 200) {
                        report.fail("POST_RESTART_SCAN_FAILED", "Scan failed after restart", null);
                        return;
                    }
                }
            }

            // Čekanje da se stabilizuje pozadinsko održavanje pre očitavanja statusa
            Await.until(() -> {
                try {
                    JsonNode st = MAPPER.readTree(admin.storageStatus().body());
                    String phase = st.path("maintenance").path("phase").asText("IDLE");
                    boolean immNull = st.path("immutableMemtable").isMissingNode() || st.path("immutableMemtable").isNull();
                    return "IDLE".equals(phase) && immNull;
                } catch (Exception e) {
                    return false;
                }
            }, java.time.Duration.ofMillis(500), "Maintenance state timeout");

            // Faza validacije sa neispravnim ulazima (CP1 §29.6)
            HttpResponse<String> stBefore = admin.storageStatus();
            JsonNode sb = MAPPER.readTree(stBefore.body());
            long seqBefore = sb.path("nextSequence").asLong();
            long walBefore = sb.path("stats").path("walBytesWritten").asLong();
            long memGenBefore = sb.path("activeMemtable").path("generation").asLong();
            long manGenBefore = sb.path("manifest").path("generation").asLong();

            // 1. Predugačak ključ (129 znakova)
            String longKey = "k".repeat(129);
            HttpResponse<String> badKey1 = api.put(longKey, "val".getBytes(StandardCharsets.UTF_8), "CP1-BAD-1", 5000);

            // 2. Nevažeći znakovi u ključu
            HttpResponse<String> badKey2 = api.put("bad$key", "val".getBytes(StandardCharsets.UTF_8), "CP1-BAD-2", 5000);

            // 3. Vrednost veća od 256 KiB
            byte[] hugeVal = new byte[262145];
            HttpResponse<String> hugePut = api.put("key-001", hugeVal, "CP1-BAD-3", 5000);

            // 4. Loše granice i limit SCAN-a
            HttpResponse<String> badScan1 = api.scan("key-000", "key-010", 0, "CP1-BAD-4", 5000);
            HttpResponse<String> badScan2 = api.scan("key-000", "key-010", 1001, "CP1-BAD-5", 5000);

            if (badKey1.statusCode() != 400 || badKey2.statusCode() != 400 || hugePut.statusCode() != 400 ||
                    badScan1.statusCode() != 400 || badScan2.statusCode() != 400) {
                report.fail("VALIDATION_INPUTS_FAILED", "Expected 400 for invalid inputs, got statuses: " +
                        badKey1.statusCode() + ", " + badKey2.statusCode() + ", " + hugePut.statusCode() + ", " +
                        badScan1.statusCode() + ", " + badScan2.statusCode(), null);
                return;
            }

            HttpResponse<String> stAfter = admin.storageStatus();
            JsonNode sa = MAPPER.readTree(stAfter.body());
            if (sa.path("nextSequence").asLong() != seqBefore ||
                    sa.path("stats").path("walBytesWritten").asLong() != walBefore ||
                    sa.path("activeMemtable").path("generation").asLong() != memGenBefore ||
                    sa.path("manifest").path("generation").asLong() != manGenBefore) {
                report.fail("VALIDATION_STATE_MUTATED", "Validation requests mutated storage state!", null);
                return;
            }

            // Konačna provera celog prostora ključeva
            for (int kIdx = 0; kIdx < keySpaceSize; kIdx++) {
                String key = String.format("key-%03d", kIdx);
                byte[] expected = model.get(key);
                HttpResponse<String> resp = api.get(key, "CP1-MDL-FINAL-" + key, 5000);
                if (expected == null) {
                    if (resp.statusCode() != 404) {
                        report.fail("FINAL_DELETED_CHECK", "Key " + key + " should be absent (404), got " + resp.statusCode(), null);
                        return;
                    }
                } else {
                    if (resp.statusCode() != 200) {
                        report.fail("FINAL_VALIDATION", "Key " + key + " missing after operations (got " + resp.statusCode() + ")", null);
                        return;
                    }
                    byte[] act = Base64.getDecoder().decode(MAPPER.readTree(resp.body()).path("value").asText());
                    if (!Arrays.equals(expected, act)) {
                        report.fail("FINAL_VALUE_MISMATCH", "Final value mismatch for key " + key, null);
                        return;
                    }
                }
            }

            // Sabiramo konačne kompekcije iz poslednjeg aktivnog stanja
            int totalCompactionPubs = accumulatedCompactionPubs + sa.path("stats").path("compactionPublications").asInt();
            int autoCompactions = totalCompactionPubs - manualCompactCount;

            // Provera ukupne pokrivenosti na kraju
            if (publishedFlushCount < 20 || publishedCompactCount < 5 || autoCompactions < 1 || restartCount < 11) {
                report.fail("COVERAGE_METRICS_FAILED", String.format("Metrics not met: flushes=%d (>=20), manualCompacts=%d (>=5), autoCompacts=%d (>=1), restarts=%d (>=11)",
                        publishedFlushCount, publishedCompactCount, autoCompactions, restartCount), null);
                return;
            }

            // Završni offline inspektori
            node.crash();
            int walInspCode = InspectWal.run(node.dataDir(), System.out);
            int manInspCode = InspectManifest.run(node.dataDir(), System.out);
            if (walInspCode != 0 || manInspCode != 0) {
                report.fail("OFFLINE_INSPECTOR_FAILED", "Inspectors failed with codes wal=" + walInspCode + ", manifest=" + manInspCode, null);
                return;
            }

            report.pass();
        } finally {
            try {
                node.close();
                if (node.dataDir() != null) {
                    VolumeTools.deleteDirectory(node.dataDir());
                }
            } catch (Exception ignored) {}
        }
    }
}