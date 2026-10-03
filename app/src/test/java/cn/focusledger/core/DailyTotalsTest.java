package cn.focusledger.core;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class DailyTotalsTest {
    private static final long M = 60_000L;
    private static final long H = 60L * M;

    private static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static long local(String iso, String zone) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(iso), ZoneId.of(zone))
                .toInstant().toEpochMilli();
    }

    private static void assertConserved(Session session, Map<LocalDate, Long> totals) {
        long sum = 0L;
        for (long duration : totals.values()) {
            assertTrue(duration > 0L);
            sum = Math.addExact(sum, duration);
        }
        assertEquals(session.actualMs, sum);
    }

    @Test public void ordinarySessionBelongsToItsCapturedLocalDay() {
        Session session = Session.manual("id", "task", "Title", null,
                utc("2026-10-03T17:00:00Z"), 30 * M, "Asia/Shanghai", false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(1, totals.size());
        assertEquals(Long.valueOf(30 * M), totals.get(LocalDate.of(2026, 10, 4)));
        assertConserved(session, totals);
    }

    @Test public void localMidnightSplitsOneMinuteExactlyIntoTwoDays() {
        Session session = Session.manual("id", "task", "Title", null,
                utc("2026-10-03T23:59:30Z"), M, "UTC", false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(Long.valueOf(30_000L), totals.get(LocalDate.of(2026, 10, 3)));
        assertEquals(Long.valueOf(30_000L), totals.get(LocalDate.of(2026, 10, 4)));
        assertConserved(session, totals);
    }

    @Test public void endingExactlyAtMidnightDoesNotCreateZeroNextDayEntry() {
        Session session = Session.manual("id", "task", "Title", null,
                utc("2026-10-03T23:45:00Z"), 15 * M, "UTC", false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(1, totals.size());
        assertEquals(Long.valueOf(15 * M), totals.get(LocalDate.of(2026, 10, 3)));
    }

    @Test public void millisecondsArePreservedAcrossMidnight() {
        Session session = Session.manual("id", "task", "Title", null,
                utc("2026-10-03T23:59:59.999Z"), 3L, "UTC", false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(Long.valueOf(1L), totals.get(LocalDate.of(2026, 10, 3)));
        assertEquals(Long.valueOf(2L), totals.get(LocalDate.of(2026, 10, 4)));
        assertConserved(session, totals);
    }

    @Test public void pausedGapAcrossMidnightIsExcluded() {
        long start = utc("2026-10-03T23:50:00Z");
        long resume = utc("2026-10-04T00:30:00Z");
        Session session = new Session("id", "task", "Title", null,
                30 * M, 15 * M, start, resume + 10 * M,
                Arrays.asList(new Segment(start, start + 5 * M, 5 * M),
                        new Segment(resume, resume + 10 * M, 10 * M)),
                TimerEngine.REASON_EARLY_END, false, false, "UTC");
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(Long.valueOf(5 * M), totals.get(LocalDate.of(2026, 10, 3)));
        assertEquals(Long.valueOf(10 * M), totals.get(LocalDate.of(2026, 10, 4)));
        assertConserved(session, totals);
    }

    @Test public void springForwardDayHasTwentyThreeElapsedHours() {
        String zone = "America/New_York";
        long start = local("2026-03-08T00:00:00", zone);
        long end = local("2026-03-09T00:00:00", zone);
        assertEquals(23 * H, end - start);
        Session session = Session.manual("id", "task", "Title", null,
                start, end - start, zone, false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(1, totals.size());
        assertEquals(Long.valueOf(23 * H), totals.get(LocalDate.of(2026, 3, 8)));
        assertConserved(session, totals);
    }

    @Test public void fallBackDayHasTwentyFiveElapsedHours() {
        String zone = "America/New_York";
        long start = local("2026-11-01T00:00:00", zone);
        long end = local("2026-11-02T00:00:00", zone);
        assertEquals(25 * H, end - start);
        Session session = Session.manual("id", "task", "Title", null,
                start, end - start, zone, false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(1, totals.size());
        assertEquals(Long.valueOf(25 * H), totals.get(LocalDate.of(2026, 11, 1)));
        assertConserved(session, totals);
    }

    @Test public void multiDayIntervalHandlesDstWithoutAssumingTwentyFourHours() {
        String zone = "America/New_York";
        long start = local("2026-03-07T23:30:00", zone);
        long end = local("2026-03-09T00:30:00", zone);
        Session session = Session.manual("id", "task", "Title", null,
                start, end - start, zone, false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(Long.valueOf(30 * M), totals.get(LocalDate.of(2026, 3, 7)));
        assertEquals(Long.valueOf(23 * H), totals.get(LocalDate.of(2026, 3, 8)));
        assertEquals(Long.valueOf(30 * M), totals.get(LocalDate.of(2026, 3, 9)));
        assertConserved(session, totals);
    }

    @Test public void midnightDstGapUsesFirstValidTimeOfNextDay() {
        String zone = "America/Sao_Paulo";
        long start = LocalDate.of(2018, 11, 3).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli();
        long end = LocalDate.of(2018, 11, 5).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli();
        Session session = Session.manual("id", "task", "Title", null,
                start, end - start, zone, false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(Long.valueOf(24 * H), totals.get(LocalDate.of(2018, 11, 3)));
        assertEquals(Long.valueOf(23 * H), totals.get(LocalDate.of(2018, 11, 4)));
        assertConserved(session, totals);
    }

    @Test public void skippedCivilDateDoesNotReceivePhantomHours() {
        String zone = "Pacific/Apia";
        long start = LocalDate.of(2011, 12, 29).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli();
        long end = LocalDate.of(2011, 12, 31).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli();
        Session session = Session.manual("id", "task", "Title", null,
                start, end - start, zone, false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(1, totals.size());
        assertEquals(Long.valueOf(24 * H), totals.get(LocalDate.of(2011, 12, 29)));
        assertFalse(totals.containsKey(LocalDate.of(2011, 12, 30)));
        assertConserved(session, totals);
    }

    @Test public void changingDeviceZoneDoesNotChangeHistoricalTotals() {
        Session session = Session.manual("id", "task", "Title", null,
                utc("2026-10-03T23:50:00Z"), 20 * M, "UTC", false);
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
            assertEquals(Long.valueOf(10 * M), totals.get(LocalDate.of(2026, 10, 3)));
            assertEquals(Long.valueOf(10 * M), totals.get(LocalDate.of(2026, 10, 4)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test public void normalizedClockRollbackIntervalsDoNotLoseOrInflateTime() {
        long start = utc("2026-10-03T23:58:00Z");
        Session session = new Session("id", "task", "Title", null,
                15 * M, 6 * M, start, start + M,
                Arrays.asList(new Segment(start, start + 3 * M, 3 * M),
                        new Segment(start - 2 * M, start + M, 3 * M)),
                TimerEngine.REASON_EARLY_END, false, false, "UTC");
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(session);
        assertEquals(Long.valueOf(5 * M), totals.get(LocalDate.of(2026, 10, 3)));
        assertEquals(Long.valueOf(M), totals.get(LocalDate.of(2026, 10, 4)));
        assertConserved(session, totals);
    }

    @Test public void collectionAggregatesIndependentZonesAndSortedDays() {
        Session utc = Session.manual("a", "t", "A", null,
                utc("2026-10-03T17:00:00Z"), 10 * M, "UTC", false);
        Session shanghai = Session.manual("b", "t", "B", null,
                utc("2026-10-03T17:00:00Z"), 20 * M, "Asia/Shanghai", false);
        Map<LocalDate, Long> totals = DailyTotals.totalsByDay(Arrays.asList(shanghai, utc));
        assertEquals(Long.valueOf(10 * M), totals.get(LocalDate.of(2026, 10, 3)));
        assertEquals(Long.valueOf(20 * M), totals.get(LocalDate.of(2026, 10, 4)));
        assertEquals(LocalDate.of(2026, 10, 3), totals.keySet().iterator().next());
    }

    @Test public void emptyIntervalsAndEmptyCollectionProduceNoDays() {
        Session session = Session.manual("id", "t", "Title", null, 0L, 0L, "UTC", false);
        assertTrue(DailyTotals.totalsByDay(session).isEmpty());
        assertTrue(DailyTotals.totalsByDay(Collections.<Session>emptyList()).isEmpty());
    }

    @Test public void edgeEpochMillisecondsAvoidNextMidnightOverflow() {
        Session session = Session.manual("id", "t", "Title", null,
                Long.MAX_VALUE - 1L, 1L, "UTC", false);
        assertConserved(session, DailyTotals.totalsByDay(session));
        Session negative = Session.manual("id2", "t", "Title", null,
                Long.MIN_VALUE, 1L, "UTC", false);
        assertConserved(negative, DailyTotals.totalsByDay(negative));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void totalsAreUnmodifiable() {
        Session session = Session.manual("id", "t", "Title", null,
                0L, M, "UTC", false);
        DailyTotals.totalsByDay(session).clear();
    }
}
