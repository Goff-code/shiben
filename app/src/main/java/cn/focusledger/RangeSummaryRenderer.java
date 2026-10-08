package cn.focusledger;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** One legible PNG for one requested range. Oversized content fails before allocating an output bitmap. */
public final class RangeSummaryRenderer {
    public static final int WIDTH = 1080, MAX_HEIGHT = 8000, HIDDEN_TASK_HEIGHT = GraffitiPoster.HEIGHT;
    public static final long MAX_OUTPUT_PIXELS = (long) WIDTH * MAX_HEIGHT;
    public static final long MAX_BACKGROUND_PIXELS = 512L * 1024;
    /** ARGB output + background + one sequential title/grade sprite + encoder/preview reserve. */
    public static final long BITMAP_BUDGET_BYTES = 48L * 1024 * 1024;
    public static final long MAX_LETTERING_BYTES = GradeArtwork.MAX_SPRITE_PIXELS * 4L;
    public static final long AUXILIARY_RESERVE_BYTES = 8L * 1024 * 1024;
    public static final String TOO_LARGE = "任务内容过长，无法在安全内存范围内生成单张清晰完整的图片；请缩小日期范围或改用 Markdown 导出。";

    private static final int BG = 0xff0b1215, INK = 0xfff3f6f5, TEAL = 0xff00ded2,
            MUTED = 0xffb3c4c5, LINE = 0xff334447;
    private static final int LEFT = 106, RIGHT = 974, TITLE_X = 198, TITLE_WIDTH = RIGHT - TITLE_X;
    private static final int TITLE_SIZE = 38, TITLE_LINE = 49, NOTE_SIZE = 29, NOTE_LINE = 39;
    private static final int META_SIZE = 27, META_LINE = 37, ROW_EXTRA = 65, BOTTOM = 82;
    static final String PROGRESS_CAPTION = "日均总完成度";
    static final int CAPTION_SIZE = 31, CAPTION_BASELINE_OFFSET = 64, PROGRESS_RADIUS = 153, RING_STROKE_WIDTH = 24;

    private RangeSummaryRenderer() { }

    /** Pure, testable allocation preflight; uses long arithmetic and accounts for all bitmap allocations. */
    public static long estimatedBitmapBytes(int width, long height, long backgroundPixels) throws IOException {
        if (width <= 0 || height <= 0 || backgroundPixels < 0) throw new IOException(TOO_LARGE);
        try {
            long output = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
            return Math.addExact(Math.addExact(output, Math.multiplyExact(backgroundPixels, 4L)), Math.addExact(AUXILIARY_RESERVE_BYTES, MAX_LETTERING_BYTES));
        } catch (ArithmeticException overflow) { throw new IOException(TOO_LARGE, overflow); }
    }

    public static void validateBitmapBudget(int width, long height, long backgroundPixels) throws IOException {
        long bytes = estimatedBitmapBytes(width, height, backgroundPixels);
        if (width != WIDTH || height > MAX_HEIGHT || backgroundPixels > MAX_BACKGROUND_PIXELS
                || (long) width * height > MAX_OUTPUT_PIXELS || bytes > BITMAP_BUDGET_BYTES) throw new IOException(TOO_LARGE);
    }

    /** No truncation, splitting, or shrinking of task text is allowed to make an oversized plan fit. */
    public static int checkedHeight(long proposedHeight) throws IOException {
        return checkedHeight(proposedHeight, false);
    }

    public static int checkedHeight(long proposedHeight, boolean hideTaskDetails) throws IOException {
        long height = Math.max(hideTaskDetails ? HIDDEN_TASK_HEIGHT : 1730, proposedHeight);
        validateBitmapBudget(WIDTH, height, MAX_BACKGROUND_PIXELS);
        return (int) height;
    }

    /** Writes a real PNG atomically to target; owns/recycles every decoded and allocated bitmap. */
    public static File render(Context context, RangeSummary summary, File target, Runnable checkCancelled) throws IOException {
        return render(context, summary, target, checkCancelled, false);
    }

    /** Privacy mode does not lay out or render task names, per-task progress, durations, or annotations. */
    public static File render(Context context, RangeSummary summary, File target, Runnable checkCancelled,
                              boolean hideTaskDetails) throws IOException {
        if (context == null || summary == null || target == null) throw new IllegalArgumentException("图片导出参数不能为空");
        RangeSummary.check(checkCancelled);
        Plan plan = plan(summary, checkCancelled, hideTaskDetails); // Full layout must pass before any image allocation.
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        Bitmap background = null, output = null;
        boolean moved = false, success = false;
        try {
            background = GraffitiPoster.decodeBackground(context.getResources(), checkCancelled);
            validateBitmapBudget(WIDTH, plan.height, (long) background.getWidth() * background.getHeight());
            RangeSummary.check(checkCancelled);
            output = Bitmap.createBitmap(WIDTH, plan.height, Bitmap.Config.ARGB_8888);
            draw(context, new Canvas(output), background, summary, plan, checkCancelled);
            RangeSummary.check(checkCancelled);
            try (FileOutputStream file = new FileOutputStream(temporary);
                 OutputStream out = new CancellationOutputStream(file, checkCancelled)) {
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("无法生成 PNG");
                RangeSummary.check(checkCancelled); out.flush(); file.getFD().sync();
            }
            RangeSummary.check(checkCancelled);
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            RangeSummary.check(checkCancelled);
            success = true;
            return target;
        } finally {
            if (output != null) output.recycle();
            if (background != null) background.recycle();
            if (!success) {
                Files.deleteIfExists(temporary.toPath());
                if (moved) Files.deleteIfExists(target.toPath());
            }
        }
    }

    private static final class Row {
        RangeSummary.MergedTask task;
        List<String> titles, details;
        int height() { return titles.size() * TITLE_LINE + details.size() * META_LINE + ROW_EXTRA; }
    }

    private static final class Plan {
        final List<Row> rows = new ArrayList<>();
        List<String> notes, taskNotes;
        int height, taskHeadingY, taskNotesY, taskTop;
        boolean hideTaskDetails;
    }

    private static Plan plan(RangeSummary summary, Runnable cancel, boolean hideTaskDetails) throws IOException {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Plan plan = new Plan();
        plan.hideTaskDetails = hideTaskDetails;
        if (hideTaskDetails) {
            // Crucially, no iteration over task names or task rows, even for oversized inventories.
            plan.height = checkedHeight(HIDDEN_TASK_HEIGHT, true);
            return plan;
        }
        setFont(paint, NOTE_SIZE, false);
        String notes = "每日总完成度平均＝各留存日总完成度之和 ÷ " + summary.recordedDayCount + " 天。\n"
                + "无记录日期不计入分母；区间等级按未取整均值自动计算。\n"
                + "区间等级不继承每日手动等级。";
        if (summary.knownProgressDayCount < summary.recordedDayCount) notes += "\n"
                + summary.recordedDayCount + " 天中 " + summary.knownProgressDayCount + " 天进度已知；未知日不会当作 0% 或跳过，均值与等级显示未知。";
        if (summary.knownActualDayCount < summary.recordedDayCount) notes += "\n部分留存日注意力时长未知，累计值显示未知。";
        plan.notes = wrap(notes, paint, RIGHT - LEFT, MAX_HEIGHT / NOTE_LINE, cancel);
        plan.taskHeadingY = 1504 + plan.notes.size() * NOTE_LINE + 61;
        plan.taskNotesY = plan.taskHeadingY + 49;
        setFont(paint, META_SIZE, false);
        String taskNotes = "按任务标识合并，名称取最后一次留存；同名不同任务分别保留。\n"
                + "任务均值仅计该任务的留存次数，含已归档记录；未出现的日期不补零。";
        if (!summary.completeInventory) taskNotes += "\n部分日期任务清单未知，以下仅列已留存任务。";
        plan.taskNotes = wrap(taskNotes, paint, RIGHT - LEFT, MAX_HEIGHT / META_LINE, cancel);
        plan.taskTop = plan.taskNotesY + plan.taskNotes.size() * META_LINE + 11;
        long contentBottom = plan.taskTop;
        checkedHeight(contentBottom + BOTTOM);
        for (RangeSummary.MergedTask task : summary.tasks) {
            RangeSummary.check(cancel);
            Row row = new Row(); row.task = task;
            setFont(paint, TITLE_SIZE, false);
            int availableTitleLines = (int) Math.max(0, (MAX_HEIGHT - BOTTOM - contentBottom - ROW_EXTRA) / TITLE_LINE);
            row.titles = wrap(task.title == null || task.title.isEmpty() ? "未命名" : task.title,
                    paint, TITLE_WIDTH, availableTitleLines, cancel);
            setFont(paint, META_SIZE, false);
            String details = "平均完成度 " + task.progressText() + " · 记录 " + task.occurrenceCount + " 天";
            if (task.knownProgressOccurrenceCount < task.occurrenceCount)
                details += "（" + (task.occurrenceCount - task.knownProgressOccurrenceCount) + " 天进度未知）";
            details += "\n累计注意力 " + task.actualText();
            if (task.knownActualOccurrenceCount < task.occurrenceCount)
                details += "（" + (task.occurrenceCount - task.knownActualOccurrenceCount) + " 天时长未知）";
            if (task.taskId == null || task.taskId.trim().isEmpty()) details += "\n任务标识未知，保留为独立记录";
            row.details = wrap(details, paint, TITLE_WIDTH, MAX_HEIGHT / META_LINE, cancel);
            contentBottom += row.height();
            checkedHeight(contentBottom + BOTTOM);
            plan.rows.add(row);
        }
        if (plan.rows.isEmpty()) contentBottom += 84;
        plan.height = checkedHeight(contentBottom + BOTTOM);
        return plan;
    }

    /** Bounded wrapping preserves every Unicode code point and explicit newline; never ellipsizes. */
    static List<String> wrap(String value, Paint paint, float width, int maxLines, Runnable cancel) throws IOException {
        List<String> lines = new ArrayList<>();
        int offset = 0;
        while (offset <= value.length()) {
            RangeSummary.check(cancel);
            int end = offset;
            while (end < value.length() && value.charAt(end) != '\n' && value.charAt(end) != '\r') {
                if ((end & 1023) == 0) RangeSummary.check(cancel);
                end++;
            }
            if (offset == end) addLine(lines, "", maxLines);
            while (offset < end) {
                RangeSummary.check(cancel);
                int count = paint.breakText(value, offset, end, true, width, null);
                if (count <= 0) count = Character.charCount(value.codePointAt(offset));
                int next = Math.min(end, offset + count);
                if (next < end && Character.isHighSurrogate(value.charAt(next - 1)) && Character.isLowSurrogate(value.charAt(next))) next--;
                if (next == offset) next += Character.charCount(value.codePointAt(offset));
                addLine(lines, value.substring(offset, next), maxLines); offset = next;
            }
            if (end == value.length()) break;
            offset = end + 1;
            if (value.charAt(end) == '\r' && offset < value.length() && value.charAt(offset) == '\n') offset++;
        }
        return lines;
    }

    private static void addLine(List<String> lines, String line, int maxLines) throws IOException {
        if (lines.size() >= maxLines) throw new IOException(TOO_LARGE);
        lines.add(line);
    }

    private static void draw(Context context, Canvas canvas, Bitmap background, RangeSummary summary, Plan plan, Runnable cancel) throws IOException {
        RangeSummary.check(cancel);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawColor(BG);
        GraffitiPoster.drawRange(context, canvas, background, summary, cancel);
        if (plan.hideTaskDetails) return;
        for (int i = 0; i < plan.notes.size(); i++) {
            RangeSummary.check(cancel);
            text(canvas, paint, plan.notes.get(i), LEFT, 1504 + i * NOTE_LINE, NOTE_SIZE, MUTED, false);
        }
        divider(canvas, paint, plan.taskHeadingY - 49);
        text(canvas, paint, "任务汇总与平均完成度", LEFT, plan.taskHeadingY, 40, INK, true);
        for (int i = 0; i < plan.taskNotes.size(); i++) {
            RangeSummary.check(cancel);
            text(canvas, paint, plan.taskNotes.get(i), LEFT, plan.taskNotesY + i * META_LINE, META_SIZE, MUTED, false);
        }
        int y = plan.taskTop;
        if (plan.rows.isEmpty()) text(canvas, paint, summary.completeInventory ? "无" : "未知", LEFT, y + 50, 36, MUTED, false);
        for (Row row : plan.rows) {
            RangeSummary.check(cancel);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xffd4f1ee); canvas.drawCircle(143, y + 31, 28, paint);
            paint.setColor(0xff148d96); paint.setStrokeWidth(3);
            canvas.drawLine(132, y + 25, 154, y + 25, paint); canvas.drawLine(132, y + 37, 148, y + 37, paint);
            int baseline = y + 44;
            for (String line : row.titles) {
                RangeSummary.check(cancel); text(canvas, paint, line, TITLE_X, baseline, TITLE_SIZE, INK, false); baseline += TITLE_LINE;
            }
            baseline += 2;
            for (String line : row.details) {
                RangeSummary.check(cancel); text(canvas, paint, line, TITLE_X, baseline, META_SIZE, MUTED, false); baseline += META_LINE;
            }
            int barY = baseline - 12;
            paint.setColor(LINE); canvas.drawRoundRect(new RectF(TITLE_X, barY, RIGHT, barY + 13), 6.5f, 6.5f, paint);
            if (Double.isFinite(row.task.meanProgress) && row.task.meanProgress > 0) {
                paint.setColor(TEAL);
                canvas.drawRoundRect(new RectF(TITLE_X, barY,
                        TITLE_X + TITLE_WIDTH * (float) row.task.meanProgress / 100f, barY + 13), 6.5f, 6.5f, paint);
            }
            y += row.height();
        }
    }

    private static void divider(Canvas canvas, Paint paint, int y) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(LINE); canvas.drawRect(LEFT - 4, y, RIGHT + 4, y + 2, paint);
    }

    private static void setFont(Paint paint, int size, boolean bold) {
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); paint.setTextSize(size);
    }

    private static void text(Canvas canvas, Paint paint, String value, float x, float y, int size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); setFont(paint, size, bold); canvas.drawText(value, x, y, paint);
    }

    private static final class CancellationOutputStream extends FilterOutputStream {
        private final Runnable cancel;
        CancellationOutputStream(OutputStream out, Runnable cancel) { super(out); this.cancel = cancel; }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            RangeSummary.check(cancel); out.write(data, offset, length); RangeSummary.check(cancel);
        }
        @Override public void write(int value) throws IOException { RangeSummary.check(cancel); out.write(value); }
    }
}
