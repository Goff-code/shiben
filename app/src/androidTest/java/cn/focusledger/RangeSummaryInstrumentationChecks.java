package cn.focusledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Real Android Paint/Bitmap checks. Pure fixture rendering only; never changes the ledger or gallery. */
public final class RangeSummaryInstrumentationChecks {
    private RangeSummaryInstrumentationChecks() { }

    private static void check(boolean value, String name, Consumer<String> report) {
        if (!value) throw new AssertionError(name);
        report.accept(name);
    }

    private static LedgerStore.DayTask task(String id, String name, int progress, long actual) {
        return new LedgerStore.DayTask(id, name, 1, progress, 1, actual, false, null);
    }

    private static Object plan(RangeSummary summary, boolean hide) throws Exception {
        Method method = RangeSummaryRenderer.class.getDeclaredMethod("plan", RangeSummary.class, Runnable.class, boolean.class);
        method.setAccessible(true);
        return method.invoke(null, summary, null, hide);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private static BitmapFactory.Options bounds(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options); return options;
    }

    public static void run(Context context, Consumer<String> report) throws Exception {
        File directory = new File(context.getFilesDir(), "qa-v203");
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create range PNG fixture directory");

        LocalDate first = LocalDate.of(1, 1, 1), last = LocalDate.of(9999, 12, 31);
        LedgerStore.DaySnapshot beginning = new LedgerStore.DaySnapshot(first,
                Collections.singletonList(task("a", "边界任务", 65, 0)), 65, true, 0);
        LedgerStore.DaySnapshot ending = new LedgerStore.DaySnapshot(last,
                Collections.singletonList(task("a", "边界任务最后名称", 65, Long.MAX_VALUE)), 65, true, Long.MAX_VALUE);
        RangeSummary extreme = RangeSummary.create(V2Reports.DateRange.all(), Arrays.asList(ending, beginning));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(32);
        String allDate = "全部记录 · " + extreme.recordedSpanText();
        String customDate = V2Reports.DateRange.custom(first, last).label();
        check(paint.measureText(allDate) <= 868 && paint.measureText(customDate) <= 868,
                "year 0001 through 9999 dates fit full PNG content width without truncation", report);

        paint.setTextSize(RangeSummaryRenderer.CAPTION_SIZE);
        Paint.FontMetrics captionMetrics = paint.getFontMetrics();
        double innerRadius = RangeSummaryRenderer.PROGRESS_RADIUS - RangeSummaryRenderer.RING_STROKE_WIDTH / 2d;
        double captionDy = Math.max(Math.abs(RangeSummaryRenderer.CAPTION_BASELINE_OFFSET + captionMetrics.ascent),
                Math.abs(RangeSummaryRenderer.CAPTION_BASELINE_OFFSET + captionMetrics.descent));
        double captionHalfChord = Math.sqrt(innerRadius * innerRadius - captionDy * captionDy);
        check(captionDy < innerRadius && paint.measureText(RangeSummaryRenderer.PROGRESS_CAPTION) / 2d + 8 <= captionHalfChord,
                "27px daily-average caption including font descent clears inner ring by at least 8px on both sides", report);

        Object extremePlan = plan(extreme, true);
        List<?> duration = (List<?>) field(extremePlan, "duration");
        paint.setTextSize(44); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        for (Object line : duration) check(paint.measureText((String) line) <= 468,
                "Long.MAX_VALUE attention duration line fits fixed readable 44px text", report);
        check(duration.size() <= 3 && 419 + (duration.size() - 1) * 54 + paint.getFontMetrics().descent < 581,
                "maximum cumulative duration stays above grade divider", report);
        paint.setTextSize(65);
        check(654 + paint.getFontMetrics().descent < RangeSummaryRenderer.HIDDEN_TASK_HEIGHT - 48,
                "hidden summary grade stays inside near-white panel", report);
        File boundary = new File(directory, "range-hidden-boundary.png");
        RangeSummaryRenderer.render(context, extreme, boundary, null, true);
        BitmapFactory.Options boundaryBounds = bounds(boundary);
        check(boundaryBounds.outWidth == 1080 && boundaryBounds.outHeight == 740 && "image/png".equals(boundaryBounds.outMimeType),
                "extreme dates and duration render one actual 1080x740 hidden PNG", report);

        String secret = "保密名称开始" + String.join("", Collections.nCopies(3000, "完整保密内容🧠")) + "保密名称结束";
        List<LedgerStore.DayTask> hugeRows = new ArrayList<>();
        for (int i = 0; i < 5000; i++) hugeRows.add(task("private-" + i, i == 0 ? secret : "私密任务" + i, 50, i));
        LocalDate day = LocalDate.of(2026, 10, 5);
        RangeSummary huge = RangeSummary.create(V2Reports.DateRange.month(2026, 10), Collections.singletonList(
                new LedgerStore.DaySnapshot(day, hugeRows, 65, true, 8_100_000)));
        RangeSummary changed = RangeSummary.create(V2Reports.DateRange.month(2026, 10), Collections.singletonList(
                new LedgerStore.DaySnapshot(day, Collections.singletonList(task("different-id", "完全不同的任务", 20, 17)),
                        65, true, 8_100_000)));
        Object hiddenPlan = plan(huge, true);
        check(((List<?>) field(hiddenPlan, "rows")).isEmpty() && (int) field(hiddenPlan, "height") == 740
                        && field(hiddenPlan, "taskNotes") == null && field(hiddenPlan, "notes") == null,
                "hidden range layout never wraps or lays out 5000 private tasks or their long names", report);
        File hidden = new File(directory, "range-hidden-preview.png"), compared = new File(directory, "range-hidden-compared.png");
        RangeSummaryRenderer.render(context, huge, hidden, null, true);
        RangeSummaryRenderer.render(context, changed, compared, null, true);
        check(Arrays.equals(Files.readAllBytes(hidden.toPath()), Files.readAllBytes(compared.toPath())),
                "hidden range PNG bytes ignore every task name, id, count, progress and per-task duration", report);
        check(bounds(hidden).outHeight == 740, "hidden oversized inventory still exports one compact readable PNG", report);
        Files.deleteIfExists(compared.toPath());

        File oversized = new File(directory, "range-oversized-must-not-exist.png");
        Files.deleteIfExists(oversized.toPath());
        boolean rejected = false;
        try { RangeSummaryRenderer.render(context, huge, oversized, null); }
        catch (IOException expected) { rejected = expected.getMessage().contains("单张") && expected.getMessage().contains("Markdown"); }
        check(rejected && !oversized.exists() && !new File(directory, oversized.getName() + ".part").exists(),
                "same unhidden oversized content fails before creating partial PNG or splitting/truncating", report);

        paint.setTextSize(38); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        String emoji = "🧠阅读🧠";
        check(emoji.equals(String.join("", RangeSummaryRenderer.wrap(emoji, paint, 1, 100, null))),
                "range title wrapping preserves surrogate pairs even at a one-pixel test width", report);

        LedgerStore.DaySnapshot one = new LedgerStore.DaySnapshot(day, Arrays.asList(
                task("read", "阅读", 100, 3_600_000), task("web", "Web3", 60, 2_700_000),
                task("english", "英语", 0, 900_000), task("code", "代码视频", 0, 900_000)), 65, true, 8_100_000);
        LedgerStore.DaySnapshot two = new LedgerStore.DaySnapshot(day.plusDays(1), Arrays.asList(
                task("read", "阅读与笔记", 80, 2_700_000), task("web", "Web3", 100, 2_700_000),
                task("english", "英语", 50, 900_000), task("code", "代码视频", 20, 900_000)), 73.75, true, 7_200_000);
        RangeSummary sample = RangeSummary.create(V2Reports.DateRange.custom(day, day.plusDays(1)), Arrays.asList(two, one));
        File preview = new File(directory, "range-summary-preview.png");
        RangeSummaryRenderer.render(context, sample, preview, null);
        BitmapFactory.Options previewBounds = bounds(preview);
        check(previewBounds.outWidth == 1080 && previewBounds.outHeight <= 8000 && "image/png".equals(previewBounds.outMimeType),
                "real four-task summary PNG generated for visual review: qa-v203/range-summary-preview.png", report);
        Bitmap decoded = BitmapFactory.decodeFile(preview.getAbsolutePath());
        try {
            int panel = decoded.getPixel(80, 250), backdrop = decoded.getPixel(10, 10);
            check(panel != backdrop && android.graphics.Color.red(panel) > 230 && android.graphics.Color.green(panel) > 230
                            && android.graphics.Color.blue(panel) > 230,
                    "range PNG preserves approved leaf backdrop and near-white panel", report);
        } finally { decoded.recycle(); }

        AtomicInteger calls = new AtomicInteger();
        File cancelled = new File(directory, "range-cancelled-must-not-exist.png");
        Files.deleteIfExists(cancelled.toPath());
        boolean stopped = false;
        try { RangeSummaryRenderer.render(context, sample, cancelled, () -> {
            if (calls.incrementAndGet() >= 5) throw new CancellationException("test cancellation");
        }); }
        catch (CancellationException expected) { stopped = true; }
        check(stopped && !cancelled.exists() && !new File(directory, cancelled.getName() + ".part").exists(),
                "range renderer cancellation does not leave an image or .part file", report);
    }
}
