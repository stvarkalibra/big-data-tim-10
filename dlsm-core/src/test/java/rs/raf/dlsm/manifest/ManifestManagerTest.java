package rs.raf.dlsm.manifest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.FailpointNames;
import rs.raf.dlsm.failpoint.FailpointRegistry;
import rs.raf.dlsm.failpoint.FailpointRegistry.Action;
import rs.raf.dlsm.io.InjectedIOException;
import rs.raf.dlsm.recovery.DataLayout;
import rs.raf.dlsm.sstable.SSTableMeta;
import rs.raf.dlsm.wal.RecordingStorageIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManifestManagerTest {

    private static final OpContext CTX = OpContext.background();

    @TempDir
    Path root;

    private DataLayout layout;
    private FailpointRegistry failpoints;
    private RecordingStorageIO io;
    private ManifestManager manager;

    @BeforeEach
    void setUp() throws IOException {
        layout = new DataLayout(root);
        for (Path dir : layout.requiredDirectories()) {
            Files.createDirectories(dir);
        }
        failpoints = new FailpointRegistry(true);
        io = new RecordingStorageIO(failpoints);
        manager = new ManifestManager(layout, io);
    }

    private static SSTableMeta table(long id, long minSeq, long maxSeq) {
        return new SSTableMeta(id, SSTableMeta.fileName(id), "a", "z", minSeq, maxSeq, 10, 100);
    }

    private static Manifest withTable(Manifest base, SSTableMeta table, long flushedThrough) {
        List<SSTableMeta> tables = new ArrayList<>(base.sstables());
        tables.add(table);
        return new Manifest(base.formatVersion(), base.generation() + 1,
                Math.max(base.nextTableId(), table.tableId() + 1), flushedThrough, tables);
    }

    private void arm(String name, Action action) {
        failpoints.addRule(new FailpointRegistry.Rule("tok-" + name, name, action, 0, 1, 0, Map.of()));
    }

    private byte[] manifestBytes() throws IOException {
        return Files.readAllBytes(layout.manifestFile());
    }

    private List<Path> tmpFiles() throws IOException {
        try (var files = Files.list(layout.tmpDir())) {
            return files.toList();
        }
    }

    @Test
    void currentFailsBeforeLoadOrInitialPublish() {
        assertThrows(IllegalStateException.class, () -> manager.current());
    }

    @Test
    void publishesInitialManifest() throws IOException, StorageException {
        manager.publishInitialManifest(CTX);

        assertEquals(Manifest.initial(), manager.current());
        assertArrayEquals(Manifest.initial().toJson(), manifestBytes());
        assertTrue(tmpFiles().isEmpty());
        assertEquals(Manifest.initial(), new ManifestManager(layout, io).loadAndValidate());
    }

    @Test
    void initialPublishFollowsAtomicProtocolInOrder() throws IOException {
        manager.publishInitialManifest(CTX);

        String temp = ManifestManager.tempFileName(0);
        assertEquals(List.of(
                "delete " + temp,
                "openNew " + temp,
                "writeFully",
                "force",
                "atomicReplace MANIFEST.json",
                "fsyncDirectory lsm"), io.calls());
    }

    @Test
    void tempFileNameUsesTwentyDigitGeneration() {
        assertEquals("MANIFEST-00000000000000000007.tmp", ManifestManager.tempFileName(7));
    }

    @Test
    void initialPublishRefusedWhenManifestAlreadyLoaded() throws IOException {
        manager.publishInitialManifest(CTX);

        assertThrows(IllegalStateException.class, () -> manager.publishInitialManifest(CTX));
    }

    @Test
    void publishReplacesManifestWithNextGeneration() throws IOException, StorageException {
        manager.publishInitialManifest(CTX);
        Manifest next = withTable(manager.current(), table(1, 1, 40), 40);

        manager.publish(next, CTX);

        assertEquals(next, manager.current());
        assertArrayEquals(next.toJson(), manifestBytes());
        assertEquals(next, new ManifestManager(layout, io).loadAndValidate());
        assertTrue(tmpFiles().isEmpty());
    }

    @Test
    void publishChangeRebasesOnLatestManifest() throws IOException, StorageException {
        manager.publishInitialManifest(CTX);
        manager.publish(withTable(manager.current(), table(1, 1, 40), 40), CTX);

        Manifest published = manager.publish(
                base -> withTable(base, table(2, 41, 90), 90), CTX);

        assertEquals(2, published.generation());
        assertEquals(List.of(table(1, 1, 40), table(2, 41, 90)), published.sstables());
        assertEquals(3, published.nextTableId());
        assertEquals(published, manager.current());

        Manifest overridden = manager.publish(
                base -> new Manifest(1, 99, base.nextTableId(), base.flushedThroughSequence(), base.sstables()),
                CTX);
        assertEquals(3, overridden.generation());
    }

    @Test
    void publishRejectsWrongGenerationAndRegressions() throws IOException, StorageException {
        manager.publishInitialManifest(CTX);
        manager.publish(withTable(manager.current(), table(1, 1, 40), 40), CTX);
        Manifest base = manager.current();
        io.clearCalls();

        assertThrows(IllegalArgumentException.class, () -> manager.publish(
                new Manifest(1, 1, base.nextTableId(), 40, base.sstables()), CTX));
        assertThrows(IllegalArgumentException.class, () -> manager.publish(
                new Manifest(1, 3, base.nextTableId(), 40, base.sstables()), CTX));
        assertThrows(IllegalArgumentException.class, () -> manager.publish(
                new Manifest(1, 2, 1, 40, List.of()), CTX));
        assertThrows(IllegalArgumentException.class, () -> manager.publish(
                new Manifest(1, 2, base.nextTableId(), 39, List.of()), CTX));

        assertEquals(base, manager.current());
        assertTrue(io.calls().isEmpty());
    }

    @Test
    void publishRejectsInvalidCandidateWithoutTouchingDisk() throws IOException {
        manager.publishInitialManifest(CTX);
        byte[] before = manifestBytes();
        io.clearCalls();

        StorageException e = assertThrows(StorageException.class, () -> manager.publish(
                new Manifest(1, 1, 5, 10, List.of(table(3, 1, 20))), CTX));

        assertEquals("MANIFEST_INVALID", e.category());
        assertTrue(io.calls().isEmpty());
        assertArrayEquals(before, manifestBytes());
        assertEquals(Manifest.initial(), manager.current());
    }

    @Test
    void publishRequiresLoadedManifest() {
        assertThrows(IllegalStateException.class, () -> manager.publish(Manifest.initial(), CTX));
    }

    @Test
    void ioErrorBeforeRenameKeepsOldManifestAndRemovesTemp() throws IOException, StorageException {
        manager.publishInitialManifest(CTX);
        byte[] before = manifestBytes();
        arm(FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME, Action.IO_ERROR);
        Manifest next = withTable(manager.current(), table(1, 1, 40), 40);

        ManifestPublishException e = assertThrows(ManifestPublishException.class,
                () -> manager.publish(next, CTX));

        assertFalse(e.renamed());
        assertEquals(1, e.generation());
        assertInstanceOf(InjectedIOException.class, e.getCause());
        assertArrayEquals(before, manifestBytes());
        assertEquals(Manifest.initial(), manager.current());
        assertTrue(tmpFiles().isEmpty());

        manager.publish(next, CTX);
        assertEquals(next, manager.current());
        assertArrayEquals(next.toJson(), manifestBytes());
    }

    @Test
    void ioErrorAfterRenameReportsRenamedAndKeepsInMemoryView() throws IOException, StorageException {
        manager.publishInitialManifest(CTX);
        arm(FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, Action.IO_ERROR);
        Manifest next = withTable(manager.current(), table(1, 1, 40), 40);

        ManifestPublishException e = assertThrows(ManifestPublishException.class,
                () -> manager.publish(next, CTX));

        assertTrue(e.renamed());
        assertEquals(1, e.generation());
        assertArrayEquals(next.toJson(), manifestBytes());
        assertEquals(Manifest.initial(), manager.current());
        assertTrue(tmpFiles().isEmpty());
    }

    @Test
    void forceFailureIsNotRenamedAndLeavesNoTemp() throws IOException {
        manager.publishInitialManifest(CTX);
        byte[] before = manifestBytes();
        io.failOn("force");

        ManifestPublishException e = assertThrows(ManifestPublishException.class,
                () -> manager.publish(withTable(manager.current(), table(1, 1, 40), 40), CTX));

        assertFalse(e.renamed());
        assertArrayEquals(before, manifestBytes());
        assertTrue(tmpFiles().isEmpty());
    }

    @Test
    void leftoverTempOfSameGenerationIsReplaced() throws IOException, StorageException {
        manager.publishInitialManifest(CTX);
        Files.write(layout.tmpDir().resolve(ManifestManager.tempFileName(1)),
                "half".getBytes(StandardCharsets.US_ASCII));
        Manifest next = withTable(manager.current(), table(1, 1, 40), 40);

        manager.publish(next, CTX);

        assertArrayEquals(next.toJson(), manifestBytes());
        assertTrue(tmpFiles().isEmpty());
    }

    @Test
    void loadRejectsMissingManifest() {
        StorageException e = assertThrows(StorageException.class, () -> manager.loadAndValidate());

        assertEquals("MANIFEST_INVALID", e.category());
    }

    @Test
    void loadRejectsDirectoryInPlaceOfManifest() throws IOException {
        Files.createDirectory(layout.manifestFile());

        StorageException e = assertThrows(StorageException.class, () -> manager.loadAndValidate());

        assertEquals("MANIFEST_INVALID", e.category());
    }

    @Test
    void loadRejectsCorruptManifestAndLeavesNoCurrent() throws IOException {
        Files.write(layout.manifestFile(), "{\"formatVersion\":1".getBytes(StandardCharsets.UTF_8));

        StorageException e = assertThrows(StorageException.class, () -> manager.loadAndValidate());

        assertEquals("MANIFEST_INVALID", e.category());
        assertThrows(IllegalStateException.class, () -> manager.current());
    }

    @Test
    void loadedManifestIsBaseForNextPublish() throws IOException, StorageException {
        manager.publishInitialManifest(CTX);
        manager.publish(withTable(manager.current(), table(1, 1, 40), 40), CTX);

        ManifestManager restarted = new ManifestManager(layout, io);
        Manifest loaded = restarted.loadAndValidate();
        Manifest next = restarted.publish(base -> withTable(base, table(2, 41, 50), 50), CTX);

        assertEquals(1, loaded.generation());
        assertEquals(2, next.generation());
        assertEquals(next, new ManifestManager(layout, io).loadAndValidate());
    }
}
