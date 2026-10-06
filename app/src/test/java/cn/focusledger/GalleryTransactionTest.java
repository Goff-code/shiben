package cn.focusledger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

/** Android-free transaction tests; no real files outside the temporary test folder. */
public final class GalleryTransactionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private File source(String name) throws IOException {
        File source = temporary.newFile(name);
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        return source;
    }
    private List<File> sources() throws IOException {
        return Arrays.asList(source("one.png"), source("two.png"), source("three.png"));
    }
    private static final class Control implements GalleryTransaction.Control {
        boolean cancelled, committed, cancelOnCommit, cancelOnProgress;
        int commitCount;
        final List<String> progress = new ArrayList<>();
        @Override public void checkCancelled() { if (cancelled) throw new CancellationException("test cancellation"); }
        @Override public void progress(int completed, int total) {
            progress.add(completed + "/" + total);
            if (cancelOnProgress) cancelled = true;
        }
        @Override public void commit() {
            commitCount++;
            if (cancelOnCommit) cancelled = true;
            checkCancelled(); committed = true;
        }
    }
    private static class Backend implements GalleryTransaction.Backend<Integer> {
        final Control control;
        final Set<Integer> present = new LinkedHashSet<>(Collections.singletonList(99));
        final List<Integer> created = new ArrayList<>(), copied = new ArrayList<>(), published = new ArrayList<>(), deleted = new ArrayList<>();
        final List<String> operations = new ArrayList<>();
        String failStage, cancelStage;
        int failAt = 2, cancelAt = 1, deleteFailure = -1;
        boolean nullCreate, uncheckedFailure;
        Backend(Control control) { this.control = control; }
        void fail(String stage, int id) throws IOException {
            if (stage.equals(failStage) && id == failAt) {
                if (uncheckedFailure) throw new SecurityException("backend denied");
                throw new IOException("injected " + stage);
            }
        }
        void cancel(String stage, int id) {
            if (stage.equals(cancelStage) && id == cancelAt) control.cancelled = true;
        }
        @Override public Integer create(File source, int index) throws IOException {
            int id = index + 1; operations.add("create" + id); fail("create", id);
            if (nullCreate && id == failAt) return null;
            created.add(id); present.add(id); cancel("create", id); return id;
        }
        @Override public void copy(File source, Integer id, Runnable checkCancelled) throws IOException {
            operations.add("copy" + id); cancel("copy", id); checkCancelled.run();
            fail("copy", id); copied.add(id);
        }
        @Override public void publish(Integer id) throws IOException {
            operations.add("publish" + id); fail("publish", id); published.add(id); cancel("publish", id);
        }
        @Override public void delete(Integer id) throws IOException {
            operations.add("delete" + id); deleted.add(id);
            if (id == deleteFailure) throw new IOException("injected delete");
            present.remove(id);
        }
    }
    private static <T extends Throwable> T expect(Class<T> type, Throwing action) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private interface Throwing { void run() throws Exception; }
    private static void rolledBack(Backend backend, Control control) {
        assertEquals(backend.created, backend.deleted);
        assertEquals(Collections.singleton(99), backend.present);
        assertFalse(control.committed);
    }

    @Test public void savesAllItemsCopiesBeforePublishingAndCommitsOnce() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control);
        List<File> inputs = sources(); List<Integer> result = GalleryTransaction.save(inputs, backend, control);
        assertEquals(Arrays.asList(1, 2, 3), result);
        assertEquals(Arrays.asList("create1", "copy1", "create2", "copy2", "create3", "copy3", "publish1", "publish2", "publish3"), backend.operations);
        assertEquals(Arrays.asList("1/3", "2/3", "3/3"), control.progress);
        assertTrue(control.committed); assertEquals(1, control.commitCount); assertTrue(backend.deleted.isEmpty());
        for (File input : inputs) assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(input.toPath()));
        expect(UnsupportedOperationException.class, () -> result.add(4));
    }
    @Test public void emptyBatchFailsWithoutCreatingOrCommitting() throws Exception {
        for (List<File> inputs : Arrays.<List<File>>asList(null, Collections.emptyList())) {
            Control control = new Control(); Backend backend = new Backend(control);
            expect(IOException.class, () -> GalleryTransaction.save(inputs, backend, control));
            assertTrue(backend.operations.isEmpty()); assertEquals(0, control.commitCount);
        }
    }
    @Test public void missingSecondSourceFailsBeforeInsertingFirstImage() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control);
        File valid = source("valid.png"), missing = new File(temporary.getRoot(), "missing.png");
        expect(IOException.class, () -> GalleryTransaction.save(Arrays.asList(valid, missing), backend, control));
        assertTrue(backend.operations.isEmpty()); assertTrue(valid.isFile());
    }
    @Test public void emptyNullAndDirectorySourcesFailBeforeInsert() throws Exception {
        File empty = temporary.newFile("empty.png"), directory = temporary.newFolder("folder.png");
        for (File invalid : Arrays.asList(null, empty, directory)) {
            Control control = new Control(); Backend backend = new Backend(control);
            expect(IOException.class, () -> GalleryTransaction.save(Collections.singletonList(invalid), backend, control));
            assertTrue(backend.operations.isEmpty());
        }
    }
    @Test public void alreadyCancelledBatchNeverCreatesAnItem() throws Exception {
        Control control = new Control(); control.cancelled = true; Backend backend = new Backend(control);
        List<File> inputs = sources();
        expect(CancellationException.class, () -> GalleryTransaction.save(inputs, backend, control));
        assertTrue(backend.operations.isEmpty()); assertEquals(0, control.commitCount);
    }
    @Test public void firstCreateFailureCannotClaimSuccessOrDeleteOtherRows() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.failStage = "create"; backend.failAt = 1;
        List<File> inputs = sources();
        expect(IOException.class, () -> GalleryTransaction.save(inputs, backend, control));
        rolledBack(backend, control); assertTrue(backend.created.isEmpty()); assertEquals(0, control.commitCount);
    }
    @Test public void laterCreateFailureRollsBackEarlierImages() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.failStage = "create";
        List<File> inputs = sources();
        expect(IOException.class, () -> GalleryTransaction.save(inputs, backend, control));
        rolledBack(backend, control); assertEquals(Collections.singletonList(1), backend.deleted);
        assertTrue(backend.published.isEmpty());
    }
    @Test public void nullCreateResultRollsBackEarlierImages() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.nullCreate = true;
        List<File> inputs = sources();
        expect(IOException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Collections.singletonList(1), backend.deleted);
    }
    @Test public void copyFailureRollsBackCurrentAndEarlierImages() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.failStage = "copy";
        List<File> inputs = sources();
        expect(IOException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Arrays.asList(1, 2), backend.deleted); assertTrue(backend.published.isEmpty());
        assertEquals(Collections.singletonList("1/3"), control.progress);
    }
    @Test public void uncheckedBackendFailureStillRollsBackOnlyNewImages() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.failStage = "copy"; backend.uncheckedFailure = true;
        List<File> inputs = sources();
        expect(SecurityException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
    }
    @Test public void publishFailureAlsoRemovesAlreadyPublishedImages() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.failStage = "publish";
        List<File> inputs = sources();
        expect(IOException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Collections.singletonList(1), backend.published); assertEquals(Arrays.asList(1, 2, 3), backend.deleted);
        assertEquals(0, control.commitCount);
    }
    @Test public void cancelImmediatelyAfterCreateRemovesNewRow() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.cancelStage = "create";
        List<File> inputs = sources();
        expect(CancellationException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Collections.singletonList(1), backend.deleted); assertTrue(backend.copied.isEmpty());
    }
    @Test public void cancelDuringCopyRemovesCurrentAndEarlierRows() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.cancelStage = "copy"; backend.cancelAt = 2;
        List<File> inputs = sources();
        expect(CancellationException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Arrays.asList(1, 2), backend.deleted); assertTrue(backend.published.isEmpty());
    }
    @Test public void cancellationAfterProgressStopsBeforeNextCreate() throws Exception {
        Control control = new Control(); control.cancelOnProgress = true; Backend backend = new Backend(control);
        List<File> inputs = sources();
        expect(CancellationException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Collections.singletonList(1), backend.created); assertTrue(backend.published.isEmpty());
    }
    @Test public void cancellationDuringFirstPublishRemovesAllBatchRows() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.cancelStage = "publish";
        List<File> inputs = sources();
        expect(CancellationException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Collections.singletonList(1), backend.published); assertEquals(0, control.commitCount);
    }
    @Test public void cancellationDuringFinalPublishIsCaughtByAtomicCommit() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control); backend.cancelStage = "publish"; backend.cancelAt = 3;
        List<File> inputs = sources();
        expect(CancellationException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Arrays.asList(1, 2, 3), backend.published); assertEquals(1, control.commitCount);
    }
    @Test public void cancellationAtCommitRollsBackEveryPublishedRow() throws Exception {
        Control control = new Control(); control.cancelOnCommit = true; Backend backend = new Backend(control);
        List<File> inputs = sources();
        expect(CancellationException.class, () -> GalleryTransaction.save(inputs, backend, control)); rolledBack(backend, control);
        assertEquals(Arrays.asList(1, 2, 3), backend.published);
    }
    @Test public void cleanupFailureRemainsExplicitAndDoesNotStopOtherDeletes() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control);
        backend.failStage = "publish"; backend.deleteFailure = 1;
        List<File> inputs = sources();
        IOException failure = expect(IOException.class, () -> GalleryTransaction.save(inputs, backend, control));
        assertTrue(failure.getMessage().contains("部分图片未能清理"));
        assertEquals("injected delete", failure.getCause().getMessage());
        assertEquals(Arrays.asList(1, 2, 3), backend.deleted); assertEquals(new LinkedHashSet<>(Arrays.asList(99, 1)), backend.present);
        assertFalse(control.committed);
    }
    @Test public void cleanupFailureCannotBeMisreportedAsCleanCancellation() throws Exception {
        Control control = new Control(); Backend backend = new Backend(control);
        backend.cancelStage = "copy"; backend.cancelAt = 2; backend.deleteFailure = 1;
        List<File> inputs = sources();
        IOException failure = expect(IOException.class, () -> GalleryTransaction.save(inputs, backend, control));
        assertTrue(failure.getMessage().contains("部分图片未能清理")); assertEquals(Arrays.asList(1, 2), backend.deleted);
        assertFalse(control.committed);
    }
    @Test public void duplicateSourceEntriesRemainDistinctExplicitImages() throws Exception {
        File image = source("same.png"); Control control = new Control(); Backend backend = new Backend(control);
        assertEquals(Arrays.asList(1, 2), GalleryTransaction.save(Arrays.asList(image, image), backend, control));
        assertEquals(Arrays.asList(1, 2), backend.created); assertEquals(Arrays.asList(1, 2), backend.published);
        assertTrue(backend.deleted.isEmpty()); assertEquals(1, control.commitCount);
    }
}
