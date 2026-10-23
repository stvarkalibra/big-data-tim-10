package rs.raf.dlsm.manifest;

import org.junit.jupiter.api.Test;
import rs.raf.dlsm.common.ErrorCode;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.sstable.SSTableMeta;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestTest {

    private static final String SPEC_EXAMPLE = """
            {
              "formatVersion": 1,
              "generation": 7,
              "nextTableId": 12,
              "flushedThroughSequence": 812,
              "sstables": [
                {
                  "tableId": 11,
                  "file": "sst-00000000000000000011.sst",
                  "minKey": "customer.0001",
                  "maxKey": "customer.0999",
                  "minSequence": 401,
                  "maxSequence": 812,
                  "entryCount": 377,
                  "sizeBytes": 28194
                }
              ]
            }
            """;

    private static SSTableMeta table(long id, long minSeq, long maxSeq) {
        return new SSTableMeta(id, SSTableMeta.fileName(id), "a", "z", minSeq, maxSeq, 10, 100);
    }

    private static Manifest manifest(long nextTableId, long flushedThrough, SSTableMeta... tables) {
        return new Manifest(Manifest.FORMAT_VERSION, 3, nextTableId, flushedThrough, List.of(tables));
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertInvalid(String json) {
        StorageException e = assertThrows(StorageException.class, () -> Manifest.parse(bytes(json)));
        assertEquals(ErrorCode.STORAGE_IO, e.code());
        assertEquals("MANIFEST_INVALID", e.category());
    }

    private static void assertInvalid(Manifest manifest) {
        StorageException e = assertThrows(StorageException.class, () -> ManifestValidator.validate(manifest));
        assertEquals("MANIFEST_INVALID", e.category());
    }

    @Test
    void initialManifestMatchesSpec() throws StorageException {
        Manifest initial = Manifest.initial();

        assertEquals(new Manifest(1, 0, 1, 0, List.of()), initial);
        assertEquals("{\"formatVersion\":1,\"generation\":0,\"nextTableId\":1,"
                        + "\"flushedThroughSequence\":0,\"sstables\":[]}",
                new String(initial.toJson(), StandardCharsets.UTF_8));
        ManifestValidator.validate(initial);
    }

    @Test
    void parsesSpecExample() throws StorageException {
        Manifest m = Manifest.parse(bytes(SPEC_EXAMPLE));

        assertEquals(7, m.generation());
        assertEquals(12, m.nextTableId());
        assertEquals(812, m.flushedThroughSequence());
        assertEquals(List.of(new SSTableMeta(11, "sst-00000000000000000011.sst",
                "customer.0001", "customer.0999", 401, 812, 377, 28194)), m.sstables());
    }

    @Test
    void roundTripsThroughJson() throws StorageException {
        Manifest m = manifest(10, 500, table(3, 1, 200), table(9, 201, 500));

        assertEquals(m, Manifest.parse(m.toJson()));
    }

    @Test
    void sstablesListIsImmutableCopy() {
        List<SSTableMeta> source = new ArrayList<>(List.of(table(1, 1, 5)));
        Manifest m = manifest(2, 5, source.toArray(SSTableMeta[]::new));
        source.clear();

        assertEquals(1, m.sstables().size());
        assertThrows(UnsupportedOperationException.class, () -> m.sstables().clear());
    }

    @Test
    void rejectsMalformedJson() {
        assertInvalid("");
        assertInvalid("{");
        assertInvalid("[]");
        assertInvalid("null");
        assertInvalid(SPEC_EXAMPLE + "{}");
    }

    @Test
    void rejectsMissingOrUnknownFields() {
        assertInvalid(SPEC_EXAMPLE.replace("\"generation\": 7,", ""));
        assertInvalid(SPEC_EXAMPLE.replace("\"sizeBytes\": 28194", "\"sizeBytes\": 28194, \"extra\": 1"));
        assertInvalid(SPEC_EXAMPLE.replace("\"generation\": 7,", "\"generation\": 7, \"other\": 0,"));
        assertInvalid(SPEC_EXAMPLE.replace("\"entryCount\": 377,", ""));
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        assertInvalid(SPEC_EXAMPLE.replace("\"generation\": 7,", "\"generation\": 7, \"generation\": 8,"));
    }

    @Test
    void rejectsWrongTypes() {
        assertInvalid(SPEC_EXAMPLE.replace("\"generation\": 7", "\"generation\": \"7\""));
        assertInvalid(SPEC_EXAMPLE.replace("\"generation\": 7", "\"generation\": 7.5"));
        assertInvalid(SPEC_EXAMPLE.replace("\"generation\": 7", "\"generation\": 99999999999999999999"));
        assertInvalid(SPEC_EXAMPLE.replace("\"minKey\": \"customer.0001\"", "\"minKey\": 1"));
        assertInvalid(SPEC_EXAMPLE.replace("\"sstables\": [", "\"sstables\": {\"x\": [").replace("]\n}", "]}\n}"));
        assertInvalid("{\"formatVersion\":1,\"generation\":0,\"nextTableId\":1,"
                + "\"flushedThroughSequence\":0,\"sstables\":[1]}");
    }

    @Test
    void rejectsUnsupportedFormatVersion() {
        assertInvalid(SPEC_EXAMPLE.replace("\"formatVersion\": 1", "\"formatVersion\": 2"));
        assertInvalid(manifestWithVersion(2));
    }

    private static Manifest manifestWithVersion(int version) {
        return new Manifest(version, 0, 1, 0, List.of());
    }

    @Test
    void rejectsTableMetaThatViolatesItsOwnBounds() {
        assertInvalid(SPEC_EXAMPLE.replace("\"minSequence\": 401", "\"minSequence\": 900"));
        assertInvalid(SPEC_EXAMPLE.replace("\"entryCount\": 377", "\"entryCount\": 0"));
        assertInvalid(SPEC_EXAMPLE.replace("\"tableId\": 11", "\"tableId\": 0"));
    }

    @Test
    void rejectsNegativeOrOutOfRangeCounters() {
        assertInvalid(new Manifest(1, -1, 1, 0, List.of()));
        assertInvalid(new Manifest(1, 0, 0, 0, List.of()));
        assertInvalid(new Manifest(1, 0, 1, -1, List.of()));
    }

    @Test
    void rejectsDuplicateTableIdsAndFiles() {
        assertInvalid(manifest(10, 100, table(4, 1, 10), table(4, 11, 20)));
        SSTableMeta sameFile = new SSTableMeta(5, SSTableMeta.fileName(4), "a", "z", 11, 20, 1, 1);
        assertInvalid(manifest(10, 100, table(4, 1, 10), sameFile));
    }

    @Test
    void rejectsTableIdNotBelowNextTableId() {
        assertInvalid(manifest(4, 100, table(4, 1, 10)));
        assertInvalid(manifest(4, 100, table(7, 1, 10)));
    }

    @Test
    void rejectsFileNameThatDoesNotMatchTableId() {
        assertInvalid(manifest(10, 100, new SSTableMeta(3, "sst-3.sst", "a", "z", 1, 10, 1, 1)));
        assertInvalid(manifest(10, 100, new SSTableMeta(3, SSTableMeta.fileName(4), "a", "z", 1, 10, 1, 1)));
    }

    @Test
    void rejectsInvalidKeys() {
        assertInvalid(manifest(10, 100, new SSTableMeta(3, SSTableMeta.fileName(3), "a b", "z", 1, 10, 1, 1)));
        assertInvalid(manifest(10, 100, new SSTableMeta(3, SSTableMeta.fileName(3), "a", "z:", 1, 10, 1, 1)));
    }

    @Test
    void rejectsTableAboveHighWaterMark() {
        assertInvalid(manifest(10, 9, table(3, 1, 10)));
    }

    @Test
    void acceptsTableAtHighWaterMarkAndSequenceGaps() throws StorageException {
        ManifestValidator.validate(manifest(10, 10, table(3, 1, 10)));
        ManifestValidator.validate(manifest(10, 50, table(3, 1, 10), table(8, 31, 34)));
    }
}
