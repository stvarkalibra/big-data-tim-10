
package rs.raf.dlsm.inspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.sstable.BlockCodec;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.sstable.SSTableValidator;
import rs.raf.dlsm.sstable.SSTableWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InspectSSTableFileTest {

    @TempDir
    Path directory;

    private record Fixture(
            Path file,
            SSTableMeta meta,
            byte[] bytes
    ) {
    }

    private Fixture createTable(
            int count,
            long tableId) throws Exception {

        List<BlockCodec.Record> records =
                new ArrayList<>();

        for (int i = 0; i < count; i++) {
            records.add(new BlockCodec.Record(
                    String.format("key%05d", i),
                    i + 1L,
                    BlockCodec.PUT,
                    new byte[100]
            ));
        }

        SSTableWriter.Result result =
                SSTableWriter.build(
                        tableId,
                        records.iterator(),
                        records.size()
                );

        Path file = directory.resolve(
                result.meta().file()
        );

        Files.write(file, result.bytes());

        return new Fixture(
                file,
                result.meta(),
                result.bytes()
        );
    }

    @Test
    void producesExactlySameReportForOneBlock()
            throws Exception {

        Fixture fixture = createTable(3, 101);

        Map<String, Object> expected =
                InspectSSTable.inspect(
                        fixture.bytes(),
                        fixture.meta()
                );

        Map<String, Object> actual =
                InspectSSTableFile.inspect(
                        fixture.file(),
                        fixture.meta()
                );

        assertEquals(expected, actual);
    }

    @Test
    void producesExactlySameReportForManyBlocks()
            throws Exception {

        Fixture fixture = createTable(1000, 102);

        Map<String, Object> expected =
                InspectSSTable.inspect(
                        fixture.bytes(),
                        fixture.meta()
                );

        Map<String, Object> actual =
                InspectSSTableFile.inspect(
                        fixture.file(),
                        fixture.meta()
                );

        assertEquals(expected, actual);
        assertEquals(1000L, actual.get("entryCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks =
                (List<Map<String, Object>>) actual.get("blocks");

        assertTrue(blocks.size() > 1);
    }

    @Test
    void distinguishesDeleteAndEmptyPut()
            throws Exception {

        List<BlockCodec.Record> records = List.of(
                new BlockCodec.Record(
                        "alpha", 1,
                        BlockCodec.DELETE, null
                ),
                new BlockCodec.Record(
                        "beta", 2,
                        BlockCodec.PUT, new byte[0]
                )
        );

        SSTableWriter.Result table =
                SSTableWriter.build(
                        103,
                        records.iterator(),
                        records.size()
                );

        Path file = directory.resolve(
                table.meta().file()
        );

        Files.write(file, table.bytes());

        assertEquals(
                InspectSSTable.inspect(
                        table.bytes(),
                        table.meta()
                ),
                InspectSSTableFile.inspect(
                        file,
                        table.meta()
                )
        );
    }

    @Test
    void rejectsCorruptedBlock()
            throws Exception {

        Fixture fixture = createTable(100, 104);

        byte[] corrupted = fixture.bytes().clone();

        corrupted[SSTableWriter.HEADER_BYTES + 25] ^= 1;

        Files.write(fixture.file(), corrupted);

        SSTableValidator.ValidationException error =
                assertThrows(
                        SSTableValidator.ValidationException.class,
                        () -> InspectSSTableFile.inspect(
                                fixture.file(),
                                fixture.meta()
                        )
                );

        assertEquals(
                SSTableValidator.Category.DATA_BLOCK,
                error.category()
        );
    }

    @Test
    void rejectsTruncatedFile()
            throws Exception {

        Fixture fixture = createTable(100, 105);

        byte[] truncated = new byte[20];
        Files.write(fixture.file(), truncated);

        SSTableValidator.ValidationException error =
                assertThrows(
                        SSTableValidator.ValidationException.class,
                        () -> InspectSSTableFile.inspect(
                                fixture.file(),
                                fixture.meta()
                        )
                );

        assertEquals(
                SSTableValidator.Category.TRUNCATED_REGION,
                error.category()
        );
    }

    @Test
    void inspectionDoesNotModifyFile()
            throws Exception {

        Fixture fixture = createTable(300, 106);

        byte[] before =
                Files.readAllBytes(fixture.file());

        InspectSSTableFile.inspect(
                fixture.file(),
                fixture.meta()
        );

        assertArrayEquals(
                before,
                Files.readAllBytes(fixture.file())
        );
    }

    @Test
    void reportsAllKeysInCorrectOrder()
            throws Exception {

        Fixture fixture = createTable(1000, 107);

        Map<String, Object> result =
                InspectSSTableFile.inspect(
                        fixture.file(),
                        fixture.meta()
                );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records =
                (List<Map<String, Object>>)
                        result.get("records");

        assertEquals(1000, records.size());

        for (int i = 0; i < records.size(); i++) {
            assertEquals(
                    String.format("key%05d", i),
                    records.get(i).get("key")
            );

            assertEquals(
                    i + 1L,
                    records.get(i).get("sequence")
            );
        }
    }
}
