package cn.focusledger;

import org.junit.Test;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class V2ReportsTest {
    private final LocalDate day = LocalDate.of(2026, 10, 4);
    private LedgerStore.DayTask task(String name, int rank, int progress, double weight, long actual, boolean archived) {
        return new LedgerStore.DayTask(name, name, rank, progress, weight, actual, archived, null);
    }
    private LedgerStore.DaySnapshot snapshot(List<LedgerStore.DayTask> rows, double progress, boolean complete) {
        return new LedgerStore.DaySnapshot(day, rows, progress, complete, 3_001);
    }
    @Test public void dayIsInclusive() {
        V2Reports.DateRange range = V2Reports.DateRange.day(day);
        assertTrue(range.contains(day)); assertFalse(range.contains(day.minusDays(1)));
        assertFalse(range.contains(day.plusDays(1))); assertEquals(1, range.inclusiveDays());
    }
    @Test public void customIncludesBothEndpoints() {
        V2Reports.DateRange range = V2Reports.DateRange.custom(day.minusDays(3), day);
        assertEquals(4, range.inclusiveDays()); assertTrue(range.contains(day.minusDays(3))); assertTrue(range.contains(day));
    }
    @Test public void monthRespectsLeapYear() {
        V2Reports.DateRange leap = V2Reports.DateRange.month(YearMonth.of(2024, 2));
        assertEquals(29, leap.inclusiveDays()); assertEquals(LocalDate.of(2024,2,29), leap.end);
        assertEquals(28, V2Reports.DateRange.month(2026,2).inclusiveDays());
    }
    @Test public void yearRespectsLeapYear() {
        assertEquals(366, V2Reports.DateRange.year(2024).inclusiveDays());
        assertEquals(365, V2Reports.DateRange.year(2026).inclusiveDays());
    }
    @Test(expected=IllegalArgumentException.class) public void reversedRangeRejected() { V2Reports.DateRange.custom(day, day.minusDays(1)); }
    @Test(expected=IllegalArgumentException.class) public void nullDayDoesNotMeanAll() { V2Reports.DateRange.day(null); }
    @Test(expected=IllegalArgumentException.class) public void nullCustomEndRejected() { V2Reports.DateRange.custom(day,null); }
    @Test(expected=IllegalArgumentException.class) public void yearZeroRejected() { V2Reports.DateRange.year(0); }
    @Test public void allUsesRecordedDaysOnlyChronologicalAndDistinct() {
        assertEquals(Arrays.asList(day.minusDays(3), day), V2Reports.selectDays(
                Arrays.asList(day, day.minusDays(3), day, null), V2Reports.DateRange.all()));
        assertEquals(-1, V2Reports.DateRange.all().inclusiveDays());
    }
    @Test public void rangeFiltersRecordsWithoutCreatingMissingDates() {
        assertEquals(Collections.singletonList(day), V2Reports.selectDays(Arrays.asList(day.minusDays(30),day),
                V2Reports.DateRange.month(2026,10)));
        assertTrue(V2Reports.selectDays(Collections.emptyList(), V2Reports.DateRange.all()).isEmpty());
    }
    @Test public void fileNamesSafeAndDeterministic() {
        assertEquals("时本_2026-10-04",V2Reports.DateRange.day(day).fileStem());
        assertEquals("时本_2026-10-01_2026-10-31",V2Reports.DateRange.month(2026,10).fileStem());
        assertEquals("时本_全部记录",V2Reports.DateRange.all().fileStem());
    }
    @Test public void percentFormatsUnknownWithoutFabrication() {
        assertEquals("未知",V2Reports.percent(Double.NaN)); assertEquals("未知",V2Reports.percent(Double.POSITIVE_INFINITY));
        assertEquals("65%",V2Reports.percent(65)); assertEquals("66.7%",V2Reports.percent(200.0/3));
        assertEquals("0%",V2Reports.percent(-5)); assertEquals("100%",V2Reports.percent(105));
        assertEquals("未知",V2Reports.taskProgress(-1));
    }
    @Test public void incompleteTaskNeverDisplaysRounded100Percent() {
        assertEquals("99.99%", V2Reports.totalPercent(snapshot(Collections.singletonList(task("未完成",1,99,1,0,false)),99.99,true)));
        assertEquals("99.99%", V2Reports.totalPercent(snapshot(Collections.singletonList(task("未完成",1,99,1,0,false)),99.999999,true)));
        assertEquals("<100%", V2Reports.totalPercent(snapshot(Collections.singletonList(task("未完成",1,99,1,0,false)),100,true)));
        assertEquals("100%", V2Reports.totalPercent(snapshot(Collections.singletonList(task("已完成",1,100,1,0,false)),100,true)));
    }
    @Test public void totalDisplayNeverRoundsAcrossAnySettlementBoundary() {
        List<LedgerStore.DayTask> rows=Collections.singletonList(task("任务",1,93,1,0,false));
        assertEquals("93.99%",V2Reports.totalPercent(snapshot(rows,93.999,true)));
        assertEquals("96.99%",V2Reports.totalPercent(snapshot(rows,96.999,true)));
        assertEquals("93.96%",V2Reports.totalPercent(snapshot(rows,93.96,true)));
        assertEquals("65.37%",V2Reports.totalPercent(snapshot(rows,65.375,true)));
        assertEquals("65%",V2Reports.totalPercent(snapshot(rows,65,true)));
        assertEquals("0%",V2Reports.totalPercent(snapshot(rows,0,true)));
    }
    @Test public void fractionWeightFormatsAndUnknownStaysUnknown() {
        assertEquals("40.00%",V2Reports.weight(.4)); assertEquals("<0.01%",V2Reports.weight(.00001)); assertEquals("未知",V2Reports.weight(Double.NaN));
    }
    @Test public void durationKeepsReadableSeconds() {
        assertEquals("0秒",V2Reports.duration(999)); assertEquals("1分钟1秒",V2Reports.duration(61_999));
        assertEquals("1小时0分钟1秒",V2Reports.duration(3_601_000));
    }
    @Test(expected=IllegalArgumentException.class) public void negativeDurationRejected() { V2Reports.duration(-1); }
    @Test public void markdownEscapesSyntaxAndPreservesUnicode() {
        String value = V2Reports.markdownText("阅读🧠|[a]*_`#<script>\n第二行");
        assertTrue(value.contains("阅读🧠\\|\\[a\\]\\*\\_\\`\\#&lt;script&gt;<br>第二行"));
    }
    @Test public void compactReportMatchesApprovedExampleExactly() {
        LedgerStore.DaySnapshot sample = new LedgerStore.DaySnapshot(LocalDate.of(2026,10,5), Arrays.asList(
                task("Web3",2,60,.25,0,false), task("阅读",1,100,.5,0,false),
                task("英语",3,0,.125,0,false), task("代码视频",4,0,.125,0,false)),65,true,8_100_000,"D");
        assertEquals("# 2026-10-05｜时本\n\n"
                + "## 今日投资注意力时长总计\n2小时15分钟\n\n"
                + "## 总完成度\n65%\n\n"
                + "## 当日任务与完成度\n- 阅读：100%\n- Web3：60%\n- 英语：0%\n- 代码视频：0%\n\n"
                + "## 当日结算等级\nD\n", V2Reports.dailyMarkdown(sample));
    }
    @Test public void completeReportContainsOnlyDateAndFourSections() {
        String value = V2Reports.dailyMarkdown(snapshot(Arrays.asList(task("Web3",2,60,.3,1001,false),
                task("阅读",1,100,.4,2000,false),task("英语",3,0,.2,0,false)),65,true));
        assertTrue(value.startsWith("# 2026-10-04｜时本\n"));
        assertTrue(value.contains("## 总完成度\n65%\n"));
        assertTrue(value.contains("## 今日投资注意力时长总计\n3秒\n"));
        assertEquals(4, value.split("(?m)^## ", -1).length - 1);
        assertTrue(value.indexOf("- 阅读：100%") < value.indexOf("- Web3：60%"));
        assertTrue(value.contains("- 英语：0%"));
        for (String technical : Arrays.asList("毫秒", "权重", "旧版完成", "SQLite", "专注明细", "保存", "口径", "元数据"))
            assertFalse(technical, value.contains(technical));
    }
    @Test public void legacyReportDoesNotConvertCheckToPercentOrInventGrade() {
        LedgerStore.DayTask old = new LedgerStore.DayTask("old","旧任务",-1,-1,Double.NaN,0,false,true);
        String value = V2Reports.dailyMarkdown(snapshot(Collections.singletonList(old),Double.NaN,false));
        assertTrue(value.contains("## 总完成度\n未知\n"));
        assertTrue(value.contains("- 旧任务：未知\n"));
        assertTrue(value.contains("## 当日结算等级\n未知\n"));
        assertFalse(value.contains("已勾选")); assertFalse(value.contains("0%")); assertFalse(value.contains("100%"));
    }
    @Test public void archivedAndActiveRowsAreAllRetainedInRecordedRankOrder() {
        String value = V2Reports.dailyMarkdown(snapshot(Arrays.asList(task("活动第三",3,0,.125,0,false),
                task("归档第二",2,80,.25,3001,true),task("活动第一",1,0,.5,0,false)),0,true));
        assertTrue(value.indexOf("- 活动第一：0%") < value.indexOf("- 归档第二：80%"));
        assertTrue(value.indexOf("- 归档第二：80%") < value.indexOf("- 活动第三：0%"));
        assertEquals(4, value.split("(?m)^## ", -1).length - 1);
    }
    @Test public void unknownPriorityDoesNotAcquireAFictionalRank() {
        LedgerStore.DayTask first = task("未知先",-1,-1,Double.NaN,0,false);
        LedgerStore.DayTask second = task("未知后",-1,-1,Double.NaN,0,false);
        List<LedgerStore.DayTask> ordered = V2Reports.orderedTasks(snapshot(Arrays.asList(first,
                task("已知第二",2,30,.5,0,true), second, task("已知第一",1,60,.5,0,false)),Double.NaN,false));
        assertEquals("已知第一", ordered.get(0).title); assertEquals("已知第二", ordered.get(1).title);
        assertSame(first, ordered.get(2)); assertSame(second, ordered.get(3));
    }
    @Test public void fullLongListAndLongNamesNeverTruncated() {
        List<LedgerStore.DayTask> rows = new ArrayList<>();
        for(int i=0;i<2000;i++) rows.add(task("任务_"+i,i+1,0,1.0/2000,0,false));
        String longName = String.join("",Collections.nCopies(2000,"读书🧠"));
        rows.add(task(longName,2001,0,0,0,false));
        String value = V2Reports.dailyMarkdown(snapshot(rows,0,true));
        for(int i=0;i<2000;i++) assertTrue(value.contains("- 任务\\_"+i+"：0%"));
        assertTrue(value.contains(longName)); assertEquals(2001, value.split("(?m)^- ",-1).length-1);
    }
    @Test public void reportIsDeterministicWithoutUnrequestedFooter() {
        LedgerStore.DaySnapshot snap=snapshot(Collections.emptyList(),0,true);
        assertEquals(V2Reports.dailyMarkdown(snap),V2Reports.dailyMarkdown(snap));
        assertTrue(V2Reports.dailyMarkdown(snap).contains("## 当日任务与完成度\n无\n"));
        assertFalse(V2Reports.dailyMarkdown(snap).contains("卸载"));
        assertFalse(V2Reports.dailyMarkdown(snap).contains("备份"));
    }
    @Test public void absentHistoricalInventoryIsUnknownInsteadOfEmpty() {
        assertTrue(V2Reports.dailyMarkdown(snapshot(Collections.emptyList(),Double.NaN,false))
                .contains("## 当日任务与完成度\n未知\n"));
    }
    @Test public void snapshotsGradeIsUsedRatherThanRecalculatedInReport() {
        LedgerStore.DaySnapshot snap=new LedgerStore.DaySnapshot(day,
                Collections.singletonList(task("任务",1,100,1,0,false)),100,true,0,"SSS+");
        assertTrue(V2Reports.dailyMarkdown(snap).endsWith("## 当日结算等级\nSSS+\n"));
    }
}
