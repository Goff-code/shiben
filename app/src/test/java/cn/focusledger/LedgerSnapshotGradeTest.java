package cn.focusledger;

import cn.focusledger.core.PriorityProgress;
import org.junit.Test;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class LedgerSnapshotGradeTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    private static LedgerStore.DayTask item(String id, int rank, int progress, double weight, boolean archived) {
        return new LedgerStore.DayTask(id, id, rank, progress, weight, 0L, archived, null);
    }

    @Test public void genuineCompletionChecksEveryTaskEvenWhenWeightedTotalRoundsTo100() {
        List<LedgerStore.DayTask> items = new ArrayList<>();
        List<Integer> progress = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            int value = i == 79 ? 99 : 100;
            items.add(item("t" + i, i + 1, value, PriorityProgress.weight(i + 1, 80), false));
            progress.add(value);
        }
        double raw = PriorityProgress.total(progress);
        assertEquals(100d, raw, 0d); // A tiny unfinished final task is lost in binary floating arithmetic.
        LedgerStore.DaySnapshot snapshot = new LedgerStore.DaySnapshot(DAY, items, raw, true, 0L, "SSS+");
        assertFalse(snapshot.genuineCompletion);
        assertEquals("A+", snapshot.settlementGrade);
    }

    @Test public void allKnownActiveTasksCompleteWhileArchivedEvidenceStaysIndependent() {
        List<LedgerStore.DayTask> items = Arrays.asList(item("live", 1, 100, 1d, false),
                item("archived", -1, -1, Double.NaN, true));
        LedgerStore.DaySnapshot snapshot = new LedgerStore.DaySnapshot(DAY, items, 100d, true, 456L, "SSS", 123L);
        assertTrue(snapshot.genuineCompletion);
        assertEquals("SSS", snapshot.settlementGrade);
        assertEquals(123L, snapshot.totalManualMs);
        LedgerStore.DaySnapshot overlay = snapshot.withActualTotals(items, 999L);
        assertEquals("SSS", overlay.settlementGrade);
        assertEquals(123L, overlay.totalManualMs);
        assertEquals(999L, overlay.totalActualMs);
    }

    @Test public void existingConstructorAutomaticallyComputesGrade() {
        assertEquals("B+", new LedgerStore.DaySnapshot(DAY,
                Collections.singletonList(item("t", 1, 87, 1d, false)), 87d, true, 0L).settlementGrade);
        assertEquals("S", new LedgerStore.DaySnapshot(DAY,
                Collections.singletonList(item("t", 1, 100, 1d, false)), 100d, true, 0L).settlementGrade);
    }

    @Test public void knownEmptyDayIsEButUnknownInventoryRemainsUnknown() {
        LedgerStore.DaySnapshot empty = new LedgerStore.DaySnapshot(DAY, Collections.emptyList(), 0d, true, 0L, "SS");
        assertFalse(empty.genuineCompletion);
        assertEquals("E", empty.settlementGrade);
        LedgerStore.DaySnapshot unknown = new LedgerStore.DaySnapshot(DAY,
                Collections.singletonList(item("legacy", 1, 100, 1d, false)), 100d, false, 0L, "SS");
        assertFalse(unknown.genuineCompletion);
        assertEquals("未知", unknown.settlementGrade);
    }

    @Test public void incompleteAndUnknownMetadataCannotBeManuallyUpgraded() {
        for (LedgerStore.DayTask item : Arrays.asList(item("partial", 1, 99, 1d, false),
                item("legacy-rank", -1, 100, 1d, false), item("legacy-weight", 1, 100, Double.NaN, false))) {
            LedgerStore.DaySnapshot snapshot = new LedgerStore.DaySnapshot(DAY,
                    Collections.singletonList(item), 99.99d, true, 0L, "SSS+");
            assertFalse(snapshot.genuineCompletion);
            assertEquals(item.rank < 1 || Double.isNaN(item.weight) ? "未知" : "A+", snapshot.settlementGrade);
        }
    }
}
