package cn.focusledger;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

/** Reusable offline 3:4 poster. Background contains no text or metrics; every mark below is live. */
public final class GraffitiPoster {
    public static final int WIDTH = 1080, HEIGHT = 1440;
    public static final int INK = 0xff0b1215, WHITE = 0xfff7f8f4, CYAN = 0xff00ece2;
    public static final long MAX_BACKGROUND_PIXELS = 512L * 1024;
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    private static final Typeface BODY = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static Typeface brush;
    private GraffitiPoster() { }

    private static synchronized Typeface brush(Context context) {
        if (brush == null) brush = context.getResources().getFont(R.font.ma_shan_zheng);
        return brush;
    }

    public static Bitmap decodeBackground(Resources resources, Runnable cancel) throws IOException {
        RangeSummary.check(cancel);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true; options.inScaled = false;
        BitmapFactory.decodeResource(resources, R.drawable.export_graffiti_studio, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("无法读取导出背景");
        int sample = 1;
        while (((options.outWidth + (long) sample - 1) / sample)
                * ((options.outHeight + (long) sample - 1) / sample) > MAX_BACKGROUND_PIXELS) {
            RangeSummary.check(cancel); sample *= 2;
        }
        options.inJustDecodeBounds = false; options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeResource(resources, R.drawable.export_graffiti_studio, options);
        if (bitmap == null) throw new IOException("无法读取导出背景");
        if (bitmap.getAllocationByteCount() > MAX_BACKGROUND_PIXELS * 4L) {
            bitmap.recycle(); throw new IOException("导出背景超出安全内存范围");
        }
        return bitmap;
    }

    public static void drawDay(Context context, Canvas canvas, Bitmap background,
                               LedgerStore.DaySnapshot snapshot, Runnable cancel) {
        LocalDate day = snapshot.day;
        String date = day.format(DateTimeFormatter.ofPattern("uuuu.M.d", Locale.ROOT))
                + "  " + WEEKDAYS[day.getDayOfWeek().getValue() - 1];
        draw(context, canvas, background, "今日记录", date, snapshot.totalProgress,
                V2Reports.totalPercent(snapshot), "总完成度", V2Reports.settlementGrade(snapshot),
                "当日结算等级", "今日投资注意力时长总计",
                snapshot.totalActualMs < 0 ? "未知" : V2Reports.duration(snapshot.totalActualMs), cancel);
    }

    public static void drawRange(Context context, Canvas canvas, Bitmap background,
                                 RangeSummary summary, Runnable cancel) {
        String date = summary.range.isAll() ? "全部记录 · " + summary.recordedSpanText() : summary.range.label();
        draw(context, canvas, background, "区间记录", date, summary.meanDailyProgress,
                summary.progressText(), "日均总完成度", summary.settlementGrade,
                "区间结算等级", "累计投资注意力时长", summary.actualText(), cancel);
    }

    private static void draw(Context context, Canvas canvas, Bitmap background, String subtitle,
                             String date, double progress, String percent, String progressCaption,
                             String grade, String gradeCaption, String durationCaption, String duration,
                             Runnable cancel) {
        RangeSummary.check(cancel);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Typeface calligraphy = brush(context);
        canvas.save(); canvas.clipRect(0, 0, WIDTH, HEIGHT);
        canvas.drawColor(INK);
        canvas.drawBitmap(background, null, new RectF(0, 0, WIDTH, HEIGHT), p);
        // The photographic backdrop is never stretched with the task appendix.
        p.setShader(new LinearGradient(0, 0, 0, 1060,
                new int[]{0x08000000, 0x28000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, 1060, p); p.setShader(null);

        canvas.save(); canvas.rotate(-11, 85, 236);
        brushText(canvas, p, "时本", 65, 234, 200, 555, WHITE, calligraphy, 91);
        canvas.restore();
        canvas.save(); canvas.rotate(-10, 96, 355);
        brushText(canvas, p, subtitle, 88, 355, 120, 585, WHITE, calligraphy, 124);
        canvas.restore();
        String[] dateRows = dateLines(date);
        canvas.save(); canvas.rotate(-8, 109, 429);
        for (int i = 0; i < dateRows.length; i++)
            fitText(canvas, p, dateRows[i], 106, 430 + i * 40, 43, i == 0 ? 560 : 260, WHITE, calligraphy);
        p.setColor(CYAN); p.setStrokeWidth(4);
        canvas.drawLine(111, 444 + (dateRows.length - 1) * 40, dateRows.length == 1 ? 420 : 360,
                434 + (dateRows.length - 1) * 40, p);
        canvas.restore();

        text(canvas, p, "PLAN", 953, 52, 13, WHITE, BODY);
        text(canvas, p, "ACTION", 953, 73, 13, WHITE, BODY);
        text(canvas, p, "REFLECTION", 953, 94, 13, WHITE, BODY);
        text(canvas, p, "GROW", 953, 115, 13, WHITE, BODY);
        p.setStrokeWidth(2); canvas.drawLine(953, 133, 976, 133, p);
        canvas.save(); canvas.rotate(-13, 858, 223);
        text(canvas, p, "持续投入", 835, 218, 34, CYAN, calligraphy);
        text(canvas, p, "终会看到复利。", 808, 262, 33, CYAN, calligraphy);
        p.setColor(CYAN); canvas.drawLine(884, 285, 1010, 278, p); canvas.restore();

        if (dateRows.length == 1) {
            canvas.save(); canvas.rotate(-9, 70, 510);
            text(canvas, p, "记录当下，", 65, 507, 31, WHITE, calligraphy);
            text(canvas, p, "成为更好的自己。", 74, 546, 30, WHITE, calligraphy);
            canvas.restore();
        }
        drawRing(canvas, p, progress, percent, progressCaption, calligraphy);
        RangeSummary.check(cancel);

        canvas.save(); canvas.rotate(-16, 410, 468);
        fitText(canvas, p, gradeCaption, 407, 465, 49, 355, WHITE, calligraphy);
        p.setColor(WHITE); p.setStrokeWidth(4); canvas.drawLine(448, 485, 691, 480, p);
        canvas.drawLine(570, 496, 615, 529, p); canvas.drawLine(615, 529, 596, 491, p);
        canvas.restore();
        drawCrown(canvas, p);
        drawGrade(canvas, p, grade, calligraphy);
        canvas.save(); canvas.rotate(-14, 852, 904);
        text(canvas, p, "KEEP GOING!", 818, 914, 29, CYAN, calligraphy); canvas.restore();

        drawDuration(canvas, p, durationCaption, duration, calligraphy);
        canvas.save(); canvas.rotate(-8, 750, 1215);
        text(canvas, p, "专注当下", 733, 1198, 31, 0xccffffff, calligraphy);
        text(canvas, p, "时间会给你答案", 701, 1239, 30, 0xccffffff, calligraphy);
        canvas.restore();
        text(canvas, p, "A BRIGHTER YOU", 887, 1380, 13, 0xccffffff, BODY);
        p.setColor(WHITE); p.setStrokeWidth(2); canvas.drawLine(1015, 1397, 1038, 1397, p);
        canvas.restore();
        RangeSummary.check(cancel);
    }

    /** Ranges use two readable lines and cannot drift into the crown/grade zone. */
    static String[] dateLines(String date) {
        int split = date.indexOf(" 至 ");
        return split < 0 ? new String[]{date} : new String[]{date.substring(0, split), "至 " + date.substring(split + 3)};
    }

    private static void drawRing(Canvas c, Paint p, double progress, String value, String caption, Typeface face) {
        float cx = 238, cy = 715, radius = 153;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(24); p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0x606e7c7c); c.drawCircle(cx, cy, radius, p);
        if (Double.isFinite(progress) && progress > 0) {
            p.setColor(0xffffffff); // A shader still multiplies Paint alpha; do not inherit the dim track.
            p.setShader(new LinearGradient(75, 570, 405, 855, CYAN, 0xff00bbae, Shader.TileMode.CLAMP));
            c.drawArc(new RectF(cx - radius, cy - radius, cx + radius, cy + radius), -90,
                    (float) (Math.max(0, Math.min(100, progress)) * 3.6), false, p);
            p.setShader(null);
        }
        p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER);
        fitText(c, p, value, cx, cy + 14, 82, 256, WHITE, face);
        fitText(c, p, caption, cx, cy + 64, 31, 242, WHITE, BODY);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static void drawDuration(Canvas c, Paint p, String caption, String value, Typeface face) {
        // This field remains legible for Long.MAX_VALUE milliseconds rather than eliding its tail.
        p.setTypeface(face); p.setTextSize(67);
        boolean wide = p.measureText(value) > 540;
        float width = wide ? 880 : 592, left = 48, top = 917;
        c.save(); c.rotate(wide ? 3 : 8, left, top);
        Path panel = polygon(left, top, left + width, top + 5, left + width - 10, top + 194, left - 12, top + 187);
        p.setColor(0xbb151d20); p.setStyle(Paint.Style.FILL); c.drawPath(panel, p);
        p.setColor(0x99ffffff); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f); c.drawPath(panel, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0x7796a4a6);
        c.drawPath(polygon(left - 16, top + 9, left + 47, top - 12, left + 62, top + 20, left - 7, top + 39), p);
        c.drawPath(polygon(left + width - 57, top + 164, left + width + 13, top + 173,
                left + width + 2, top + 204, left + width - 69, top + 191), p);
        p.setStyle(Paint.Style.STROKE); p.setColor(WHITE); p.setStrokeWidth(3.5f);
        c.drawCircle(left + 51, top + 46, 17, p); c.drawLine(left + 51, top + 46, left + 51, top + 34, p);
        c.drawLine(left + 51, top + 46, left + 60, top + 50, p);
        p.setStyle(Paint.Style.FILL);
        fitText(c, p, caption, left + 82, top + 56, 28, width - 107, WHITE, BODY);
        fitText(c, p, value, left + 31, top + 139, 67, width - 63, WHITE, face);
        p.setColor(CYAN); p.setStrokeWidth(3); c.drawLine(left + 134, top + 166, left + width - 37, top + 157, p);
        c.restore();
    }

    private static void drawCrown(Canvas c, Paint p) {
        c.save(); c.rotate(10, 756, 372);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(WHITE);
        Path crown = new Path(); crown.moveTo(706, 399); crown.lineTo(699, 342); crown.lineTo(734, 369);
        crown.lineTo(760, 316); crown.lineTo(786, 365); crown.lineTo(821, 335); crown.lineTo(803, 397);
        crown.quadTo(759, 380, 706, 399); c.drawPath(crown, p);
        c.drawLine(715, 407, 795, 405, p);
        for (float[] pt : new float[][]{{699,342},{760,316},{821,335}}) c.drawCircle(pt[0], pt[1], 4, p);
        c.restore(); p.setStyle(Paint.Style.FILL);
    }

    /** Hand-drawn vector alphabet, deliberately independent of installed fonts. */
    static Path gradePath(String grade) {
        Path result = new Path(); float x = 0;
        for (int i = 0; i < grade.length(); i++) {
            char ch = grade.charAt(i); Path letter = glyph(ch);
            if (letter == null) return null;
            letter.offset(x, 0); result.addPath(letter); x += ch == '+' || ch == '-' ? 63 : 105;
        }
        result.setFillType(Path.FillType.EVEN_ODD); return result;
    }

    static RectF gradeBounds(String grade) {
        Path path = mappedGrade(grade); if (path == null) return new RectF(490, 560, 965, 760);
        RectF bounds = new RectF(); path.computeBounds(bounds, true); return bounds;
    }

    private static Path mappedGrade(String grade) {
        Path path = gradePath(grade); if (path == null) return null;
        RectF box = new RectF(); path.computeBounds(box, true);
        Matrix matrix = new Matrix();
        // Shared four-corner envelope guarantees SSS+ fits while retaining forceful perspective.
        float[] src = {box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom};
        float[] dst = {566, 498, 1001, 437, 968, 841, 420, 933};
        if (grade.length() >= 3) dst = new float[]{464, 536, 1008, 452, 982, 823, 411, 906};
        matrix.setPolyToPoly(src, 0, dst, 0, 4); path.transform(matrix); return path;
    }

    private static void drawGrade(Canvas c, Paint p, String grade, Typeface face) {
        Path path = mappedGrade(grade);
        if (path == null) {
            c.save(); c.rotate(-12, 725, 698);
            fitText(c, p, grade, 471, 741, 183, 485, CYAN, face); c.restore(); return;
        }
        p.setStrokeJoin(Paint.Join.MITER); p.setStrokeCap(Paint.Cap.BUTT);
        c.save(); c.translate(-8, 9); p.setColor(INK); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(17); c.drawPath(path, p);
        p.setColor(WHITE); p.setStrokeWidth(8); c.drawPath(path, p); c.restore();
        p.setColor(CYAN); p.setStyle(Paint.Style.FILL); c.drawPath(path, p);
        // Fixed-seed dry-brush cuts are clipped to the live path, never to a baked grade image.
        c.save(); c.clipPath(path); Random random = new Random(87023);
        for (int i = 0; i < 195; i++) {
            float x = 414 + random.nextFloat() * 608, y = 423 + random.nextFloat() * 520;
            p.setColor(i % 5 == 0 ? 0x99ffffff : 0xd00b1718); p.setStrokeWidth(i % 4 == 0 ? 2.3f : 1.1f);
            c.drawLine(x, y, x + 5 + random.nextFloat() * 48, y - 3 - random.nextFloat() * 18, p);
        }
        c.restore(); p.setStyle(Paint.Style.FILL);
    }

    private static Path glyph(char c) {
        Path p;
        switch (c) {
            case 'S': return polygon(96,3, 23,0, 0,31, 4,80, 62,105, 64,143, 3,146, 0,180,
                    72,178, 98,149, 96,96, 36,67, 35,38, 92,35);
            case 'A':
                p = polygon(30,3, 71,0, 101,180, 68,179, 59,139, 29,141, 19,180, -11,182);
                p.addPath(polygon(37,109, 52,108, 45,58)); p.setFillType(Path.FillType.EVEN_ODD); return p;
            case 'B':
                p = polygon(2,0, 67,2, 93,27, 91,73, 74,87, 99,105, 97,152, 69,179, 0,181);
                p.addPath(polygon(31,34, 59,34, 62,65, 30,68));
                p.addPath(polygon(30,108, 64,106, 65,144, 29,146)); p.setFillType(Path.FillType.EVEN_ODD); return p;
            case 'C': return polygon(96,0, 25,3, 1,32, -2,148, 24,179, 97,180, 98,144,
                    37,145, 32,135, 34,44, 41,34, 96,35);
            case 'D':
                p = polygon(2,2, 60,0, 95,31, 97,147, 64,179, 0,181);
                p.addPath(polygon(32,36, 54,35, 62,45, 63,132, 53,145, 31,146)); p.setFillType(Path.FillType.EVEN_ODD); return p;
            case 'E': return polygon(2,3, 97,0, 91,35, 33,37, 31,71, 82,67, 78,105,
                    31,108, 29,145, 96,140, 92,178, -1,181);
            case '+': return polygon(21,58, 43,54, 43,88, 69,85, 65,109, 43,112, 41,145,
                    17,149, 20,116, -4,119, -1,93, 20,90);
            case '-': return polygon(-1,90, 62,80, 58,110, -5,124);
            default: return null;
        }
    }

    private static Path polygon(float... points) {
        Path p = new Path(); p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
        p.close(); return p;
    }

    private static void brushText(Canvas c, Paint p, String value, float x, float y, float size,
                                  float width, int color, Typeface face, long seed) {
        p.setTypeface(face); p.setTextSize(size); p.setTextScaleX(1f);
        float measured = p.measureText(value); if (measured > width) p.setTextSize(size * width / measured);
        Path path = new Path(); p.getTextPath(value, 0, value.length(), x, y, path);
        p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawPath(path, p);
        RectF box = new RectF(); path.computeBounds(box, true);
        c.save(); c.clipPath(path); Random r = new Random(seed);
        p.setColor(0xc00b1215); p.setStrokeWidth(1.2f);
        for (int i = 0; i < 85; i++) {
            float px = box.left + r.nextFloat() * box.width(), py = box.top + r.nextFloat() * box.height();
            c.drawLine(px, py, px + 3 + r.nextFloat() * 20, py - r.nextFloat() * 7, p);
        }
        c.restore();
    }

    static float fittedSize(Paint p, String value, float size, float maxWidth, Typeface face) {
        p.setTypeface(face); p.setTextSize(size); p.setTextScaleX(1f);
        float width = p.measureText(value); return width > maxWidth ? size * maxWidth / width : size;
    }

    private static void fitText(Canvas c, Paint p, String value, float x, float y, float size,
                                float maxWidth, int color, Typeface face) {
        text(c, p, value, x, y, fittedSize(p, value, size, maxWidth, face), color, face);
    }

    private static void text(Canvas c, Paint p, String value, float x, float y, float size, int color, Typeface face) {
        p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTypeface(face); p.setTextSize(size); p.setTextScaleX(1f);
        c.drawText(value, x, y, p);
    }
}
