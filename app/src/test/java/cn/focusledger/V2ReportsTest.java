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
    @Test public void fractionWeightFormatsAndUnknownStaysUnknown() {
        assertEquals("40.00%",V2Reports.weight(.4)); assertEquals("<0.01%",V2Reports.weight(.00001)); assertEquals("未知",V2Reports.weight(Double.NaN));
    }
    @Test public void durationKeepsReadableSeconds() {
        assertEquals("0秒",V2Reports.duration(999)); assertEquals("1分 1秒",V2Reports.duration(61_999));
        assertEquals("1小时 0分 1秒",V2Reports.duration(3_601_000));
    }
    @Test(expected=IllegalArgumentException.class) public void negativeDurationRejected() { V2Reports.duration(-1); }
    @Test public void markdownEscapesSyntaxAndPreservesUnicode() {
        String value = V2Reports.markdownText("阅读🧠|[a]*_`#<script>\n第二行");
        assertTrue(value.contains("阅读🧠\\|\\[a\\]\\*\\_\\`\\#&lt;script&gt;<br>第二行"));
    }
    @Test public void completeReportContainsPriorityWeightProgressAndExactActual() {
        String value = V2Reports.dailyMarkdown(snapshot(Arrays.asList(task("Web3",2,60,.3,1001,false),
                task("阅读",1,100,.4,2000,false),task("英语",3,0,.2,0,false)),65,true));
        assertTrue(value.contains("**65%**")); assertTrue(value.contains("3001 毫秒")); assertTrue(value.contains("权重原值（0—1）")); assertTrue(value.contains("| — | 0.4 |"));
        assertTrue(value.contains("| 1 | 阅读 | 40.00% | 100% | 2秒 | 2000 |"));
        assertTrue(value.contains("| 3 | 英语 | 20.00% | 0% | 0秒 | 0 |"));
        assertTrue(value.indexOf("| 1 | 阅读") < value.indexOf("| 2 | Web3"));
    }
    @Test public void legacyReportDoesNotConvertCheckToPercentOrInventRank() {
        LedgerStore.DayTask old = new LedgerStore.DayTask("old","旧任务",-1,-1,Double.NaN,0,false,true);
        String value = V2Reports.dailyMarkdown(snapshot(Collections.singletonList(old),Double.NaN,false));
        assertTrue(value.contains("**未知**")); assertTrue(value.contains("| 未知 | 旧任务 | 未知 | 未知 |"));
        assertTrue(value.contains("已勾选")); assertTrue(value.contains("不用当前任务配置推算旧日期"));
        assertFalse(value.contains("**0%**")); assertFalse(value.contains("| 100% |"));
    }
    @Test public void archivedRowsKeptSeparateAndNotDropped() {
        String value = V2Reports.dailyMarkdown(snapshot(Arrays.asList(task("归档",1,80,1,3001,true),task("活动",1,0,1,0,false)),0,true));
        assertTrue(value.indexOf("| 1 | 活动") < value.indexOf("## 已归档任务"));
        assertTrue(value.indexOf("## 已归档任务") < value.indexOf("| 1 | 归档"));
        assertTrue(value.contains("不参与该日当前总进度分母"));
    }
    @Test public void fullLongListAndLongNamesNeverTruncated() {
        List<LedgerStore.DayTask> rows = new ArrayList<>();
        for(int i=0;i<2000;i++) rows.add(task("任务_"+i,i+1,0,1.0/2000,0,false));
        String longName = String.join("",Collections.nCopies(2000,"读书🧠"));
        rows.add(task(longName,2001,0,0,0,false));
        String value = V2Reports.dailyMarkdown(snapshot(rows,0,true));
        for(int i=0;i<2000;i++) assertTrue(value.contains("| "+(i+1)+" | 任务\\_"+i+" |"));
        assertTrue(value.contains(longName)); assertTrue(value.contains("2001 项"));
    }
    @Test public void reportIsDeterministicAndIncludesLossWarning() {
        LedgerStore.DaySnapshot snap=snapshot(Collections.emptyList(),0,true);
        assertEquals(V2Reports.dailyMarkdown(snap),V2Reports.dailyMarkdown(snap));
        assertTrue(V2Reports.dailyMarkdown(snap).contains("卸载应用或清除数据会丢失"));
        assertTrue(V2Reports.dailyMarkdown(snap).contains("不是可直接导入的数据库备份"));
    }
}
