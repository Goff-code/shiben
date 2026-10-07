package cn.focusledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Collections;

/** Application-scoped export jobs. No bitmap rendering, DB scan or document copy on the UI thread. */
public final class ExportWorker {
    public enum Format { MARKDOWN, PNG }
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public interface Listener { void onChanged(Job job); }
    public static final int CLIPBOARD_LIMIT_BYTES = 256 * 1024;
    private static final int PREVIEW_CHARACTERS = 24_000;

    public static final class Request {
        public final V2Reports.DateRange range;
        public final Format format;
        public final boolean hideTaskDetails;
        public Request(V2Reports.DateRange range, Format format) { this(range, format, false); }
        public Request(V2Reports.DateRange range, Format format, boolean hideTaskDetails) {
            if (range == null || format == null) throw new IllegalArgumentException("请选择日期和导出格式");
            this.range = range; this.format = format; this.hideTaskDetails = format == Format.PNG && hideTaskDetails;
        }
    }
    public static final class Result {
        public final File file, previewImage;
        public final List<File> images;
        public final List<Uri> savedImages;
        public final String mimeType, fileName, markdownPreview, markdownForCopy;
        public final boolean previewTruncated;
        public final int dayCount, pageCount;
        Result(File file, String mimeType, File previewImage, String markdownPreview,
               String markdownForCopy, boolean previewTruncated, int dayCount, int pageCount) {
            this(file, mimeType, previewImage, markdownPreview, markdownForCopy, previewTruncated,
                    dayCount, pageCount, previewImage == null ? Collections.emptyList() : Collections.singletonList(previewImage), Collections.emptyList());
        }
        Result(File file, String mimeType, File previewImage, String markdownPreview,
               String markdownForCopy, boolean previewTruncated, int dayCount, int pageCount,
               List<File> images, List<Uri> savedImages) {
            this.file = file; this.fileName = file.getName(); this.mimeType = mimeType;
            this.previewImage = previewImage; this.markdownPreview = markdownPreview;
            this.markdownForCopy = markdownForCopy; this.previewTruncated = previewTruncated;
            this.dayCount = dayCount; this.pageCount = pageCount;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.savedImages = Collections.unmodifiableList(new ArrayList<>(savedImages));
        }
    }
    public final class Job {
        public final String id = UUID.randomUUID().toString();
        public volatile State state = State.QUEUED;
        public volatile int completed, total;
        public volatile String message = "等待导出", error;
        public volatile Result result;
        private volatile Listener listener;
        private boolean committed;
        private final AtomicBoolean cancelled = new AtomicBoolean(), callbackPending = new AtomicBoolean();
        private Job(Listener listener) { this.listener = listener; }
        public boolean isTerminal() { return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED; }
        public synchronized void cancel() {
            if (isTerminal() || committed) return;
            cancelled.set(true); message = "正在取消"; emit(this);
        }
        private synchronized void commit() { checkCancelled(); committed = true; }
        private void checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("已取消导出");
        }
    }

    private static volatile ExportWorker instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "shiben-export"); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
    });
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private ExportWorker(Context context) { app = context.getApplicationContext(); }
    public static ExportWorker get(Context context) {
        if (instance == null) synchronized (ExportWorker.class) {
            if (instance == null) instance = new ExportWorker(context);
        }
        return instance;
    }
    public Job start(Request request, Listener listener) {
        if (request == null) throw new IllegalArgumentException("导出请求不能为空");
        Job job = createJob(listener);
        executor.execute(() -> run(job, () -> generate(request, job)));
        return job;
    }
    public synchronized Job getJob(String id) { return jobs.get(id); }
    /** Call after rotation with the saved job ID; the latest state/result is delivered immediately. */
    public synchronized Job observe(String id, Listener listener) {
        Job job = jobs.get(id);
        if (job != null) { job.listener = listener; emit(job); }
        return job;
    }
    /** Call when the observing Activity is destroyed to avoid retaining it. Does not cancel the job. */
    public synchronized void detach(String id) {
        Job job = jobs.get(id); if (job != null) job.listener = null;
    }
    private synchronized Job createJob(Listener listener) {
        Job job = new Job(listener); jobs.put(job.id, job); emit(job);
        // Do not evict active jobs. Cached files remain independently usable after result eviction.
        if (jobs.size() > 32) {
            java.util.Iterator<Map.Entry<String, Job>> iterator = jobs.entrySet().iterator();
            while (jobs.size() > 32 && iterator.hasNext()) {
                Job old = iterator.next().getValue(); if (old != job && old.isTerminal() && old.listener == null) iterator.remove();
            }
        }
        return job;
    }
    private interface Work { Result perform() throws IOException; }
    private void run(Job job, Work work) {
        try {
            job.checkCancelled(); job.state = State.RUNNING; job.message = "正在生成文件"; emit(job);
            Result result = work.perform(); job.checkCancelled();
            job.result = result; job.state = State.SUCCEEDED; job.message = "文件已准备好";
        } catch (CancellationException cancelled) {
            job.state = State.CANCELLED; job.message = "已取消";
        } catch (Exception error) {
            job.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            job.message = "导出失败：" + job.error; job.state = State.FAILED;
        } catch (OutOfMemoryError insufficientMemory) {
            job.error = "可用内存不足，请缩小日期范围后重试"; job.message = job.error; job.state = State.FAILED;
        } finally { emit(job); }
    }
    private void emit(Job job) {
        if (!job.callbackPending.compareAndSet(false, true)) return;
        main.post(() -> {
            job.callbackPending.set(false);
            Listener listener = job.listener; if (listener != null) listener.onChanged(job);
        });
    }

    private Result generate(Request request, Job job) throws IOException {
        File directory = new File(app.getCacheDir(), "exports-v2/" + job.id);
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法创建导出文件夹");
        boolean success = false;
        try (LedgerStore store = new LedgerStore(app)) {
            store.ensureToday();
            List<LocalDate> days = V2Reports.selectDays(store.getRecordedDays(), request.range);
            if (days.isEmpty()) throw new IOException("所选日期范围内没有留存记录");
            // Capture every requested date once, using one active-timer reading for the whole export.
            // Later timer ticks or edits cannot change one page while another is being rendered.
            List<LedgerStore.DaySnapshot> snapshots = TimerCoordinator.get(app).snapshotsForDays(days, job::checkCancelled);
            job.total = snapshots.size();
            Result result = request.format == Format.MARKDOWN
                    ? generateMarkdown(request.range, snapshots, directory, job)
                    : generatePng(request.range, snapshots, directory, job, request.hideTaskDetails);
            success = true; return result;
        } finally {
            if (!success) removePartialFiles(directory);
        }
    }
    private Result generateMarkdown(V2Reports.DateRange range, List<LedgerStore.DaySnapshot> snapshots,
                                    File directory, Job job) throws IOException {
        File target = new File(directory, range.fileStem() + ".md"), temporary = new File(directory, target.getName() + ".part");
        try (FileOutputStream fileOut = new FileOutputStream(temporary); BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            for (int i = 0; i < snapshots.size(); i++) {
                job.checkCancelled();
                write(out, V2Reports.dailyMarkdown(snapshots.get(i)));
                if (i + 1 < snapshots.size()) write(out, "\n");
                job.completed = i + 1; job.message = "已生成 " + job.completed + " / " + job.total + " 天"; emit(job);
            }
            out.flush(); fileOut.getFD().sync();
        }
        moveFinished(temporary, target);
        String copy = target.length() <= CLIPBOARD_LIMIT_BYTES ? readUtf8(target, Integer.MAX_VALUE) : null;
        String preview = copy == null ? readUtf8(target, PREVIEW_CHARACTERS) : copy;
        boolean truncated = copy == null;
        if (preview.length() > PREVIEW_CHARACTERS) { preview = preview.substring(0, PREVIEW_CHARACTERS); truncated = true; }
        if (truncated) preview += "\n\n（预览节选；保存的 Markdown 文件保留全部日期与全部任务。）";
        return new Result(target, "text/markdown", null, preview, copy, truncated, snapshots.size(), 0);
    }
    private Result generatePng(V2Reports.DateRange range, List<LedgerStore.DaySnapshot> snapshots,
                               File directory, Job job) throws IOException {
        return generatePng(range, snapshots, directory, job, false);
    }
    private Result generatePng(V2Reports.DateRange range, List<LedgerStore.DaySnapshot> snapshots,
                               File directory, Job job, boolean hideTaskDetails) throws IOException {
        if (range.isAll() || !range.start.equals(range.end)) {
            RangeSummary summary = RangeSummary.create(range, snapshots, job::checkCancelled);
            File image = new File(directory, range.fileStem() + "_汇总.png");
            RangeSummaryRenderer.render(app, summary, image, job::checkCancelled, hideTaskDetails);
            job.completed = snapshots.size();
            return new Result(image, "image/png", image, null, null, false, snapshots.size(), 1);
        }
        List<File> images = new ArrayList<>();
        // Offline photo asset is bounded and decoded only once for the whole gallery.
        Bitmap background = GraffitiPoster.decodeBackground(app.getResources(), job::checkCancelled);
        if (background == null) throw new IOException("无法读取导出背景");
        try {
            for (LedgerStore.DaySnapshot snapshot : snapshots) {
                job.checkCancelled();
                List<Page> pages = hideTaskDetails ? Collections.singletonList(new Page()) : planPages(snapshot);
                for (int i = 0; i < pages.size(); i++) {
                    job.checkCancelled();
                    String name = "时本_" + snapshot.day + (pages.size() == 1 ? "" : String.format(java.util.Locale.ROOT, "_第%03d页", i + 1)) + ".png";
                    File file = new File(directory, name);
                    renderPage(snapshot, pages.get(i), i + 1, pages.size(), background, file, job, hideTaskDetails); images.add(file);
                }
                job.completed++; job.message = "已生成 " + job.completed + " / " + job.total + " 天 · " + images.size() + " 页"; emit(job);
            }
        } finally { background.recycle(); }
        return new Result(images.get(0), "image/png", images.get(0), null, null, false,
                snapshots.size(), images.size(), images, Collections.emptyList());
    }

    /** Explicit save action; source PNGs have been fully generated before any album row is inserted. */
    public Job saveToGallery(Result source, Listener listener) {
        if (source == null || source.images.isEmpty() || !"image/png".equals(source.mimeType))
            throw new IllegalArgumentException("请先生成 PNG 图片");
        Job job = createJob(listener);
        executor.execute(() -> run(job, () -> {
            job.message = "正在保存到相册"; job.total = source.images.size(); emit(job);
            List<Uri> saved = GalleryWriter.save(app, source.images, job.id.substring(0, 8), new GalleryTransaction.Control() {
                @Override public void checkCancelled() { job.checkCancelled(); }
                @Override public void progress(int completed, int total) {
                    job.completed = completed; job.total = total;
                    job.message = "正在保存到相册 " + completed + " / " + total; emit(job);
                }
                @Override public void commit() { job.commit(); }
            });
            return new Result(source.file, source.mimeType, source.previewImage, null, null, false,
                    source.dayCount, source.pageCount, source.images, saved);
        }));
        return job;
    }

    /** User supplies the URI returned by ACTION_CREATE_DOCUMENT. Never launches a picker itself. */
    public Job copyToDocument(File source, Uri destination, Listener listener) {
        if (source == null || destination == null) throw new IllegalArgumentException("请先选择保存位置");
        Job job = createJob(listener);
        executor.execute(() -> run(job, () -> {
            job.message = "正在保存到所选位置"; emit(job);
            try (OutputStream out = app.getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new IOException("无法写入所选位置");
                copyFile(source, out, job); out.flush();
            } catch (CancellationException cancelled) {
                job.error = "复制已取消，所选位置可能保留未写完的文件"; throw cancelled;
            }
            job.completed = 1; job.total = 1;
            String name = source.getName();
            String mime = name.endsWith(".png") ? "image/png" : "text/markdown";
            return new Result(source, mime, null, null, null, false, 0, 0);
        }));
        return job;
    }

    private static void write(OutputStream out, String value) throws IOException { out.write(value.getBytes(StandardCharsets.UTF_8)); }
    private void copyFile(File source, OutputStream destination, Job job) throws IOException {
        byte[] buffer = new byte[32768]; int read;
        try (FileInputStream input = new FileInputStream(source)) {
            while ((read = input.read(buffer)) != -1) { job.checkCancelled(); destination.write(buffer, 0, read); }
        }
    }
    private static void moveFinished(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    private static String readUtf8(File file, int maxChars) throws IOException {
        StringBuilder out = new StringBuilder();
        try (java.io.Reader reader = new java.io.InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096]; int read;
            while (out.length() < maxChars && (read = reader.read(buffer, 0, Math.min(buffer.length, maxChars - out.length()))) != -1)
                out.append(buffer, 0, read);
        }
        return out.toString();
    }
    private static void removePartialFiles(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        directory.delete();
    }

    // Fixed-size pages bound memory independently of task count and date range.
    static final int PAGE_WIDTH = 1080, PAGE_MAX_HEIGHT = 2560;
    private static final int BG = 0xff0b1215, INK = 0xfff3f6f5, TEAL = 0xff00ded2,
            MUTED = 0xffb3c4c5, LINE = 0xff334447;
    private static final int TASK_TOP = 1532, PAGE_BOTTOM = 92, CONTENT_BOTTOM = PAGE_MAX_HEIGHT - PAGE_BOTTOM;
    private static final int TITLE_SIZE = 38, LINE_HEIGHT = 49, ROW_BASE = 66, MAX_LINES_PER_FRAGMENT = 8;
    private static final int TASK_TITLE_X = 198, TASK_TITLE_WIDTH = 710;
    private static final class Fragment {
        LedgerStore.DayTask task;
        List<String> lines;
        boolean continuation, compact;
        int height() { return compact ? 96 : ROW_BASE + lines.size() * LINE_HEIGHT; }
    }
    private static final class Page {
        final List<Fragment> rows = new ArrayList<>();
        int height = TASK_TOP;
    }
    private static List<Page> planPages(LedgerStore.DaySnapshot snapshot) {
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG); title.setTextSize(TITLE_SIZE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        List<Page> pages = new ArrayList<>(); Page current = new Page();
        for (LedgerStore.DayTask task : V2Reports.orderedTasks(snapshot)) {
            List<String> lines = wrapText(task.title == null ? "未命名" : task.title, title, TASK_TITLE_WIDTH);
            for (int start = 0; start < lines.size(); start += MAX_LINES_PER_FRAGMENT) {
                Fragment fragment = new Fragment(); fragment.task = task; fragment.continuation = start > 0;
                fragment.lines = new ArrayList<>(lines.subList(start, Math.min(lines.size(), start + MAX_LINES_PER_FRAGMENT)));
                fragment.compact = lines.size() == 1 && title.measureText(lines.get(0)) <= 230;
                if (!current.rows.isEmpty() && current.height + fragment.height() > CONTENT_BOTTOM) {
                    pages.add(current); current = new Page();
                }
                current.rows.add(fragment); current.height += fragment.height();
            }
        }
        if (!current.rows.isEmpty() || pages.isEmpty()) pages.add(current);
        return pages;
    }
    /** Wraps every Unicode code point; never ellipsizes or drops the remainder of a task name. */
    static List<String> wrapText(String text, Paint paint, float width) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) { lines.add("未命名"); return lines; }
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (paragraph.isEmpty()) { lines.add(""); continue; }
            int offset = 0;
            while (offset < paragraph.length()) {
                int count = paint.breakText(paragraph, offset, paragraph.length(), true, width, null);
                if (count <= 0) count = Character.charCount(paragraph.codePointAt(offset));
                int end = Math.min(paragraph.length(), offset + count);
                if (end < paragraph.length() && Character.isHighSurrogate(paragraph.charAt(end - 1))
                        && Character.isLowSurrogate(paragraph.charAt(end))) end--;
                if (end == offset) end += Character.charCount(paragraph.codePointAt(offset));
                lines.add(paragraph.substring(offset, end)); offset = end;
            }
        }
        return lines;
    }
    private void renderPage(LedgerStore.DaySnapshot snapshot, Page page, int pageNumber,
                                   int pageCount, Bitmap background, File target, Job job) throws IOException {
        renderPage(snapshot, page, pageNumber, pageCount, background, target, job, false);
    }
    private void renderPage(LedgerStore.DaySnapshot snapshot, Page page, int pageNumber,
                                   int pageCount, Bitmap background, File target, Job job, boolean hideTaskDetails) throws IOException {
        int height = hideTaskDetails ? GraffitiPoster.HEIGHT : Math.min(PAGE_MAX_HEIGHT, Math.max(1730, page.height + PAGE_BOTTOM));
        Bitmap bitmap = Bitmap.createBitmap(PAGE_WIDTH, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(BG);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            GraffitiPoster.drawDay(app, canvas, background, snapshot, job::checkCancelled);
            if (!hideTaskDetails) {
            paint.setColor(LINE); canvas.drawRect(102, 1458, 978, 1460, paint);
            text(canvas, paint, "当日任务与完成度", 106, 1516, 40, INK, true);

            int y = TASK_TOP;
            if (page.rows.isEmpty()) text(canvas, paint, snapshot.completeSnapshot ? "无" : "未知", 106, y + 78, 36, MUTED, false);
            for (Fragment row : page.rows) {
                job.checkCancelled(); LedgerStore.DayTask task = row.task;
                drawTaskBadge(canvas, paint, task.title, 143, y + 43, row.continuation);
                int lineY = y + (row.compact ? 54 : 44);
                for (String line : row.lines) {
                    text(canvas, paint, line, TASK_TITLE_X, lineY, TITLE_SIZE, INK, false); lineY += LINE_HEIGHT;
                }
                int barY = row.compact ? y + 34 : lineY - 17;
                int barX = row.compact ? 462 : TASK_TITLE_X;
                paint.setColor(LINE); canvas.drawRoundRect(new RectF(barX, barY, 846, barY + 16), 8, 8, paint);
                if (task.progress > 0) {
                    paint.setColor(TEAL); canvas.drawRoundRect(new RectF(barX, barY,
                            barX + (846 - barX) * Math.min(100, task.progress) / 100f, barY + 16), 8, 8, paint);
                }
                paint.setTextAlign(Paint.Align.RIGHT);
                text(canvas, paint, V2Reports.taskProgress(task.progress), 973, barY + 20, 31, INK, false);
                paint.setTextAlign(Paint.Align.LEFT);
                y += row.height();
            }
            if (pageCount > 1) {
                paint.setTextAlign(Paint.Align.RIGHT);
                text(canvas, paint, "第 " + pageNumber + " / " + pageCount + " 页", 974, height - 68, 23, MUTED, false);
                paint.setTextAlign(Paint.Align.LEFT);
            }
            }
            File temporary = new File(target.getParentFile(), target.getName() + ".part");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("无法生成 PNG");
                out.getFD().sync();
            }
            moveFinished(temporary, target);
        } finally { bitmap.recycle(); }
    }
    /** Decorative vector cues match the approved card and do not add report metadata. */
    private static void drawTaskBadge(Canvas canvas, Paint paint, String title, float cx, float cy, boolean continuation) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xffd4f1ee); canvas.drawCircle(cx, cy, 36, paint);
        if (continuation) {
            paint.setTextAlign(Paint.Align.CENTER);
            text(canvas, paint, "续", cx, cy + 11, 30, 0xff167e83, false);
            paint.setTextAlign(Paint.Align.LEFT); return;
        }
        String name=title==null?"":title.toLowerCase(java.util.Locale.ROOT);
        canvas.save(); canvas.translate(cx - 24, cy - 24); canvas.scale(48f / 28, 48f / 28);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setColor(0xff148d96);
        Path path=new Path();
        if (name.contains("阅读") || name.contains("读书") || name.contains("看书") || name.contains("read") || name.contains("book")) {
            path.moveTo(14,6);path.cubicTo(10,3,6,3,3,5);path.lineTo(3,24);
            path.cubicTo(7,22,11,22,14,25);path.cubicTo(17,22,21,22,25,24);
            path.lineTo(25,5);path.cubicTo(21,3,17,3,14,6);path.lineTo(14,25);canvas.drawPath(path,paint);
        } else if (name.contains("web3") || name.contains("web 3")) {
            canvas.save();canvas.rotate(-45,14,14);
            canvas.drawRoundRect(new RectF(2,9,17,19),5,5,paint);canvas.drawRoundRect(new RectF(11,9,26,19),5,5,paint);
            canvas.restore();
        } else if (name.contains("英语") || name.contains("english")) {
            path.moveTo(2,10);path.lineTo(14,4);path.lineTo(26,10);path.lineTo(14,16);path.close();
            canvas.drawPath(path,paint);path.rewind();path.moveTo(7,13);path.lineTo(7,21);
            path.quadTo(14,26,21,21);path.lineTo(21,13);canvas.drawPath(path,paint);canvas.drawLine(26,10,26,20,paint);
        } else if (name.contains("代码") || name.contains("编程") || name.contains("视频") || name.contains("code") || name.contains("coding")) {
            canvas.drawLine(8,7,2,14,paint);canvas.drawLine(2,14,8,21,paint);
            canvas.drawLine(20,7,26,14,paint);canvas.drawLine(26,14,20,21,paint);canvas.drawLine(17,3,11,25,paint);
        } else {
            canvas.drawRoundRect(new RectF(5,3,23,25),2,2,paint);
            canvas.drawLine(9,9,19,9,paint);canvas.drawLine(9,14,19,14,paint);canvas.drawLine(9,19,16,19,paint);
        }
        canvas.restore();paint.setStyle(Paint.Style.FILL);
    }
    private static void fittedText(Canvas canvas, Paint paint, String value, float x, float y,
                                   float size, float maxWidth, int color, boolean bold) {
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(size);
        float width = paint.measureText(value);
        text(canvas, paint, value, x, y, width > maxWidth ? size * maxWidth / width : size, color, bold);
    }
    private static void text(Canvas canvas, Paint paint, String value, float x, float y, float size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        canvas.drawText(value, x, y, paint);
    }
}
