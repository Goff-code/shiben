package cn.focusledger;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Pure, deterministic V2 report formatting; SQLite snapshots are the source of truth. */
public final class V2Reports {
    private V2Reports() {}

    public static final String LOCAL_DATA_WARNING =
            "记录和每日 Markdown 保存在本机，不会自动过期；卸载应用或清除数据会丢失。请定期导出，或选择外部备份文件夹。";

    /** Both ends are inclusive. all() selects recorded dates only, without fabricating empty days. */
    public static final class DateRange {
        public final LocalDate start, end;
        private DateRange(LocalDate start, LocalDate end) {
            if ((start == null) != (end == null)) throw new IllegalArgumentException("日期范围不完整");
            if (start != null) {
                requireDate(start); requireDate(end);
                if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于开始日期");
            }
            this.start = start; this.end = end;
        }
        public static DateRange day(LocalDate day) { requireDate(day); return new DateRange(day, day); }
        public static DateRange month(YearMonth month) {
            if (month == null) throw new IllegalArgumentException("月份不能为空");
            return new DateRange(month.atDay(1), month.atEndOfMonth());
        }
        public static DateRange month(int year, int month) { return month(YearMonth.of(year, month)); }
        public static DateRange year(int year) {
            return new DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        }
        public static DateRange custom(LocalDate start, LocalDate end) {
            if (start == null || end == null) throw new IllegalArgumentException("请选择开始和结束日期");
            return new DateRange(start, end);
        }
        public static DateRange all() { return new DateRange(null, null); }
        public boolean isAll() { return start == null; }
        public boolean contains(LocalDate day) {
            return day != null && (isAll() || (!day.isBefore(start) && !day.isAfter(end)));
        }
        /** -1 for the unbounded 'all recorded days' selection. */
        public long inclusiveDays() { return isAll() ? -1 : ChronoUnit.DAYS.between(start, end) + 1; }
        public String label() {
            return isAll() ? "全部记录" : start.equals(end) ? start.toString() : start + " 至 " + end;
        }
        public String fileStem() {
            return isAll() ? "时本_全部记录" : "时本_" + start + (start.equals(end) ? "" : "_" + end);
        }
    }

    private static void requireDate(LocalDate day) {
        if (day == null || day.getYear() < 1 || day.getYear() > 9999)
            throw new IllegalArgumentException("日期年份需在 1 至 9999 之间");
    }

    /** Chronological, unique recorded days inside an inclusive range. */
    public static List<LocalDate> selectDays(List<LocalDate> recordedDays, DateRange range) {
        if (recordedDays == null || range == null) throw new IllegalArgumentException("日期范围不能为空");
        TreeSet<LocalDate> selected = new TreeSet<>();
        for (LocalDate day : recordedDays) if (range.contains(day)) selected.add(day);
        return new ArrayList<>(selected);
    }

    public static String percent(double value) {
        if (!Double.isFinite(value)) return "未知";
        double bounded = Math.max(0, Math.min(100, value));
        return Math.abs(bounded - Math.rint(bounded)) < 0.000001
                ? String.format(Locale.ROOT, "%.0f%%", bounded)
                : String.format(Locale.ROOT, "%.1f%%", bounded);
    }

    public static String taskProgress(int value) { return value < 0 ? "未知" : percent(value); }

    /** Weight is a normalized share (0..1), not an invented legacy estimate. */
    public static String weight(double value) {
        if (!Double.isFinite(value)) return "未知";
        return value > 0 && value < .0001 ? "<0.01%" : String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    public static String duration(long millis) {
        if (millis < 0) throw new IllegalArgumentException("专注时长不能为负数");
        long seconds = millis / 1000, hours = seconds / 3600, minutes = seconds / 60 % 60;
        long remain = seconds % 60;
        if (hours > 0) return hours + "小时 " + minutes + "分" + (remain == 0 ? "" : " " + remain + "秒");
        if (minutes > 0) return minutes + "分" + (remain == 0 ? "" : " " + remain + "秒");
        return seconds + "秒";
    }

    public static String markdownText(String value) {
        if (value == null || value.isEmpty()) return "未命名";
        return value.replace("&", "&amp;").replace("\\", "\\\\")
                .replace("\r\n", "<br>").replace("\r", "<br>").replace("\n", "<br>")
                .replace("|", "\\|").replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
                .replace("#", "\\#").replace("<", "&lt;").replace(">", "&gt;")
                .replace("&lt;br&gt;", "<br>");
    }

    /** Active priorities first; archived rows retain their own historical rank and metadata. */
    public static List<LedgerStore.DayTask> orderedTasks(LedgerStore.DaySnapshot snapshot) {
        List<LedgerStore.DayTask> rows = new ArrayList<>(snapshot.items);
        rows.sort(Comparator.comparing((LedgerStore.DayTask row) -> row.archived)
                .thenComparingInt(row -> row.rank < 1 ? Integer.MAX_VALUE : row.rank));
        return rows;
    }

    public static String dailyMarkdown(LedgerStore.DaySnapshot snapshot) {
        if (snapshot == null || snapshot.day == null) throw new IllegalArgumentException("当天记录不能为空");
        StringBuilder out = new StringBuilder();
        out.append("# 时本 · ").append(snapshot.day).append("\n\n")
                .append("- 优先级加权总进度：**").append(percent(snapshot.totalProgress)).append("**\n")
                .append("- 总进度原值（百分数）：").append(Double.isFinite(snapshot.totalProgress) ? Double.toString(snapshot.totalProgress) : "未知").append("\n")
                .append("- 实际专注：**").append(duration(snapshot.totalActualMs)).append("**（")
                .append(snapshot.totalActualMs).append(" 毫秒）\n")
                .append("- 任务记录：").append(snapshot.items.size()).append(" 项（含保留的归档项）\n")
                .append("- 历史快照：").append(snapshot.completeSnapshot ? "完整" : "不完整；缺失的信息显示“未知”").append("\n\n");
        if (!snapshot.completeSnapshot) {
            out.append("> 旧版记录未保存完整的当日任务清单、优先级、权重或百分比进度。仅展示实际留存的信息，不用当前任务配置推算旧日期。\n\n");
        }
        out.append("## 当日任务（按当时优先级）\n\n");
        List<LedgerStore.DayTask> rows = orderedTasks(snapshot);
        boolean hasActive = false, hasArchived = false;
        for (LedgerStore.DayTask row : rows) {
            if (row.archived) hasArchived = true; else hasActive = true;
        }
        if (!hasActive) out.append("没有留存的未归档任务。\n\n");
        else appendTaskTable(out, rows, false);
        if (hasArchived) {
            out.append("## 已归档任务（保留记录）\n\n")
                    .append("归档项不参与该日当前总进度分母；下列顺序与权重为留存值，可能与未归档项重合。\n\n");
            appendTaskTable(out, rows, true);
        }
        out.append("## 口径与保存\n\n")
                .append("- 总进度由留存的任务优先级权重与手动填写的进度计算；专注计时结束不会自动增加任务进度。\n")
                .append("- 任务表包含全部留存任务，不因未完成、零进度或零专注时间省略。\n")
                .append("- 实际专注仅统计已保存记录，排除暂停；跨午夜片段按记录原时区分配到各日。当前计时尚未保存的部分不计入。\n")
                .append("- 原始毫秒数用于精确留存；可读时长向下取整到秒。\n")
                .append("- SQLite 账本为原始数据；此文件为可重新生成的可读报告，不是可直接导入的数据库备份。\n")
                .append("- ").append(LOCAL_DATA_WARNING).append("\n");
        return out.toString();
    }

    private static void appendTaskTable(StringBuilder out, List<LedgerStore.DayTask> rows, boolean archived) {
        out.append("| 优先级 | 任务 | 权重 | 进度 | 实际专注 | 实际毫秒 | 旧版完成勾选 | 权重原值（0—1） |\n")
                .append("| ---: | --- | ---: | ---: | ---: | ---: | --- | ---: |\n");
        for (LedgerStore.DayTask row : rows) {
            if (row.archived != archived) continue;
            out.append("| ").append(row.rank < 1 ? "未知" : row.rank).append(" | ")
                    .append(markdownText(row.title)).append(" | ").append(weight(row.weight)).append(" | ")
                    .append(taskProgress(row.progress)).append(" | ").append(duration(row.actualMs))
                    .append(" | ").append(row.actualMs).append(" | ")
                    .append(row.legacyDone == null ? "—" : row.legacyDone ? "已勾选" : "未勾选").append(" | ")
                    .append(Double.isFinite(row.weight) ? Double.toString(row.weight) : "未知").append(" |\n");
        }
        out.append('\n');
    }
}
