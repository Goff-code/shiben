package cn.focusledger;

import cn.focusledger.core.SettlementGrade;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

/** Immutable, Android-free calculations for one range summary (never a collection of daily pages). */
public final class RangeSummary {
    public final V2Reports.DateRange range;
    public final LocalDate firstRecordedDay, lastRecordedDay;
    public final int recordedDayCount, knownProgressDayCount, knownActualDayCount;
    /** -1 means that at least one retained duration was unknown. */
    public final long totalActualMs;
    /** NaN if any recorded day's progress is unknown; missing dates are not zero-filled. */
    public final double meanDailyProgress;
    public final boolean genuineCompletion, completeInventory;
    public final String settlementGrade;
    public final List<MergedTask> tasks;
    private final BigDecimal exactMean;

    public static final class MergedTask {
        public final String taskId, title;
        public final int occurrenceCount, knownProgressOccurrenceCount, knownActualOccurrenceCount;
        public final double meanProgress;
        /** Includes retained archived occurrences; -1 if any occurrence's duration is unknown. */
        public final long totalActualMs;
        private final BigDecimal exactMean;

        private MergedTask(TaskAccumulator task) {
            taskId = task.taskId; title = task.title;
            occurrenceCount = task.count; knownProgressOccurrenceCount = task.knownProgress;
            knownActualOccurrenceCount = task.knownActual;
            exactMean = task.knownProgress == task.count ? divide(task.progressSum, task.count) : null;
            meanProgress = rawDouble(exactMean);
            totalActualMs = task.knownActual == task.count ? task.actualSum : -1;
        }

        public String progressText() { return percent(exactMean, true); }
        public String actualText() { return duration(totalActualMs); }
    }

    private RangeSummary(V2Reports.DateRange range, TreeMap<LocalDate, LedgerStore.DaySnapshot> days,
                         List<MergedTask> tasks, BigDecimal progressSum, int knownProgress,
                         long actualSum, int knownActual, boolean allGenuine, boolean completeInventory) {
        this.range = range; firstRecordedDay = days.firstKey(); lastRecordedDay = days.lastKey();
        recordedDayCount = days.size(); knownProgressDayCount = knownProgress; knownActualDayCount = knownActual;
        totalActualMs = knownActual == recordedDayCount ? actualSum : -1;
        exactMean = knownProgress == recordedDayCount ? divide(progressSum, recordedDayCount) : null;
        meanDailyProgress = rawDouble(exactMean);
        genuineCompletion = exactMean != null && exactMean.compareTo(BigDecimal.valueOf(100)) == 0 && allGenuine;
        // Never use, average, or inherit any daily letter grade or manual S/SS/SSS/SSS+ override.
        settlementGrade = SettlementGrade.automatic(meanDailyProgress, genuineCompletion);
        this.completeInventory = completeInventory;
        this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
    }

    public static RangeSummary create(V2Reports.DateRange range, List<LedgerStore.DaySnapshot> snapshots) {
        return create(range, snapshots, null);
    }

    public static RangeSummary create(V2Reports.DateRange range, List<LedgerStore.DaySnapshot> snapshots,
                                      Runnable checkCancelled) {
        if (range == null || snapshots == null) throw new IllegalArgumentException("日期范围和留存记录不能为空");
        TreeMap<LocalDate, LedgerStore.DaySnapshot> days = new TreeMap<>();
        for (LedgerStore.DaySnapshot snapshot : snapshots) {
            check(checkCancelled);
            if (snapshot == null || snapshot.day == null) throw new IllegalArgumentException("留存记录缺少日期");
            if (!range.contains(snapshot.day)) continue;
            // A snapshot source must provide one authoritative record per date, not an arbitrary winner.
            if (days.put(snapshot.day, snapshot) != null) throw new IllegalArgumentException("同一日期存在重复留存记录：" + snapshot.day);
        }
        if (days.isEmpty()) throw new IllegalArgumentException("所选日期范围内没有留存记录");

        Map<String, TaskAccumulator> identified = new LinkedHashMap<>();
        List<TaskAccumulator> ordered = new ArrayList<>();
        BigDecimal progressSum = BigDecimal.ZERO;
        int knownProgress = 0, knownActual = 0;
        long actualSum = 0;
        boolean allGenuine = true, completeInventory = true;
        for (LedgerStore.DaySnapshot snapshot : days.values()) {
            check(checkCancelled);
            completeInventory &= snapshot.completeSnapshot;
            allGenuine &= snapshot.genuineCompletion;
            if (knownDailyProgress(snapshot)) {
                progressSum = progressSum.add(BigDecimal.valueOf(snapshot.totalProgress)); knownProgress++;
            }
            if (snapshot.totalActualMs >= 0) {
                actualSum = addDuration(actualSum, snapshot.totalActualMs); knownActual++;
            }
            Set<String> seenThisDay = new HashSet<>();
            for (LedgerStore.DayTask row : V2Reports.orderedTasks(snapshot)) {
                check(checkCancelled);
                if (row == null) throw new IllegalArgumentException("留存任务不能为空");
                String id = row.taskId;
                boolean hasId = id != null && !id.trim().isEmpty();
                if (hasId && !seenThisDay.add(id)) throw new IllegalArgumentException("同一日期存在重复任务标识：" + snapshot.day);
                TaskAccumulator task = hasId ? identified.get(id) : null;
                if (task == null) {
                    task = new TaskAccumulator(id); ordered.add(task);
                    if (hasId) identified.put(id, task);
                }
                // Missing identifiers never merge on title. The final chronological retained title wins.
                task.title = row.title; task.count++;
                if (row.progress >= 0 && row.progress <= 100) {
                    task.progressSum = task.progressSum.add(BigDecimal.valueOf(row.progress)); task.knownProgress++;
                }
                if (row.actualMs >= 0) {
                    task.actualSum = addDuration(task.actualSum, row.actualMs); task.knownActual++;
                }
            }
        }
        List<MergedTask> tasks = new ArrayList<>(ordered.size());
        for (TaskAccumulator task : ordered) { check(checkCancelled); tasks.add(new MergedTask(task)); }
        return new RangeSummary(range, days, tasks, progressSum, knownProgress, actualSum, knownActual,
                allGenuine, completeInventory);
    }

    public String progressText() { return percent(exactMean, genuineCompletion); }
    public String actualText() { return duration(totalActualMs); }
    public String recordedSpanText() { return firstRecordedDay + " 至 " + lastRecordedDay; }

    private static boolean knownDailyProgress(LedgerStore.DaySnapshot snapshot) {
        if (!snapshot.completeSnapshot || !Double.isFinite(snapshot.totalProgress)
                || snapshot.totalProgress < 0 || snapshot.totalProgress > 100) return false;
        for (LedgerStore.DayTask item : snapshot.items) {
            if (item == null) return false;
            if (!item.archived && (item.progress < 0 || item.progress > 100 || item.rank < 1
                    || !Double.isFinite(item.weight) || item.weight < 0 || item.weight > 1)) return false;
        }
        return true;
    }

    private static final class TaskAccumulator {
        final String taskId;
        String title;
        int count, knownProgress, knownActual;
        long actualSum;
        BigDecimal progressSum = BigDecimal.ZERO;
        TaskAccumulator(String taskId) { this.taskId = taskId; }
    }

    private static BigDecimal divide(BigDecimal sum, int count) {
        return sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128);
    }

    /** Prevent binary conversion alone from crossing a grade boundary (including 100%). */
    private static double rawDouble(BigDecimal mean) {
        if (mean == null) return Double.NaN;
        double value = mean.doubleValue();
        return BigDecimal.valueOf(value).compareTo(mean) > 0 ? Math.nextDown(value) : value;
    }

    private static String percent(BigDecimal mean, boolean trueCompletion) {
        if (mean == null) return "未知";
        if (mean.compareTo(BigDecimal.valueOf(100)) >= 0) return trueCompletion ? "100%" : "<100%";
        return mean.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + "%";
    }

    private static String duration(long value) { return value < 0 ? "未知" : V2Reports.duration(value); }

    private static long addDuration(long first, long second) {
        try { return Math.addExact(first, second); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("累计注意力时长超出可表示范围", overflow); }
    }

    static void check(Runnable checkCancelled) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("已取消导出");
        if (checkCancelled != null) checkCancelled.run();
    }
}
