package rs.raf.dlsm.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.raf.dlsm.common.StorageException;
import rs.raf.dlsm.engine.OpContext;
import rs.raf.dlsm.failpoint.NoOpFailpoints;
import rs.raf.dlsm.io.StorageIO;
import rs.raf.dlsm.wal.RecordingStorageIO;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** First initialization (CP1 §14.2) and resuming after a crash between its steps. */
class DataDirectoryTest {

    private static final OpContext CTX = OpContext.background();
    private static final String NODE = "standalone";
    private static final byte[] EMPTY_MANIFEST = ("{\"formatVersion\":1,\"generation\":0,"
            + "\"nextTableId\":1,\"flushedThroughSequence\":0,\"sstables\":[]}")
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private DataLayout layout() {
        return new DataLayout(tempDir.resolve("dlsm"));
    }

    /** Stands in for the manifest module: records each call, can fail once. */
    private final class FakeManifest implements ManifestInitializer {
        int calls;
        boolean failNext;

        @Override
        public void publishInitialManifest(OpContext ctx) throws IOException {
            calls++;
            if (failNext) {
                failNext = false;
                throw new IOException("Simulated failure at step 3");
            }
            Files.write(layout().manifestFile(), EMPTY_MANIFEST);
        }
    }

    private DataDirectory open(RecordingStorageIO io, ManifestInitializer manifest)
            throws IOException, StorageException {
        return DataDirectory.open(layout(), NODE, io, manifest, CTX);
    }

    private static RecordingStorageIO io() {
        return new RecordingStorageIO(new NoOpFailpoints());
    }

    // ===== A brand-new database =====

    @Test
    void initializesBrandNewDirectory() throws Exception {
        FakeManifest manifest = new FakeManifest();

        try (DataDirectory dir = open(io(), manifest)) {
            assertEquals(StartupState.NEW_DATABASE, dir.foundState());
            assertTrue(dir.isLocked());
        }

        for (Path required : layout().requiredDirectories()) {
            assertTrue(Files.isDirectory(required), required.toString());
        }
        assertArrayEquals(NodeIdentity.of(NODE).toJson(), Files.readAllBytes(layout().nodeFile()));
        assertEquals(1, manifest.calls);
        assertTrue(Files.exists(layout().lockFile()));
    }

    // CP1 §14.2: directories and parent force, node.json, then the manifest.
    @Test
    void runsInitializationStepsInOrder() throws Exception {
        RecordingStorageIO io = io();
        List<String> order = new ArrayList<>();
        ManifestInitializer manifest = ctx -> {
            order.addAll(io.calls());
            order.add("publishInitialManifest");
            Files.write(layout().manifestFile(), EMPTY_MANIFEST);
        };

        try (DataDirectory ignored = open(io, manifest)) {
            assertEquals(List.of(
                    "fsyncDirectory lsm",
                    "fsyncDirectory dlsm",
                    "delete node.json.tmp",
                    "openNew node.json.tmp",
                    "writeFully",
                    "force",
                    "atomicMoveNoReplace node.json",
                    "fsyncDirectory dlsm",
                    "publishInitialManifest"), order);
        }
    }

    @Test
    void existingDatabaseIsOpenedWithoutChanges() throws Exception {
        try (DataDirectory ignored = open(io(), new FakeManifest())) {
            // first start initializes
        }
        RecordingStorageIO io = io();
        FakeManifest manifest = new FakeManifest();

        try (DataDirectory dir = open(io, manifest)) {
            assertEquals(StartupState.EXISTING, dir.foundState());
        }

        assertEquals(List.of(), io.calls());
        assertEquals(0, manifest.calls);
    }

    // ===== Resuming after a crash between steps =====

    @Test
    void crashAfterDirectoriesStartsOverAsNewDatabase() throws Exception {
        for (Path required : layout().requiredDirectories()) {
            Files.createDirectories(required);
        }

        try (DataDirectory dir = open(io(), new FakeManifest())) {
            assertEquals(StartupState.NEW_DATABASE, dir.foundState());
        }
        assertTrue(Files.exists(layout().nodeFile()));
    }

    @Test
    void failureWhileWritingNodeFileLeavesNewDatabase() throws Exception {
        for (String failing : new String[]{"openNew", "writeFully", "force", "atomicMoveNoReplace"}) {
            assertThrows(IOException.class,
                    () -> open(io().failOn(failing), new FakeManifest()), failing);
            assertFalse(Files.exists(layout().nodeFile()), failing);

            try (DataDirectory dir = open(io(), new FakeManifest())) {
                assertEquals(StartupState.NEW_DATABASE, dir.foundState(), failing);
            }
            assertArrayEquals(NodeIdentity.of(NODE).toJson(), Files.readAllBytes(layout().nodeFile()));

            deleteDatabase();
        }
    }

    // The rename happened, its durability is unknown: the next start sees node.json.
    @Test
    void failureAfterNodeFileMoveResumesAtManifest() throws Exception {
        StorageIO failingRootForce = new SecondRootForceFails(io(), layout().root());

        assertThrows(IOException.class, () -> DataDirectory.open(
                layout(), NODE, failingRootForce, new FakeManifest(), CTX));
        assertTrue(Files.exists(layout().nodeFile()));

        try (DataDirectory dir = open(io(), new FakeManifest())) {
            assertEquals(StartupState.RESUME_AT_MANIFEST, dir.foundState());
        }
    }

    @Test
    void failureAtManifestStepResumesThere() throws Exception {
        FakeManifest manifest = new FakeManifest();
        manifest.failNext = true;

        assertThrows(IOException.class, () -> open(io(), manifest));
        assertTrue(Files.exists(layout().nodeFile()));
        assertFalse(Files.exists(layout().manifestFile()));

        RecordingStorageIO io = io();
        try (DataDirectory dir = open(io, manifest)) {
            assertEquals(StartupState.RESUME_AT_MANIFEST, dir.foundState());
        }
        assertEquals(2, manifest.calls);
        assertTrue(Files.exists(layout().manifestFile()));
        // node.json is not written again.
        assertEquals(List.of(), io.calls());
    }

    // ===== Ownership and refusals =====

    @Test
    void secondOpenWhileOwnedIsRefused() throws Exception {
        try (DataDirectory ignored = open(io(), new FakeManifest())) {
            StorageException e = assertThrows(StorageException.class,
                    () -> open(io(), new FakeManifest()));
            assertEquals(DirectoryLock.CATEGORY, e.category());
        }
    }

    @Test
    void refusedStartChangesNothingAndReleasesTheLock() throws Exception {
        try (DataDirectory ignored = open(io(), new FakeManifest())) {
            // initialize as "standalone"
        }
        byte[] nodeBefore = Files.readAllBytes(layout().nodeFile());
        RecordingStorageIO io = io();

        StorageException e = assertThrows(StorageException.class,
                () -> DataDirectory.open(layout(), "storage-1", io, new FakeManifest(), CTX));

        assertEquals(NodeIdentity.CATEGORY, e.category());
        assertEquals(List.of(), io.calls());
        assertArrayEquals(nodeBefore, Files.readAllBytes(layout().nodeFile()));
        try (DataDirectory dir = open(io(), new FakeManifest())) {
            assertTrue(dir.isLocked());
        }
    }

    @Test
    void closeReleasesTheLock() throws Exception {
        DataDirectory dir = open(io(), new FakeManifest());
        dir.close();

        assertFalse(dir.isLocked());
        try (DataDirectory again = open(io(), new FakeManifest())) {
            assertTrue(again.isLocked());
        }
    }

    /**
     * Delegates everything, but fails the second force of the root: the one
     * right after {@code node.json} was moved to its final name.
     */
    private static final class SecondRootForceFails implements StorageIO {
        private final StorageIO delegate;
        private final Path root;
        private int rootForces;

        SecondRootForceFails(StorageIO delegate, Path root) {
            this.delegate = delegate;
            this.root = root;
        }

        @Override
        public FileChannel openNew(Path p) throws IOException {
            return delegate.openNew(p);
        }

        @Override
        public void writeFully(FileChannel ch, ByteBuffer buf, String fpBefore, OpContext ctx)
                throws IOException {
            delegate.writeFully(ch, buf, fpBefore, ctx);
        }

        @Override
        public void force(FileChannel ch, String fpBefore, OpContext ctx) throws IOException {
            delegate.force(ch, fpBefore, ctx);
        }

        @Override
        public void atomicMoveNoReplace(Path from, Path to, String fpBefore, OpContext ctx)
                throws IOException {
            delegate.atomicMoveNoReplace(from, to, fpBefore, ctx);
        }

        @Override
        public void atomicReplace(Path from, Path to, String fpBefore, OpContext ctx)
                throws IOException {
            delegate.atomicReplace(from, to, fpBefore, ctx);
        }

        @Override
        public void fsyncDirectory(Path dir, String fpBefore, OpContext ctx) throws IOException {
            if (dir.equals(root) && ++rootForces == 2) {
                throw new IOException("Simulated failure of the root force after node.json");
            }
            delegate.fsyncDirectory(dir, fpBefore, ctx);
        }

        @Override
        public void truncate(FileChannel ch, long size) throws IOException {
            delegate.truncate(ch, size);
        }

        @Override
        public void delete(Path p) throws IOException {
            delegate.delete(p);
        }
    }

    private void deleteDatabase() throws IOException {
        try (var paths = Files.walk(layout().root())) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(path);
            }
        }
    }
}
