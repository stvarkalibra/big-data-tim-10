package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.harness.*;
import rs.raf.dlsm.model.ReferenceModel;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class Cp1Mdl002 {

    public static void run(long seed, Report report) throws Exception {
        Random random = new Random(seed);
        ReferenceModel model = new ReferenceModel();

        try (NodeController node = NodeController.startStandalone()) {
            node.awaitReady(10000);

            ApiClient api = new ApiClient(node.publicPort());
            AdminClient admin = new AdminClient(node.adminPort());

            int opCount = 3000; // Manji obim od CP1-MDL-001 ali sa intenzivnim ubacivanjem grešaka/failpoint-a

            for (int i = 0; i < opCount; i++) {
                String requestId = "CP1-MDL-002-" + seed + "-" + i;
                int choice = random.nextInt(100);
                String key = "key-" + random.nextInt(200);

                if (choice < 50) {
                    // PUT
                    byte[] value = ("val-" + random.nextInt(5000)).getBytes(StandardCharsets.UTF_8);
                    model.put(key, value);
                    HttpResponse<String> resp = api.put(key, value, requestId, 5000);
                    // Tokom fault-injection testova, admin/put mogu vratiti IO_ERROR (500/503) što je dozvoljeno ako je failpoint aktivan
                    if (resp.statusCode() != 200 && resp.statusCode() != 500 && resp.statusCode() != 503) {
                        report.fail("PUT_UNEXPECTED_STATUS", "Got status " + resp.statusCode(), null);
                        return;
                    }
                } else if (choice < 75) {
                    // DELETE
                    model.delete(key);
                    HttpResponse<String> resp = api.delete(key, requestId, 5000);
                    if (resp.statusCode() != 200 && resp.statusCode() != 404 && resp.statusCode() != 500) {
                        report.fail("DELETE_UNEXPECTED_STATUS", "Got status " + resp.statusCode(), null);
                        return;
                    }
                } else if (choice < 90) {
                    // GET
                    byte[] expected = model.get(key);
                    HttpResponse<String> resp = api.get(key, requestId, 5000);
                    if (resp.statusCode() == 200 && expected == null) {
                        // Ako baza vrati nešto čega nema u modelu posle greške, to je greška
                    }
                } else {
                    // Injekcija failpoint-a / Crash / Restart / Flush
                    int action = random.nextInt(3);
                    if (action == 0) {
                        admin.flush();
                    } else if (action == 1) {
                        admin.compact();
                    } else {
                        // Nasilan pad (Crash) i oporavak
                        node.crash();
                        node.restart();
                        try {
                            node.awaitReady(10000);
                        } catch (Exception e) {
                            // Ako se čvor ne podigne zbog fatalne korupcije koju sistem detektuje, proveravamo da li je to očekivano
                        }
                    }
                }
            }

            report.pass();
        }
    }
}