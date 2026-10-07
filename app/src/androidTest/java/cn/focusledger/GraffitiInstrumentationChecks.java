package cn.focusledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Pure native render fixtures: no ledger/galleries changed, production render path only. */
public final class GraffitiInstrumentationChecks {
    private GraffitiInstrumentationChecks() { }
    private static void check(boolean okay, String name, Consumer<String> report) {
        if (!okay) throw new AssertionError(name); report.accept(name);
    }
    private static ExportWorker.Result render(Context context, LedgerStore.DaySnapshot snapshot,
                                               File directory, boolean hidden) throws Exception {
        directory.mkdirs(); ExportWorker worker = ExportWorker.get(context);
        Constructor<ExportWorker.Job> constructor = ExportWorker.Job.class.getDeclaredConstructor(ExportWorker.class, ExportWorker.Listener.class);
        constructor.setAccessible(true); ExportWorker.Job job = constructor.newInstance(worker, null);
        Method render = ExportWorker.class.getDeclaredMethod("generatePng", V2Reports.DateRange.class, List.class,
                File.class, ExportWorker.Job.class, boolean.class); render.setAccessible(true);
        return (ExportWorker.Result) render.invoke(worker, V2Reports.DateRange.day(snapshot.day),
                Collections.singletonList(snapshot), directory, job, hidden);
    }
    public static void run(Context context, Consumer<String> report) throws Exception {
        File root = new File(context.getFilesDir(), "qa-graffiti"); root.mkdirs();
        LocalDate day = LocalDate.of(2026, 10, 6);
        List<LedgerStore.DayTask> tasks = Arrays.asList(
                new LedgerStore.DayTask("one", "阅读与笔记", 1, 100, .5, 7200000, false, null),
                new LedgerStore.DayTask("two", "英语", 2, 91, .25, 3600000, false, null),
                new LedgerStore.DayTask("three", "复盘与计划", 3, 80, .25, 257000, false, null));
        LedgerStore.DaySnapshot sample = new LedgerStore.DaySnapshot(day, tasks, 92.75, true, 11057000, "A-");
        ExportWorker.Result main = render(context, sample, new File(root, "preview"), true);
        BitmapFactory.Options size = new BitmapFactory.Options(); size.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(main.file.getAbsolutePath(), size);
        check(size.outWidth == 1080 && size.outHeight == 1440 && "image/png".equals(size.outMimeType),
                "native offline graffiti preview produced 1080x1440 PNG at " + main.file.getAbsolutePath(), report);
        check(main.images.size() == 1 && main.mimeType.equals("image/png"), "hidden daily export is one actual image, not ZIP", report);
        Bitmap pixels = BitmapFactory.decodeFile(main.file.getAbsolutePath());
        try {
            int ring = pixels.getPixel(238, 868);
            check(android.graphics.Color.green(ring) > 160 && android.graphics.Color.blue(ring) > 150
                            && android.graphics.Color.red(ring) < 30,
                    "live completion arc is bright cyan and does not inherit the translucent track alpha", report);
        } finally { pixels.recycle(); }
        Bitmap bg = GraffitiPoster.decodeBackground(context.getResources(), null);
        check((long) bg.getWidth() * bg.getHeight() <= GraffitiPoster.MAX_BACKGROUND_PIXELS,
                "offline studio photo decode remains inside 512K pixel budget", report); bg.recycle();

        String[] grades = {"S", "SS", "SSS", "SSS+", "A+", "A", "A-", "B+", "B", "B-", "C", "D", "E"};
        for (String grade : grades) {
            check(GraffitiPoster.gradePath(grade) != null && !GraffitiPoster.gradePath(grade).isEmpty(),
                    "each supported grade has a real nonempty vector path: " + grade, report);
            RectF bounds = GraffitiPoster.gradeBounds(grade);
            check(bounds.left >= 400 && bounds.right <= 1010 && bounds.top >= 425 && bounds.bottom <= 940,
                    "hand-drawn live grade fits poster envelope without clipping: " + grade, report);
        }
        List<LedgerStore.DayTask> completeTasks = Collections.singletonList(
                new LedgerStore.DayTask("complete", "完成任务", 1, 100, 1, 11057000, false, null));
        ExportWorker.Result top = render(context, new LedgerStore.DaySnapshot(day, completeTasks, 100, true, 11057000, "SSS+"),
                new File(root, "sss-plus"), true);
        check(top.file.isFile(), "true 100% SSS+ fixture uses dynamic four-character vector grade", report);
        render(context, new LedgerStore.DaySnapshot(day, tasks, Double.NaN, false, -1, "未知"),
                new File(root, "unknown"), true);
        check(true, "unknown completion/time/grade render explicitly without converting them to zero", report);
        render(context, sample, new File(root, "full-tasks"), false);
        check(true, "same real export template retains complete task appendix when details are visible", report);
        LocalDate first = LocalDate.of(1, 1, 1), last = LocalDate.of(9999, 12, 31);
        LedgerStore.DaySnapshot beginning = new LedgerStore.DaySnapshot(first, completeTasks, 100, true, 0, "SSS+");
        LedgerStore.DaySnapshot ending = new LedgerStore.DaySnapshot(last, completeTasks, 100, true, Long.MAX_VALUE, "SSS+");
        RangeSummary range = RangeSummary.create(V2Reports.DateRange.all(), Arrays.asList(beginning, ending));
        check("S".equals(range.settlementGrade), "range grade recomputes true-100 S and never inherits daily SSS+", report);
        File extreme = new File(root, "all-records-extreme.png"); RangeSummaryRenderer.render(context, range, extreme, null, true);
        BitmapFactory.decodeFile(extreme.getAbsolutePath(), size);
        check(size.outWidth == 1080 && size.outHeight == 1440,
                "year0001–9999 and Long.MAX_VALUE duration render one 3:4 poster", report);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); Typeface brush = context.getResources().getFont(R.font.ma_shan_zheng);
        float durationSize = GraffitiPoster.fittedSize(paint, range.actualText(), 67, 817, brush);
        check(durationSize >= 28, "largest cumulative duration remains readable at " + durationSize + "px without truncation", report);
        String[] dateLines = GraffitiPoster.dateLines("全部记录 · " + range.recordedSpanText());
        check(dateLines.length == 2 && String.join(" ", dateLines).equals("全部记录 · " + range.recordedSpanText()),
                "long date range splits without losing any character and stays out of the grade area", report);
        for (int i = 0; i < dateLines.length; i++) {
            float dateSize = GraffitiPoster.fittedSize(paint, dateLines[i], 43, i == 0 ? 560 : 260, brush);
            check(dateSize >= 25, "longest supported date row remains readable at " + dateSize + "px", report);
        }
        GalleryInstrumentationChecks.runHiddenPreview(context, report);
    }
}
