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

/** Design-stage native preview only. Fixed title/grade sprites are offline; report values stay live. */
public final class LetteringStudyPoster {
    public static final int WIDTH = 1080, HEIGHT = 1440;
    public static final int INK = 0xff0b1215, WHITE = 0xfff7f8f4, CYAN = 0xff00ece2;
    public static final long MAX_BACKGROUND_PIXELS = 512L * 1024;
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    private static final Typeface BODY = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static Typeface brush;
    private LetteringStudyPoster() { }

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

    public static void drawDay(Context context, android.content.res.AssetManager assets, Canvas canvas, Bitmap background,
                               LedgerStore.DaySnapshot snapshot, String gradeAsset, Runnable cancel) {
        LocalDate day = snapshot.day;
        String date = day.format(DateTimeFormatter.ofPattern("uuuu.M.d", Locale.ROOT))
                + "  " + WEEKDAYS[day.getDayOfWeek().getValue() - 1];
        draw(context, assets, canvas, background, "今日记录", date, snapshot.totalProgress,
                V2Reports.totalPercent(snapshot), "总完成度", V2Reports.settlementGrade(snapshot),
                "当日结算等级", "今日投资注意力时长总计",
                snapshot.totalActualMs < 0 ? "未知" : V2Reports.duration(snapshot.totalActualMs), gradeAsset, cancel);
    }

    private static void draw(Context context, android.content.res.AssetManager assets, Canvas canvas, Bitmap background, String subtitle,
                             String date, double progress, String percent, String progressCaption,
                             String grade, String gradeCaption, String durationCaption, String duration,
                             String gradeAsset, Runnable cancel) {
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

        drawSprite(assets, "lettering/title_today.png", canvas, p, new RectF(36, 20, 616, 386));
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
        if (!"A-".equals(grade)) throw new IllegalArgumentException("This design study includes only A-; other grades are not yet drawn");
        drawSprite(assets, gradeAsset, canvas, p, new RectF(390, 377, 1060, 993));
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

    private static void drawSprite(android.content.res.AssetManager assets, String name,
                                   Canvas canvas, Paint paint, RectF slot) {
        Bitmap sprite = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = assets.open(name)) { BitmapFactory.decodeStream(in, null, bounds); }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = (long) bounds.outWidth * bounds.outHeight > 2L * 1024 * 1024 ? 2 : 1;
            try (java.io.InputStream in = assets.open(name)) { sprite = BitmapFactory.decodeStream(in, null, options); }
            if (sprite == null || !sprite.hasAlpha()) throw new IllegalStateException("Missing transparent sprite: " + name);
            float scale = Math.min(slot.width() / sprite.getWidth(), slot.height() / sprite.getHeight());
            float w = sprite.getWidth() * scale, h = sprite.getHeight() * scale;
            RectF fit = new RectF(slot.centerX() - w / 2, slot.centerY() - h / 2,
                    slot.centerX() + w / 2, slot.centerY() + h / 2);
            paint.setShader(null); paint.setColor(0xffffffff); paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(sprite, null, name.contains("title_") ? fit : slot, paint);
        } catch (IOException error) { throw new IllegalStateException("Cannot load study artwork", error); }
        finally { if (sprite != null) sprite.recycle(); }
    }

    private static Path polygon(float... points) {
        Path p = new Path(); p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
        p.close(); return p;
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
