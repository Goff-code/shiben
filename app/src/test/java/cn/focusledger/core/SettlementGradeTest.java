package cn.focusledger.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SettlementGradeTest {
    @Test public void rawBoundariesNeverUseRoundedDisplayPercent() {
        double[] boundaries = {60, 70, 80, 84, 87, 90, 94, 97};
        String[] exact = {"D", "C", "B-", "B", "B+", "A-", "A", "A+"};
        String[] below = {"E", "D", "C", "B-", "B", "B+", "A-", "A"};
        for (int i = 0; i < boundaries.length; i++) {
            assertEquals(exact[i], SettlementGrade.automatic(boundaries[i], false));
            assertEquals(below[i], SettlementGrade.automatic(Math.nextDown(boundaries[i]), false));
        }
        assertEquals("A+", SettlementGrade.automatic(99.9999, false));
        assertEquals("A+", SettlementGrade.automatic(100d, false));
        assertEquals("S", SettlementGrade.automatic(100d, true));
    }

    @Test public void unknownAndEmptyValuesDoNotBecomeTopGrades() {
        assertEquals("E", SettlementGrade.automatic(0, false));
        assertEquals("未知", SettlementGrade.automatic(Double.NaN, false));
        assertEquals("未知", SettlementGrade.resolve(Double.NaN, true, "SSS+"));
        assertEquals("未知", SettlementGrade.automatic(Double.POSITIVE_INFINITY, true));
    }

    @Test public void onlySFamilyCanOverrideGenuineCompletion() {
        for (String grade : new String[]{"S", "SS", "SSS", "SSS+"}) {
            assertTrue(SettlementGrade.isManualGrade(grade));
            assertEquals(grade, SettlementGrade.resolve(100, true, grade));
            assertEquals("A+", SettlementGrade.resolve(99, false, grade));
        }
        for (String grade : new String[]{null, "", "A+", "SSSS", "sss", " S"}) {
            assertFalse(SettlementGrade.isManualGrade(grade));
            assertEquals("S", SettlementGrade.resolve(100, true, grade));
        }
    }
}
