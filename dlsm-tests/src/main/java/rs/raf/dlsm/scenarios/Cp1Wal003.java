package rs.raf.dlsm.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import rs.raf.dlsm.fixtures.CorruptionTools;
import rs.raf.dlsm.harness.Report;
import rs.raf.dlsm.harness.VolumeTools;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * CP1-WAL-003 — corruption of a complete WAL frame (CP1 §29.2).
 *
 * <ol>
 *   <li><b>Initial state:</b> a prepared volume: three acknowledged PUTs that
 *       exist only in the WAL (no rotation, no flush), then {@code SIGKILL}.
 *       The target is the first or the second frame (from the seed), so a
 *       complete frame always follows it. Each subcase runs on its own clone
 *       of that volume.</li>
 *   <li><b>Operations:</b> per subcase, change exactly one byte while no
 *       process runs, keeping the file length; {@code inspect wal}; start a
 *       node; read an earlier key over the public API.</li>
 *   <li><b>Corruption</b> (FORMAT.md §3.2–§3.4), with the expected
 *       category: (a) a value byte under the CRC → {@code CHECKSUM};
 *       (b) the stored record CRC → {@code CHECKSUM}; (c) the guarded length
 *       or its complement → {@code WAL_LENGTH_GUARD}; (d) the segment header
 *       magic, version or header CRC → {@code WAL_HEADER}.</li>
 *   <li><b>Client outcome:</b> the node never becomes ready; every public
 *       request answers {@code 503 NODE_NOT_READY}.</li>
 *   <li><b>Logical oracle:</b> no key is served, not even one written before
 *       the damaged frame.</li>
 *   <li><b>Physical / lifecycle oracle:</b> {@code inspect wal} reports
 *       {@code CORRUPT} with that category at the damaged frame (or header);
 *       {@code /health/ready} is {@code 503} with lifecycle {@code FAILED} and
 *       {@code lastFatalError} naming the same category and the offset. The
 *       file keeps its length and the changed byte (no truncation, no
 *       repair), and no new segment appears.</li>
 *   <li><b>Proves:</b> I7 — a complete damaged frame is corruption, never a
 *       torn tail, even with a valid frame after it, and startup refuses to
 *       serve unproven state. <b>Does not prove:</b> recovery of the data,
 *       which is not required, or the handling of a truly incomplete tail
 *       (WAL-002).</li>
 * </ol>
 *
 * <p>Every changed byte is recorded with file, offset, old and new value and
 * the file length (plan §14). Any violated expectation is thrown, so the
 * runner records the scenario as failed.
 */
public final class Cp1Wal003 {

    public static final String ID = "CP1-WAL-003";

    private static final int PUBLIC_PORT = 18130;
    private static final int ADMIN_PORT = 18131;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** FORMAT.md §3.2–§3.3. */
    private static final int SEGMENT_HEADER_BYTES = 24;
    private static final int FRAME_OVERHEAD = 30;
    private static final int KEY_OFFSET = 26;

    private static final List<String> KEYS = List.of("alpha", "beta", "gamma");

    private Cp1Wal003() {
    }

    /** One byte change: where, and the category recovery must report. */
    private record Damage(String name, long offset, byte xor, String category, boolean header) {
    }

    public static void run(long seed, Report report) throws Exception {
        List<byte[]> values = KEYS.stream()
                .map(key -> (key.toUpperCase() + "-" + seed).getBytes(StandardCharsets.US_ASCII))
                .toList();

        // Frame boundaries, computed from the format, not from production code.
        int target = (int) Math.floorMod(seed, 2L);
        long frameStart = SEGMENT_HEADER_BYTES;
        for (int i = 0; i < target; i++) {
            frameStart += FRAME_OVERHEAD + KEYS.get(i).length() + values.get(i).length;
        }
        int keyLength = KEYS.get(target).length();
        int valueLength = values.get(target).length;

        long[] headerBytes = {0, 9, 20};   // magic, format version, header CRC
        List<Damage> damages = List.of(
                new Damage("(a) value byte", frameStart + KEY_OFFSET + keyLength,
                        (byte) 0x01, "CHECKSUM", false),
                new Damage("(b) record CRC", frameStart + KEY_OFFSET + keyLength + valueLength,
                        (byte) 0xFF, "CHECKSUM", false),
                new Damage("(c) length guard", frameStart + (Math.floorMod(seed, 2L) == 0 ? 3 : 7),
                        (byte) 0x01, "WAL_LENGTH_GUARD", false),
                new Damage("(d) segment header", headerBytes[(int) Math.floorMod(seed, 3L)],
                        (byte) 0x01, "WAL_HEADER", true));

        try (WalScenarioNode prepared = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            prepared.startReady();
            for (int i = 0; i < KEYS.size(); i++) {
                prepared.put(KEYS.get(i), values.get(i));
            }
            long segment = prepared.storageStatus()
                    .path("activeMemtable").path("walSegmentId").asLong();
            prepared.kill();

            for (Damage damage : damages) {
                runSubcase(prepared, segment, frameStart, damage, seed);
            }
        }
        report.pass();
    }

    private static void runSubcase(WalScenarioNode prepared, long segment, long frameStart,
                                   Damage damage, long seed) throws Exception {
        try (WalScenarioNode node = WalScenarioNode.create(ID, seed, PUBLIC_PORT, ADMIN_PORT)) {
            VolumeTools.cloneVolume(prepared.dataDir(), node.dataDir());
            Path file = node.walSegment(segment);
            long length = Files.size(file);

            byte old = Files.readAllBytes(file)[(int) damage.offset()];
            byte changed = (byte) (old ^ damage.xor());
            CorruptionTools.corruptByte(file, damage.offset(), changed);
            String at = " " + damage.name() + ": " + file.getFileName() + " offset "
                    + damage.offset() + String.format(" 0x%02X -> 0x%02X", old, changed)
                    + ", length " + length;

            // The inspector sees corruption, not a torn tail, and stops there.
            WalScenarioNode.WalInspection inspection = node.inspectWal();
            JsonNode wal = inspection.report();
            JsonNode corruption = wal.path("segments").path(0).path("corruption");
            node.check(inspection.exitCode() == 1 && "CORRUPT".equals(wal.path("status").asText())
                            && wal.path("corruptSegmentId").asLong() == segment
                            && damage.category().equals(corruption.path("category").asText()),
                    "inspect wal" + at, wal.toString());
            if (!damage.header()) {
                node.check(corruption.path("offset").asLong() == frameStart, "inspect wal" + at,
                        "corruption at " + corruption.path("offset") + ", expected " + frameStart);
            }

            node.start();
            JsonNode ready = node.awaitLifecycle("FAILED");
            // CP1 §29.2: readiness stays 503; FAILED is final, so this read sees the same state.
            HttpResponse<String> readiness = node.readiness();
            node.check(readiness.statusCode() == 503, "readiness" + at,
                    "HTTP " + readiness.statusCode() + ": " + readiness.body());
            JsonNode fatal = ready.path("lastFatalError");
            node.check("NODE_NOT_READY".equals(ready.path("code").asText())
                            && damage.category().equals(fatal.path("code").asText()),
                    "readiness" + at, ready.toString());
            if (!damage.header()) {
                node.check(fatal.path("message").asText().contains("offset " + frameStart),
                        "readiness" + at, "no frame offset in " + fatal);
            }

            // Not even the key written before the damaged frame is served.
            HttpResponse<String> read = node.rawGet(KEYS.get(0));
            node.check(read.statusCode() == 503 && "NODE_NOT_READY".equals(
                            MAPPER.readTree(read.body()).path("code").asText()),
                    "GET " + KEYS.get(0) + at, "HTTP " + read.statusCode() + ": " + read.body());

            node.kill();
            byte[] after = Files.readAllBytes(file);
            node.check(after.length == length && after[(int) damage.offset()] == changed,
                    "segment after startup" + at, "length " + after.length + ", byte 0x"
                            + String.format("%02X", after[(int) damage.offset()]));
            try (Stream<Path> files = Files.list(file.getParent())) {
                List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
                node.check(names.equals(List.of(file.getFileName().toString())),
                        "WAL directory after startup" + at, "files " + names);
            }
        }
    }
}
