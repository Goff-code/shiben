package cn.focusledger;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class RangeSummaryTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private static LedgerStore.DayTask task(String id, String title, int progress, long actual) {
        return new LedgerStore.DayTask(id, title, 1, progress, 1, actual, false, null);
    }

    private static LedgerStore.DaySnapshot day(int offset, double total, long actual, LedgerStore.DayTask... tasks) {
        return new LedgerStore.DaySnapshot(DAY.plusDays(offset), Arrays.asList(tasks), total, true, actual);
    }

    private static RangeSummary all(LedgerStore.DaySnapshot... days) {
        return RangeSummary.create(V2Reports.DateRange.all(), Arrays.asList(days));
    }

    @Test public void missingDatesAreExcludedAndMeanUsesDailyTotalsRatherThanDurations() {
        RangeSummary value = RangeSummary.create(V2Reports.DateRange.month(2026, 10), Arrays.asList(
                day(0, 60, 1000, task("a", "阅读", 60, 1000)),
                day(9, 100, 9000, task("a", "阅读", 100, 9000))));
        assertEquals(2, value.recordedDayCount); assertEquals(2, value.knownProgressDayCount);
        assertEquals(80, value.meanDailyProgress, 0); assertEquals("80%", value.progressText());
        assertEquals("B-", value.settlementGrade); assertEquals(10000, value.totalActualMs);
        assertEquals(DAY, value.firstRecordedDay); assertEquals(DAY.plusDays(9), value.lastRecordedDay);
        assertEquals(V2Reports.DateRange.month(2026, 10).label(), value.range.label());
    }

    @Test public void sameIdentifierMergesAcrossRenamesUsingLatestChronologicalTitle() {
        RangeSummary value = all(day(5, 80, 2000, task("a", "最后名称🧠", 80, 2000)),
                day(0, 20, 1000, task("a", "原名称", 20, 1000)));
        assertEquals(1, value.tasks.size());
        RangeSummary.MergedTask task = value.tasks.get(0);
        assertEquals("a", task.taskId); assertEquals("最后名称🧠", task.title);
        assertEquals(50, task.meanProgress, 0); assertEquals(2, task.occurrenceCount);
        assertEquals(3000, task.totalActualMs);
    }

    @Test public void identicalNamesWithDifferentIdentifiersStaySeparate() {
        RangeSummary value = all(day(0, 40, 0, task("a", "同名", 20, 0), task("b", "同名", 60, 0)));
        assertEquals(2, value.tasks.size());
        assertEquals(20, value.tasks.get(0).meanProgress, 0); assertEquals(60, value.tasks.get(1).meanProgress, 0);
    }

    @Test public void taskMeanDenominatorOnlyContainsItsRecordedOccurrences() {
        RangeSummary value = all(day(0, 25, 0, task("a", "A", 50, 100)),
                day(1, 75, 0, task("b", "B", 90, 200)), day(2, 50, 0, task("a", "A", 100, 300)));
        assertEquals(50, value.meanDailyProgress, 0);
        assertEquals(75, value.tasks.get(0).meanProgress, 0); assertEquals(2, value.tasks.get(0).occurrenceCount);
        assertEquals(90, value.tasks.get(1).meanProgress, 0); assertEquals(1, value.tasks.get(1).occurrenceCount);
        assertEquals(400, value.tasks.get(0).totalActualMs);
    }

    @Test public void archivedTaskOccurrencesAreRetainedWithoutChangingDailyTotals() {
        LedgerStore.DayTask archived = new LedgerStore.DayTask("a", "归档任务", 2, 80, .5, 700, true, null);
        RangeSummary value = all(day(0, 30, 1000, task("b", "活动任务", 30, 300), archived),
                day(1, 20, 400, task("a", "新名称", 20, 400)));
        assertEquals(25, value.meanDailyProgress, 0); assertEquals(1400, value.totalActualMs);
        assertEquals("b", value.tasks.get(0).taskId);
        RangeSummary.MergedTask merged = value.tasks.get(1);
        assertEquals("新名称", merged.title); assertEquals(50, merged.meanProgress, 0); assertEquals(1100, merged.totalActualMs);
    }

    @Test public void dailyUnknownMakesWholeAverageAndGradeUnknownWithoutShrinkingDenominator() {
        LedgerStore.DaySnapshot legacy = new LedgerStore.DaySnapshot(DAY, Collections.singletonList(
                new LedgerStore.DayTask("a", "旧任务", -1, -1, Double.NaN, 1500, false, true)), Double.NaN, false, 1500);
        RangeSummary value = all(legacy, day(1, 100, 2500, task("a", "阅读", 100, 2500)));
        assertEquals(2, value.recordedDayCount); assertEquals(1, value.knownProgressDayCount);
        assertTrue(Double.isNaN(value.meanDailyProgress)); assertEquals("未知", value.progressText());
        assertEquals("未知", value.settlementGrade); assertEquals(4000, value.totalActualMs);
        assertFalse(value.completeInventory);
    }

    @Test public void taskUnknownIsNotZeroAndKnownSubsetIsNotReportedAsTheMean() {
        RangeSummary value = all(day(0, 60, 100, task("a", "A", 60, 100)),
                day(1, Double.NaN, 200, task("a", "A", -1, 200)));
        assertEquals(2, value.tasks.get(0).occurrenceCount);
        assertEquals(1, value.tasks.get(0).knownProgressOccurrenceCount);
        assertTrue(Double.isNaN(value.tasks.get(0).meanProgress)); assertEquals("未知", value.tasks.get(0).progressText());
        assertEquals(300, value.tasks.get(0).totalActualMs);
    }

    @Test public void unknownArchivedProgressDoesNotContaminateKnownDailyTotal() {
        LedgerStore.DayTask archived = new LedgerStore.DayTask("old", "归档", -1, -1, Double.NaN, 0, true, null);
        RangeSummary value = all(day(0, 80, 0, task("a", "已知", 80, 0), archived));
        assertEquals(80, value.meanDailyProgress, 0); assertEquals("B-", value.settlementGrade);
        assertEquals("未知", value.tasks.get(1).progressText());
    }

    @Test public void incompleteInventoryRemainsUnknownEvenIfLegacyNumberLooksFinite() {
        RangeSummary value = all(new LedgerStore.DaySnapshot(DAY,
                Collections.singletonList(task("a", "A", 90, 0)), 90, false, 0));
        assertTrue(Double.isNaN(value.meanDailyProgress)); assertEquals("未知", value.settlementGrade);
    }

    @Test public void invalidActivePriorityMetadataCannotBecomeKnownDailyProgress() {
        RangeSummary value = all(day(0, 90, 0,
                new LedgerStore.DayTask("a", "A", -1, 90, Double.NaN, 0, false, null)));
        assertEquals(0, value.knownProgressDayCount); assertTrue(Double.isNaN(value.meanDailyProgress));
        assertEquals("90%", value.tasks.get(0).progressText());
    }

    @Test public void genuinelyCompleteRangeGetsSAndNeverInheritsManualBoost() {
        List<LedgerStore.DaySnapshot> snapshots = new ArrayList<>();
        String[] grades = {"S", "SS", "SSS", "SSS+"};
        for (int i = 0; i < grades.length; i++) snapshots.add(new LedgerStore.DaySnapshot(DAY.plusDays(i),
                Collections.singletonList(task("a", "A", 100, 0)), 100, true, 0, grades[i]));
        RangeSummary value = RangeSummary.create(V2Reports.DateRange.all(), snapshots);
        assertTrue(value.genuineCompletion); assertEquals("100%", value.progressText()); assertEquals("S", value.settlementGrade);
        for (int i = 0; i < grades.length; i++) assertEquals(grades[i], snapshots.get(i).settlementGrade);
    }

    @Test public void multiDateRequestWithOnlyOneRecordedDayStillHasAutomaticSummaryGrade() {
        LedgerStore.DaySnapshot snapshot = new LedgerStore.DaySnapshot(DAY, Collections.singletonList(task("a", "A", 100, 0)),
                100, true, 0, "SSS+");
        RangeSummary value = RangeSummary.create(V2Reports.DateRange.month(2026, 10), Collections.singletonList(snapshot));
        assertEquals(1, value.recordedDayCount); assertEquals("S", value.settlementGrade);
        assertEquals("SSS+", snapshot.settlementGrade);
    }

    @Test public void rawAverageDeterminesGradeRatherThanRoundedDisplayOrDailyLetters() {
        RangeSummary value = all(day(0, 93.999, 0, task("a", "A", 93, 0)),
                day(1, 93.999, 0, task("a", "A", 94, 0)));
        assertEquals("93.99%", value.progressText()); assertEquals("A-", value.settlementGrade);
        RangeSummary mixed = all(day(0, 100, 0, task("a", "A", 100, 0)),
                day(1, 60, 0, task("a", "A", 60, 0)));
        assertEquals("B-", mixed.settlementGrade);
    }

    @Test public void binaryMeanConversionCannotRoundUpAcrossAnyAutomaticGradeBoundary() {
        double[] thresholds = {60, 70, 80, 84, 87, 90, 94, 97, 100};
        String[] below = {"E", "D", "C", "B-", "B", "B+", "A-", "A", "A+"};
        String[] at = {"D", "C", "B-", "B", "B+", "A-", "A", "A+", "A+"};
        for (int i = 0; i < thresholds.length; i++) {
            double threshold = thresholds[i];
            RangeSummary near = all(day(0, Math.nextDown(threshold), 0, task("a", "A", 99, 0)),
                    day(1, threshold, 0, task("a", "A", 99, 0)));
            assertTrue("raw mean below " + threshold, near.meanDailyProgress < threshold);
            assertEquals("grade below " + threshold, below[i], near.settlementGrade);
            RangeSummary exact = all(day(0, threshold, 0, task("a", "A", 99, 0)));
            assertEquals("grade at " + threshold, at[i], exact.settlementGrade);
        }
    }

    @Test public void binaryHundredWithoutGenuineCompletionIsNotS() {
        RangeSummary value = all(day(0, 100, 0, task("a", "未完成", 99, 0)));
        assertFalse(value.genuineCompletion); assertEquals("A+", value.settlementGrade); assertEquals("<100%", value.progressText());
    }

    @Test public void completeEmptyDayIsZeroAndIsNotGenuineCompletion() {
        RangeSummary empty = all(day(0, 0, 0));
        assertEquals(0, empty.meanDailyProgress, 0); assertEquals("E", empty.settlementGrade); assertFalse(empty.genuineCompletion);
        RangeSummary mixed = all(day(0, 0, 0), day(1, 100, 0, task("a", "A", 100, 0)));
        assertEquals(50, mixed.meanDailyProgress, 0); assertEquals(100, mixed.tasks.get(0).meanProgress, 0);
    }

    @Test public void unknownInventoryWithNoTasksStaysUnknownInsteadOfEmpty() {
        RangeSummary value = all(new LedgerStore.DaySnapshot(DAY, Collections.emptyList(), Double.NaN, false, 0));
        assertFalse(value.completeInventory); assertTrue(value.tasks.isEmpty()); assertEquals("未知", value.progressText());
    }

    @Test public void missingIdentifiersDoNotMergeOnTitleOrFabricatedIdentity() {
        RangeSummary value = all(day(0, 20, 0, task(null, "同名", 20, 0), task("", "同名", 20, 0)),
                day(1, 40, 0, task(null, "同名", 40, 0)));
        assertEquals(3, value.tasks.size());
        for (RangeSummary.MergedTask task : value.tasks) assertEquals(1, task.occurrenceCount);
    }

    @Test public void rangeFiltersOutUnrequestedSnapshotsBeforeCalculatingAnything() {
        RangeSummary value = RangeSummary.create(V2Reports.DateRange.custom(DAY, DAY.plusDays(1)), Arrays.asList(
                day(-1, 0, 100000, task("x", "范围外", 0, 100000)), day(0, 90, 100, task("a", "A", 90, 100)),
                day(2, 0, 100000, task("x", "范围外", 0, 100000))));
        assertEquals(1, value.recordedDayCount); assertEquals(90, value.meanDailyProgress, 0);
        assertEquals(100, value.totalActualMs); assertEquals(1, value.tasks.size());
    }

    @Test public void manualAttentionIsOnlyCountedThroughAuthoritativeDailyTotal() {
        LedgerStore.DaySnapshot snapshot = new LedgerStore.DaySnapshot(DAY,
                Collections.singletonList(task("a", "A", 50, 200)), 50, true, 1200, null, 1000);
        RangeSummary value = all(snapshot);
        assertEquals(1200, value.totalActualMs); assertEquals(200, value.tasks.get(0).totalActualMs);
    }

    @Test public void negativeUnknownDurationIsNotClampedToZeroOrSilentlyOmitted() {
        RangeSummary value = all(day(0, 50, -1, task("a", "A", 50, -1)),
                day(1, 50, 100, task("a", "A", 50, 100)));
        assertEquals(-1, value.totalActualMs); assertEquals("未知", value.actualText()); assertEquals(1, value.knownActualDayCount);
        assertEquals(-1, value.tasks.get(0).totalActualMs); assertEquals("未知", value.tasks.get(0).actualText());
    }

    @Test(expected = IllegalArgumentException.class) public void overflowingAttentionIsRejectedRatherThanWrapping() {
        all(day(0, 0, Long.MAX_VALUE), day(1, 0, 1));
    }

    @Test(expected = IllegalArgumentException.class) public void duplicateDatesCannotDoubleCountTheDenominator() {
        all(day(0, 10, 0), day(0, 90, 0));
    }

    @Test(expected = IllegalArgumentException.class) public void duplicateTaskIdsOnOneDayCannotDoubleCountAnOccurrence() {
        all(day(0, 0, 0, task("a", "A", 20, 0), task("a", "B", 40, 0)));
    }

    @Test(expected = IllegalArgumentException.class) public void emptyRangeHasNoInventedMeanOrGrade() { all(); }

    @Test(expected = UnsupportedOperationException.class) public void resultTaskListIsImmutable() {
        all(day(0, 0, 0)).tasks.clear();
    }

    @Test(expected = CancellationException.class) public void cancellationIsCheckedInsideAggregation() {
        AtomicInteger checks = new AtomicInteger();
        RangeSummary.create(V2Reports.DateRange.all(), Arrays.asList(
                day(0, 20, 0, task("a", "A", 20, 0)), day(1, 40, 0, task("a", "A", 40, 0))),
                () -> { if (checks.incrementAndGet() >= 4) throw new CancellationException(); });
    }

    @Test public void fullTitleIsRetainedWithoutEllipsis() {
        String title = String.join("", Collections.nCopies(10000, "读书🧠"));
        RangeSummary value = all(day(0, 30, 0, task("a", title, 30, 0)));
        assertEquals(title, value.tasks.get(0).title);
    }
}
