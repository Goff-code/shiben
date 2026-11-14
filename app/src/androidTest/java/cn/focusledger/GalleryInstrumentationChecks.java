package cn.focusledger;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** App-owned disposable PNG fixtures only. Never clears app data or another MediaStore row. */
public final class GalleryInstrumentationChecks {
    private GalleryInstrumentationChecks() { }
    private static void check(boolean value, String name, Consumer<String> report) {
        if (!value) throw new AssertionError(name);
        report.accept(name);
    }
    private static final class Control implements GalleryTransaction.Control {
        boolean cancelled, committed, cancelOnCommit;
        int completed;
        @Override public void checkCancelled() { if (cancelled) throw new CancellationException("injected cancellation"); }
        @Override public void progress(int completed, int total) { this.completed = completed; }
        @Override public void commit() { if (cancelOnCommit) cancelled = true; checkCancelled(); committed = true; }
    }
    /** Injects failures around actual MediaStore writes, never a fake resolver. */
    private static final class Backend implements GalleryTransaction.Backend<Uri> {
        final GalleryWriter.MediaBackend delegate;
        final List<Uri> created = new ArrayList<>();
        final List<Uri> published = new ArrayList<>();
        final Control control;
        int creates, copies, publishes, copyChecks;
        String failure, cancellation;
        boolean cleanupFailure;
        Backend(Context context, String id, Control control) {
            delegateResolver = context.getContentResolver();
            delegate = new GalleryWriter.MediaBackend(delegateResolver, id); this.control = control;
        }
        @Override public Uri create(File source, int index) throws IOException {
            creates++;
            if ("create".equals(failure) && creates == 2) throw new IOException("injected MediaStore create failure");
            Uri uri = delegate.create(source, index); created.add(uri); return uri;
        }
        @Override public void copy(File source, Uri uri, Runnable checkCancelled) throws IOException {
            copies++;
            if ("copy".equals(failure) && copies == 2) {
                // Leave a genuine partial destination behind for transaction rollback.
                try (java.io.OutputStream out = delegateResolver.openOutputStream(uri, "w")) {
                    if (out == null) throw new IOException("missing MediaStore stream");
                    out.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
                }
                throw new IOException("injected MediaStore copy failure");
            }
            final int[] checksForThisImage = {0};
            delegate.copy(source, uri, () -> {
                copyChecks++;
                checksForThisImage[0]++;
                // The second cancellation check follows at least one genuine chunk write.
                if ("copy".equals(cancellation) && copies == 2 && checksForThisImage[0] >= 2) control.cancelled = true;
                checkCancelled.run();
            });
        }
        final ContentResolver delegateResolver;
        @Override public void publish(Uri uri) throws IOException {
            publishes++;
            if ("publish".equals(failure) && publishes == 2) throw new IOException("injected MediaStore publish failure");
            delegate.publish(uri); published.add(uri);
            if ("publish".equals(cancellation)) control.cancelled = true;
        }
        @Override public void delete(Uri uri) throws IOException {
            if (cleanupFailure && uri.equals(created.get(0))) throw new IOException("injected MediaStore cleanup failure");
            delegate.delete(uri);
        }
    }
    static int countBatch(Context context, String batchId) {
        String selection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                new String[]{MediaStore.Images.Media._ID}, selection, new String[]{"%" + batchId + "%"}, null)) {
            if (cursor == null) throw new AssertionError("MediaStore query returned no cursor");
            return cursor.getCount();
        }
    }
    static void verifyImage(Context context, Uri uri, File source, Consumer<String> report) throws Exception {
        String[] projection = {MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.DISPLAY_NAME};
        try (Cursor row = context.getContentResolver().query(uri, projection, null, null, null)) {
            check(row != null && row.moveToFirst() && row.getCount() == 1, "saved image has exactly one real MediaStore row", report);
            check("image/png".equals(row.getString(0)), "saved MediaStore MIME is image/png", report);
            check("Pictures/时本/".equals(row.getString(1)), "saved image belongs to Pictures/时本", report);
            check(row.getInt(2) == 0, "saved image is published with IS_PENDING=0", report);
            check(row.getString(3).endsWith(".png") && !row.getString(3).endsWith(".zip"), "saved display name is PNG, never ZIP", report);
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            check(input != null && Arrays.equals(Files.readAllBytes(source.toPath()), readAll(input)),
                    "gallery bytes exactly equal the generated source PNG", report);
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            check(bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0,
                    "published gallery PNG opens as a readable bitmap", report);
            if (bitmap != null) bitmap.recycle();
        }
    }
    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[32768]; int count;
        while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }
    private static boolean exists(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        }
    }
    private static void removeCreated(Context context, List<Uri> rows) {
        for (Uri row : rows) if (exists(context, row)) {
            if (context.getContentResolver().delete(row, null, null) != 1) throw new AssertionError("Could not clean test-created row " + row);
        }
    }
    private static File writePng(File directory, String name) throws IOException {
        File file = new File(directory, name); Bitmap bitmap = Bitmap.createBitmap(173, 127, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
            bitmap.setPixel(x, y, 0xff000000 | ((x * 37 + y * 19) & 255) << 16 | ((x * 13 + y * 43) & 255) << 8 | ((x * 29 + y * 17) & 255));
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Could not make PNG fixture");
        } finally { bitmap.recycle(); }
        return file;
    }
    private static ExportWorker.Result render(Context context, V2Reports.DateRange range,
                                               List<LedgerStore.DaySnapshot> snapshots, File root) throws Exception {
        return render(context, range, snapshots, root, false);
    }
    private static ExportWorker.Result render(Context context, V2Reports.DateRange range,
                                               List<LedgerStore.DaySnapshot> snapshots, File root, boolean hidden) throws Exception {
        File directory = new File(root, UUID.randomUUID().toString());
        if (!directory.mkdir()) throw new IOException("Cannot make render fixture directory");
        ExportWorker worker = ExportWorker.get(context);
        Constructor<ExportWorker.Job> constructor = ExportWorker.Job.class.getDeclaredConstructor(ExportWorker.class, ExportWorker.Listener.class);
        constructor.setAccessible(true);
        ExportWorker.Job job = constructor.newInstance(worker, null);
        Method method = ExportWorker.class.getDeclaredMethod("generatePng", V2Reports.DateRange.class, List.class, File.class, ExportWorker.Job.class, boolean.class);
        method.setAccessible(true);
        try { return (ExportWorker.Result) method.invoke(worker, range, snapshots, directory, job, hidden); }
        catch (InvocationTargetException error) { throw new AssertionError("Real PNG renderer failed", error.getCause()); }
    }
    private static void renderHiddenPreview(Context context, LedgerStore.DaySnapshot snapshot, File output) throws Exception {
        Class<?> pageClass = Class.forName("cn.focusledger.ExportWorker$Page");
        Constructor<?> pageConstructor = pageClass.getDeclaredConstructor(); pageConstructor.setAccessible(true);
        Object emptyPage = pageConstructor.newInstance();
        Constructor<ExportWorker.Job> constructor = ExportWorker.Job.class.getDeclaredConstructor(ExportWorker.class, ExportWorker.Listener.class);
        constructor.setAccessible(true);
        ExportWorker.Job job = constructor.newInstance(ExportWorker.get(context), null);
        Method renderer = ExportWorker.class.getDeclaredMethod("renderPage", LedgerStore.DaySnapshot.class,
                pageClass, int.class, int.class, Bitmap.class, File.class, ExportWorker.Job.class, boolean.class);
        renderer.setAccessible(true);
        Bitmap background = GraffitiPoster.decodeBackground(context.getResources(), null);
        if (background == null) throw new AssertionError("Missing real PNG background");
        try { renderer.invoke(ExportWorker.get(context), snapshot, emptyPage, 1, 1, background, output, job, true); }
        catch (InvocationTargetException error) { throw new AssertionError("Real hidden PNG renderer failed", error.getCause()); }
        finally { background.recycle(); }
    }
    public static void runHiddenPreview(Context context, Consumer<String> report) throws Exception {
        File directory = new File(context.getFilesDir(), "qa-v203");
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Could not make preview fixture directory");
        LocalDate date = LocalDate.of(2026, 10, 6);
        List<LedgerStore.DayTask> originalRows = Arrays.asList(
                new LedgerStore.DayTask("private-a", "机密任务阿尔法PRIVATE_ONE", 1, 100, .71, 7000000, false, null),
                new LedgerStore.DayTask("private-b", "机密任务贝塔PRIVATE_TWO", 2, 75, .29, 4057000, false, null));
        List<LedgerStore.DayTask> changedRows = Arrays.asList(
                new LedgerStore.DayTask("secret-x", "绝不可泄露OMEGA_UNIQUE_926", 1, 91, .4, 5000000, false, null),
                new LedgerStore.DayTask("secret-y", "绝不可泄露DELTA_UNIQUE_174", 2, 93, .6, 6057000, false, null));
        LedgerStore.DaySnapshot original = new LedgerStore.DaySnapshot(date, originalRows, 92.75, true, 11057000, "A-");
        LedgerStore.DaySnapshot changed = new LedgerStore.DaySnapshot(date, changedRows, 92.75, true, 11057000, "A-");
        File preview = new File(directory, "hidden-preview.png"), comparison = new File(directory, "hidden-preview-compare.png");
        try {
            renderHiddenPreview(context, original, preview); renderHiddenPreview(context, changed, comparison);
            check(preview.isFile() && preview.length() > 0, "real hidden preview created: qa-v203/hidden-preview.png", report);
            check(Arrays.equals(Files.readAllBytes(preview.toPath()), Files.readAllBytes(comparison.toPath())),
                    "hidden PNG bytes are identical after all private task names, identifiers and per-task values change", report);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(preview.getAbsolutePath(), bounds);
            check(bounds.outWidth == 1080 && bounds.outHeight == 1440 && "image/png".equals(bounds.outMimeType),
                    "hidden daily preview is a readable compact 1080x1440 PNG", report);
        } finally { if (comparison.exists() && !comparison.delete()) throw new IOException("Could not clean comparison fixture"); }
    }
    private static LedgerStore.DaySnapshot privacySnapshot(LocalDate day, int count, boolean changed) {
        List<LedgerStore.DayTask> rows = new ArrayList<>();
        String marker = changed ? "私密OMEGA173-" : "私密ALPHA926-";
        String longName = marker + String.join("", Collections.nCopies(100, "不应出现在图片中的私人任务细节🧠"));
        long duration = 11057000L;
        for (int i = 0; i < count; i++) rows.add(new LedgerStore.DayTask(marker + i, longName + i,
                i + 1, changed ? (i == 0 ? 95 : 90) : (i == 0 ? 100 : 75),
                i == 0 ? (changed ? .55 : .71) : (changed ? .45 : .29) / (count - 1),
                duration / count + (i == 0 ? duration % count : 0), false, null));
        return new LedgerStore.DaySnapshot(day, rows, 92.75, true, duration, "A-");
    }
    private static void hiddenImages(Context context, File root, Consumer<String> report) throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 1);
        check(!new ExportWorker.Request(V2Reports.DateRange.day(day), ExportWorker.Format.PNG).hideTaskDetails
                        && new ExportWorker.Request(V2Reports.DateRange.day(day), ExportWorker.Format.PNG, true).hideTaskDetails
                        && !new ExportWorker.Request(V2Reports.DateRange.day(day), ExportWorker.Format.MARKDOWN, true).hideTaskDetails,
                "task privacy is explicit for PNG and cannot change Markdown request semantics", report);
        List<LedgerStore.DaySnapshot> original = Arrays.asList(privacySnapshot(day, 128, false), privacySnapshot(day.plusDays(1), 128, false));
        List<LedgerStore.DaySnapshot> changed = Arrays.asList(privacySnapshot(day, 2, true), privacySnapshot(day.plusDays(1), 2, true));
        List<V2Reports.DateRange> ranges = Arrays.asList(V2Reports.DateRange.day(day), V2Reports.DateRange.month(2026, 10),
                V2Reports.DateRange.year(2026), V2Reports.DateRange.custom(day, day.plusDays(1)), V2Reports.DateRange.all());
        String[] labels = {"daily", "monthly", "yearly", "custom", "all-records"};
        for (int i = 0; i < ranges.size(); i++) {
            boolean daily = i == 0;
            ExportWorker.Result image = render(context, ranges.get(i), daily ? original.subList(0, 1) : original, root, true);
            ExportWorker.Result comparison = render(context, ranges.get(i), daily ? changed.subList(0, 1) : changed, root, true);
            check(image.images.size() == 1 && image.pageCount == 1 && "image/png".equals(image.mimeType),
                    "hidden " + labels[i] + " export is one PNG even with 128 extremely long tasks", report);
            check(Arrays.equals(Files.readAllBytes(image.file.toPath()), Files.readAllBytes(comparison.file.toPath())),
                    "hidden " + labels[i] + " pixels and PNG metadata cannot reveal changed names, task counts or per-task values", report);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.file.getAbsolutePath(), bounds);
            check(bounds.outWidth == 1080 && bounds.outHeight == (daily ? 1440 : RangeSummaryRenderer.HIDDEN_TASK_HEIGHT),
                    "hidden " + labels[i] + " image remains compact without blank private-detail space", report);
            check(!image.fileName.contains("ALPHA926") && !image.fileName.contains("OMEGA173") && image.fileName.endsWith(".png")
                            && image.fileName.equals(comparison.fileName),
                    "hidden " + labels[i] + " output names contain no task identity or task-dependent suffix", report);
        }
    }
    private static LedgerStore.DaySnapshot snapshot(LocalDate date, int tasks) {
        List<LedgerStore.DayTask> rows = new ArrayList<>();
        for (int i = 0; i < tasks; i++) rows.add(new LedgerStore.DayTask("gallery-task-" + i,
                "相册验证任务 " + i + " 阅读记录🧠完整保存", i + 1, 60, Math.scalb(1d, -i - 1), 60000, false, null));
        return new LedgerStore.DaySnapshot(date, rows, 60, true, tasks * 60000L, "D");
    }
    private static ExportWorker.Job save(Context context, ExportWorker.Result result, List<Uri> owned,
                                          Consumer<String> report) throws Exception {
        ExportWorker worker = ExportWorker.get(context); CountDownLatch terminal = new CountDownLatch(1);
        boolean[] mainThread = {true};
        ExportWorker.Job job = worker.saveToGallery(result, value -> {
            mainThread[0] &= Looper.myLooper() == Looper.getMainLooper();
            if (value.isTerminal()) terminal.countDown();
        });
        try {
            check(terminal.await(90, TimeUnit.SECONDS), "gallery worker reaches terminal state", report);
            if (job.result != null) owned.addAll(job.result.savedImages);
            check(job.state == ExportWorker.State.SUCCEEDED && job.result != null,
                    "gallery worker reports success only after all images publish: " + job.error, report);
            check(mainThread[0], "gallery worker callbacks arrive on main thread", report);
            check(job.result.savedImages.size() == result.images.size() && job.completed == result.images.size(),
                    "gallery worker returns one published URI for every generated image", report);
            job.cancel();
            check(job.state == ExportWorker.State.SUCCEEDED, "cancellation after committed save cannot erase or relabel success", report);
            for (int i = 0; i < result.images.size(); i++) verifyImage(context, job.result.savedImages.get(i), result.images.get(i), report);
            return job;
        } finally { worker.detach(job.id); }
    }
    private static void awaitTerminal(ExportWorker.Job job) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (!job.isTerminal() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25);
        if (!job.isTerminal()) throw new AssertionError("Gallery job did not terminate");
    }
    private static void queuedCancel(Context context, ExportWorker.Result result, Consumer<String> report) throws Exception {
        ExportWorker worker = ExportWorker.get(context); Field field = ExportWorker.class.getDeclaredField("executor"); field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(worker);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.execute(() -> { entered.countDown(); try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        if (!entered.await(30, TimeUnit.SECONDS)) throw new AssertionError("Worker queue fixture timed out");
        ExportWorker.Job job = null;
        try {
            job = worker.saveToGallery(result, null);
            check(job.state == ExportWorker.State.QUEUED, "gallery save can be tested deterministically while queued", report);
            job.cancel(); job.cancel();
        } finally { release.countDown(); }
        try {
            awaitTerminal(job);
            check(job.state == ExportWorker.State.CANCELLED && job.result == null,
                    "queued cancellation is terminal and never reports saved images", report);
            check(countBatch(context, job.id.substring(0, 8)) == 0, "queued cancellation creates no MediaStore rows", report);
        } finally { worker.detach(job.id); }
    }
    private static void failures(Context context, List<File> sources, Uri unrelated, Consumer<String> report) throws Exception {
        for (String scenario : Arrays.asList("create", "copy", "publish", "cancel-copy", "cancel-publish", "cancel-commit", "cleanup")) {
            Control control = new Control(); String batch = "qa-gallery-" + UUID.randomUUID();
            Backend backend = new Backend(context, batch, control);
            if (scenario.startsWith("cancel-")) {
                backend.cancellation = scenario.substring(7); control.cancelOnCommit = "cancel-commit".equals(scenario);
            } else backend.failure = "cleanup".equals(scenario) ? "publish" : scenario;
            backend.cleanupFailure = "cleanup".equals(scenario);
            Throwable observed = null; List<Uri> result = null;
            try {
                try { result = GalleryTransaction.save(sources, backend, control); }
                catch (IOException | CancellationException error) { observed = error; }
                check(result == null && observed != null && !control.committed,
                        "real MediaStore " + scenario + " never returns false success", report);
                if (scenario.startsWith("cancel-")) check(observed instanceof CancellationException,
                        "real MediaStore " + scenario + " reports cancellation", report);
                if ("cancel-copy".equals(scenario)) check(backend.copyChecks >= 2 && backend.created.size() == 2,
                        "cancellation is injected inside a genuine MediaStore copy", report);
                if ("publish".equals(scenario) || "cancel-publish".equals(scenario) || "cancel-commit".equals(scenario))
                    check(!backend.published.isEmpty(), "rollback also covers already-published images: " + scenario, report);
                if (backend.cleanupFailure) {
                    check(observed instanceof IOException && observed.getMessage().contains("部分图片未能清理"),
                            "MediaStore rollback failure is explicit rather than success or clean cancellation", report);
                    check(countBatch(context, batch) == 1, "cleanup continues after a failed delete, leaving only the injected failure", report);
                } else check(countBatch(context, batch) == 0, "real MediaStore rollback removes all pending and published batch rows: " + scenario, report);
                check(exists(context, unrelated), "rollback preserves a separate previously saved image: " + scenario, report);
            } finally { removeCreated(context, backend.created); }
            check(countBatch(context, batch) == 0, "injected failure fixture leaves no gallery rows: " + scenario, report);
        }
    }
    private static void removeDirectory(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) { if (child.isDirectory()) removeDirectory(child); else if (!child.delete()) throw new IOException("Could not delete fixture " + child); }
        if (!directory.delete()) throw new IOException("Could not delete fixture directory " + directory);
    }
    public static void run(Context context, Consumer<String> report) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new AssertionError("Run gallery checks off main thread");
        if (Build.VERSION.SDK_INT < 29) throw new AssertionError("Gallery suite requires Android 10+");
        File root = new File(context.getCacheDir(), "qa-gallery-" + UUID.randomUUID());
        if (!root.mkdir()) throw new IOException("Could not create gallery test fixture");
        List<Uri> owned = new ArrayList<>();
        try {
            Set<String> forbidden = new HashSet<>(Arrays.asList("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                    "android.permission.MANAGE_EXTERNAL_STORAGE"));
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            for (String permission : forbidden) check(info.requestedPermissions == null || !Arrays.asList(info.requestedPermissions).contains(permission),
                    "gallery flow adds no permission: " + permission, report);
            Set<String> priorPermissions = new HashSet<>(Arrays.asList("android.permission.POST_NOTIFICATIONS", "android.permission.VIBRATE",
                    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                    "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"));
            check(info.requestedPermissions != null && priorPermissions.equals(new HashSet<>(Arrays.asList(info.requestedPermissions))),
                    "installed app retains its declared gallery and opt-in sync permission set", report);

            LocalDate day = LocalDate.of(2026, 10, 1);
            ExportWorker.Result single = render(context, V2Reports.DateRange.day(day), Collections.singletonList(snapshot(day, 1)), root);
            check(single.images.size() == 1 && single.pageCount == 1 && "image/png".equals(single.mimeType),
                    "actual single-day renderer produces one PNG result", report);
            ExportWorker.Job firstSave = save(context, single, owned, report);
            queuedCancel(context, single, report);

            ExportWorker.Result longDay = render(context, V2Reports.DateRange.day(day), Collections.singletonList(snapshot(day, 19)), root);
            check(longDay.images.size() > 1 && longDay.images.size() == longDay.pageCount && "image/png".equals(longDay.mimeType)
                            && !longDay.file.getName().endsWith(".zip"),
                    "actual long daily export retains separate PNG pages, never a ZIP", report);
            ExportWorker.Job multipleSave = save(context, longDay, owned, report);
            check(new HashSet<>(multipleSave.result.savedImages).size() == longDay.images.size(),
                    "every daily page saves as a distinct gallery image", report);

            ExportWorker.Result summary = render(context, V2Reports.DateRange.custom(day, day.plusDays(1)),
                    Arrays.asList(snapshot(day, 2), snapshot(day.plusDays(1), 2)), root);
            check(summary.dayCount == 2 && summary.pageCount == 1 && summary.images.size() == 1 && "image/png".equals(summary.mimeType),
                    "actual multi-date range generates exactly one summary PNG", report);
            save(context, summary, owned, report);
            hiddenImages(context, root, report);

            File first = writePng(root, "故障测试一.png"), second = writePng(root, "故障测试二.png");
            failures(context, Arrays.asList(first, second), firstSave.result.savedImages.get(0), report);

            String invalidBatch = "qa-gallery-" + UUID.randomUUID();
            File invalid = new File(root, "not-a-png.png"); Files.write(invalid.toPath(), new byte[]{1, 2, 3});
            boolean rejected = false;
            try { GalleryWriter.save(context, Arrays.asList(first, invalid), invalidBatch, new Control()); }
            catch (IOException expected) { rejected = true; }
            check(rejected && countBatch(context, invalidBatch) == 0,
                    "invalid later PNG is rejected before inserting the first gallery image", report);

            ExportWorker.Result stale = new ExportWorker.Result(first, "image/png", first, null, null, false, 1, 2,
                    Arrays.asList(first, new File(root, "missing-source.png")), Collections.emptyList());
            ExportWorker worker = ExportWorker.get(context); ExportWorker.Job failed = worker.saveToGallery(stale, null);
            try {
                awaitTerminal(failed);
                check(failed.state == ExportWorker.State.FAILED && failed.result == null && failed.error != null,
                        "missing-source gallery job explicitly fails without a success result", report);
                check(countBatch(context, failed.id.substring(0, 8)) == 0 && first.isFile(),
                        "failed gallery job creates no rows and preserves usable source PNG", report);
            } finally { worker.detach(failed.id); }
        } finally {
            removeCreated(context, owned);
            for (Uri uri : owned) if (exists(context, uri)) throw new AssertionError("Test gallery row remained after cleanup");
            removeDirectory(root);
        }
        check(true, "gallery suite cleans only its own files and exact created MediaStore rows", report);
    }
}
