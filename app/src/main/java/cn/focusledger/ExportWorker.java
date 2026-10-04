package cn.focusledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        public Request(V2Reports.DateRange range, Format format) {
            if (range == null || format == null) throw new IllegalArgumentException("请选择日期和导出格式");
            this.range = range; this.format = format;
        }
    }
    public static final class Result {
        public final File file, previewImage;
        public final String mimeType, fileName, markdownPreview, markdownForCopy;
        public final boolean previewTruncated;
        public final int dayCount, pageCount;
        Result(File file, String mimeType, File previewImage, String markdownPreview,
               String markdownForCopy, boolean previewTruncated, int dayCount, int pageCount) {
            this.file = file; this.fileName = file.getName(); this.mimeType = mimeType;
            this.previewImage = previewImage; this.markdownPreview = markdownPreview;
            this.markdownForCopy = markdownForCopy; this.previewTruncated = previewTruncated;
            this.dayCount = dayCount; this.pageCount = pageCount;
        }
    }
    public final class Job {
        public final String id = UUID.randomUUID().toString();
        public volatile State state = State.QUEUED;
        public volatile int completed, total;
        public volatile String message = "等待导出", error;
        public volatile Result result;
        private volatile Listener listener;
        private final AtomicBoolean cancelled = new AtomicBoolean(), callbackPending = new AtomicBoolean();
        private Job(Listener listener) { this.listener = listener; }
        public boolean isTerminal() { return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED; }
        public void cancel() {
            if (isTerminal()) return;
            cancelled.set(true); message = "正在取消"; emit(this);
        }
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
            List<LocalDate> days = V2Reports.selectDays(store.getRecordedDays(), request.range);
            if (days.isEmpty()) throw new IOException("所选日期范围内没有留存记录");
            job.total = days.size();
            Result result = request.format == Format.MARKDOWN
                    ? generateMarkdown(store, request.range, days, directory, job)
                    : generatePng(store, request.range, days, directory, job);
            success = true; return result;
        } finally {
            if (!success) removePartialFiles(directory);
        }
    }
    private Result generateMarkdown(LedgerStore store, V2Reports.DateRange range, List<LocalDate> days,
                                    File directory, Job job) throws IOException {
        File target = new File(directory, range.fileStem() + ".md"), temporary = new File(directory, target.getName() + ".part");
        try (FileOutputStream fileOut = new FileOutputStream(temporary); BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            if (days.size() > 1) write(out, "# 时本记录\n\n范围：" + range.label() + "（包含起止日期）\n\n"
                    + "共 " + days.size() + " 个有留存记录的日期；未生成没有记录的日期。\n\n---\n\n");
            for (int i = 0; i < days.size(); i++) {
                job.checkCancelled(); LocalDate date = days.get(i);
                write(out, V2Reports.dailyMarkdown(store.getDaySnapshot(date)));
                if (i + 1 < days.size()) write(out, "\n---\n\n");
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
        return new Result(target, "text/markdown", null, preview, copy, truncated, days.size(), 0);
    }
    private Result generatePng(LedgerStore store, V2Reports.DateRange range, List<LocalDate> days,
                               File directory, Job job) throws IOException {
        List<File> images = new ArrayList<>();
        for (LocalDate day : days) {
            job.checkCancelled(); LedgerStore.DaySnapshot snapshot = store.getDaySnapshot(day);
            List<Page> pages = planPages(snapshot);
            for (int i = 0; i < pages.size(); i++) {
                job.checkCancelled();
                String name = "时本_" + day + (pages.size() == 1 ? "" : String.format(java.util.Locale.ROOT, "_第%03d页", i + 1)) + ".png";
                File file = new File(directory, name);
                renderPage(snapshot, pages.get(i), i + 1, pages.size(), file, job); images.add(file);
            }
            job.completed++; job.message = "已生成 " + job.completed + " / " + job.total + " 天 · " + images.size() + " 页"; emit(job);
        }
        if (images.size() == 1) return new Result(images.get(0), "image/png", images.get(0), null, null, false, days.size(), 1);
        File zip = new File(directory, range.fileStem() + "_PNG.zip"), temporary = new File(directory, zip.getName() + ".part");
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
            ZipEntry readme = new ZipEntry("导出说明.txt"); readme.setTime(0); out.putNextEntry(readme);
            write(out, "时本 PNG 导出\n日期范围：" + range.label() + "\n共 " + days.size() + " 天、" + images.size()
                    + " 张图片。\n长任务列表按页输出；每页显示相同的日总进度，任务依次续接，不能把各页总时长相加。\n"
                    + "每个任务的完整名称、优先级、权重、进度和实际专注均保留。\n" + V2Reports.LOCAL_DATA_WARNING + "\n");
            out.closeEntry();
            for (File file : images) {
                job.checkCancelled(); ZipEntry entry = new ZipEntry(file.getName()); entry.setTime(0); out.putNextEntry(entry);
                copyFile(file, out, job); out.closeEntry();
            }
        }
        moveFinished(temporary, zip);
        return new Result(zip, "application/zip", images.get(0), null, null, false, days.size(), images.size());
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
            String mime = name.endsWith(".png") ? "image/png" : name.endsWith(".zip") ? "application/zip" : "text/markdown";
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

    // A fixed-size page means PNG memory is bounded independently of task count or date range.
    static final int PAGE_WIDTH = 1080, PAGE_MAX_HEIGHT = 1600;
    private static final int BG = 0xfffcfaf6, INK = 0xff172331, TEAL = 0xff116b73, MUTED = 0xff697078, LINE = 0xffe3e4e3;
    private static final int TASK_TOP = 540, PAGE_BOTTOM = 112, CONTENT_BOTTOM = PAGE_MAX_HEIGHT - PAGE_BOTTOM;
    private static final int TITLE_SIZE = 38, LINE_HEIGHT = 49, ROW_BASE = 130, MAX_LINES_PER_FRAGMENT = 8;
    private static final class Fragment {
        LedgerStore.DayTask task;
        List<String> lines;
        boolean continuation;
        int height() { return ROW_BASE + lines.size() * LINE_HEIGHT; }
    }
    private static final class Page {
        final List<Fragment> rows = new ArrayList<>();
        int height = TASK_TOP;
    }
    private static List<Page> planPages(LedgerStore.DaySnapshot snapshot) {
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG); title.setTextSize(TITLE_SIZE); title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        List<Page> pages = new ArrayList<>(); Page current = new Page();
        for (LedgerStore.DayTask task : V2Reports.orderedTasks(snapshot)) {
            List<String> lines = wrapText(task.title == null ? "未命名" : task.title, title, PAGE_WIDTH - 220);
            for (int start = 0; start < lines.size(); start += MAX_LINES_PER_FRAGMENT) {
                Fragment fragment = new Fragment(); fragment.task = task; fragment.continuation = start > 0;
                fragment.lines = new ArrayList<>(lines.subList(start, Math.min(lines.size(), start + MAX_LINES_PER_FRAGMENT)));
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
    private static void renderPage(LedgerStore.DaySnapshot snapshot, Page page, int pageNumber,
                                   int pageCount, File target, Job job) throws IOException {
        int height = Math.min(PAGE_MAX_HEIGHT, Math.max(850, page.height + PAGE_BOTTOM));
        Bitmap bitmap = Bitmap.createBitmap(PAGE_WIDTH, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(BG);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            text(canvas, paint, "时本 · " + snapshot.day.getMonthValue() + "月" + snapshot.day.getDayOfMonth() + "日", 64, 92, 48, INK, true);
            text(canvas, paint, snapshot.day.toString(), 66, 139, 29, MUTED, false);
            text(canvas, paint, pageNumber + " / " + pageCount, 906, 88, 28, MUTED, false);
            float cx = 278, cy = 309, radius = 126;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(20); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(LINE);
            canvas.drawCircle(cx, cy, radius, paint);
            if (Double.isFinite(snapshot.totalProgress) && snapshot.totalProgress > 0) {
                paint.setColor(TEAL); canvas.drawArc(new RectF(cx - radius, cy - radius, cx + radius, cy + radius), -90,
                        (float) (Math.min(100, snapshot.totalProgress) * 3.6), false, paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
            text(canvas, paint, V2Reports.percent(snapshot.totalProgress), cx, cy + 23, 65, INK, false);
            paint.setTextAlign(Paint.Align.LEFT);
            text(canvas, paint, "优先级加权总进度", 477, 274, 36, INK, true);
            text(canvas, paint, "实际专注 " + V2Reports.duration(snapshot.totalActualMs), 477, 330, 30, MUTED, false);
            text(canvas, paint, "完整保留 " + snapshot.items.size() + " 项任务记录", 477, 377, 27, MUTED, false);
            text(canvas, paint, snapshot.completeSnapshot ? "按当日优先级排序 · 手动更新任务进度" : "旧版快照不完整 · 缺失信息标为“未知”", 64, 482, 29, MUTED, false);
            paint.setColor(LINE); canvas.drawRect(64, 511, PAGE_WIDTH - 64, 513, paint);
            int y = TASK_TOP;
            if (page.rows.isEmpty()) text(canvas, paint, "这一天没有留存任务清单", 64, y + 55, 32, MUTED, false);
            for (Fragment row : page.rows) {
                job.checkCancelled(); LedgerStore.DayTask task = row.task;
                String rank = task.rank < 1 ? "?" : Integer.toString(task.rank);
                text(canvas, paint, rank, 65, y + 43, 30, MUTED, false);
                int lineY = y + 43;
                for (String line : row.lines) { text(canvas, paint, line, 139, lineY, TITLE_SIZE, INK, false); lineY += LINE_HEIGHT; }
                String label = (task.archived ? "已归档 · " : "") + (row.continuation ? "名称续页 · " : "")
                        + "权重 " + V2Reports.weight(task.weight) + " · 实际 " + V2Reports.duration(task.actualMs);
                text(canvas, paint, label, 139, lineY + 4, 26, MUTED, false);
                int barY = lineY + 44;
                paint.setColor(LINE); canvas.drawRoundRect(new RectF(139, barY, 824, barY + 13), 7, 7, paint);
                if (task.progress > 0) {
                    paint.setColor(TEAL); canvas.drawRoundRect(new RectF(139, barY, 139 + 685 * Math.min(100, task.progress) / 100f, barY + 13), 7, 7, paint);
                }
                text(canvas, paint, V2Reports.taskProgress(task.progress), 862, barY + 17, 28, INK, false);
                paint.setColor(LINE); canvas.drawRect(64, y + row.height() - 10, PAGE_WIDTH - 64, y + row.height() - 8, paint);
                y += row.height();
            }
            text(canvas, paint, "第 " + pageNumber + " / " + pageCount + " 页 · " + (pageCount > 1 ? "任务列表依次续接，日总计勿重复相加" : "任务清单完整保留"), 64, height - 65, 25, MUTED, false);
            text(canvas, paint, "本机记录不会自动过期；卸载或清除数据前请先导出", 64, height - 28, 24, MUTED, false);
            File temporary = new File(target.getParentFile(), target.getName() + ".part");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("无法生成 PNG");
                out.getFD().sync();
            }
            moveFinished(temporary, target);
        } finally { bitmap.recycle(); }
    }
    private static void text(Canvas canvas, Paint paint, String value, float x, float y, float size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        canvas.drawText(value, x, y, paint);
    }
}
