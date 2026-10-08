package cn.focusledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
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
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static double gradeProgress(String grade) {
        switch (grade) {
            case "SSS+": case "SSS": case "SS": case "S": return 100;
            case "A+": return 97; case "A": return 94; case "A-": return 92.75;
            case "B+": return 87; case "B": return 84; case "B-": return 80;
            case "C": return 70; case "D": return 60; case "E": return 30;
            default: throw new AssertionError("Unexpected fixture grade");
        }
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

        Set<String> renderedHashes = new HashSet<>();
        for (GradeArtwork.Spec spec : GradeArtwork.all()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getAssets().open(spec.assetPath())) {
                byte[] block = new byte[65536]; int count;
                while ((count = in.read(block)) != -1) digest.update(block, 0, count);
            }
            check(hex(digest.digest()).equals(spec.sha256),
                    "bundled grade PNG is byte-identical to supplied original: " + spec.grade, report);
            Bitmap original;
            try (InputStream in = context.getAssets().open(spec.assetPath())) {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false;
                options.inSampleSize = GradeArtwork.sampleSize(spec.width, spec.height);
                original = BitmapFactory.decodeStream(in, null, options);
            }
            try {
                check(original != null && original.hasAlpha()
                                && original.getAllocationByteCount() <= GradeArtwork.MAX_SPRITE_PIXELS * 4L,
                        "original alpha retained with bounded native decode: " + spec.grade, report);
            } finally { if (original != null) original.recycle(); }
            GradeArtwork.Placement bounds = GradeArtwork.placement(spec);
            check(bounds.left >= GradeArtwork.SAFE_LEFT - .01f && bounds.right() <= GradeArtwork.SAFE_RIGHT + .01f
                            && bounds.top >= GradeArtwork.SAFE_TOP - .01f && bounds.bottom() <= GradeArtwork.SAFE_BOTTOM + .01f,
                    "whole original canvas fits safe poster envelope without clipping: " + spec.grade, report);
            double value = gradeProgress(spec.grade);
            check(spec.grade.equals(cn.focusledger.core.SettlementGrade.resolve(value, value == 100, spec.grade)),
                    "native fixture matches unchanged settlement rules: " + spec.grade, report);
            List<LedgerStore.DayTask> fixture = "A-".equals(spec.grade) ? tasks : Collections.singletonList(
                    new LedgerStore.DayTask("fixture", "私密任务不应出现在摘要", 1, (int) value, 1, 11057000, false, null));
            LedgerStore.DaySnapshot rank = new LedgerStore.DaySnapshot(day, fixture, value, true, 11057000, spec.grade);
            ExportWorker.Result rendered = render(context, rank, new File(root, "grades/" + spec.fileName.replace(".png", "")), true);
            BitmapFactory.decodeFile(rendered.file.getAbsolutePath(), size);
            check(size.outWidth == 1080 && size.outHeight == 1440 && rendered.images.size() == 1,
                    "production hidden PNG renders grade " + spec.grade + " at " + rendered.file.getAbsolutePath(), report);
            check(renderedHashes.add(hex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(rendered.file.toPath())))),
                    "each selected grade creates a distinct native PNG: " + spec.grade, report);
        }
        check(renderedHashes.size() == 13, "all thirteen grades were rendered through the production path", report);
        check(GradeArtwork.forGrade("未知") == null, "unknown stays explicit and never selects E artwork", report);
        List<LedgerStore.DayTask> completeTasks = Collections.singletonList(
                new LedgerStore.DayTask("complete", "完成任务", 1, 100, 1, 11057000, false, null));
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
