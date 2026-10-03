package cn.focusledger.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Calendar-day accounting from only focused intervals, using each record's zone. */
public final class DailyTotals {
    private DailyTotals() { }

    public static Map<LocalDate, Long> totalsByDay(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("Session is required");
        }
        ZoneId zone = ZoneId.of(session.zoneId);
        Map<LocalDate, Long> totals = new TreeMap<>();
        for (Segment segment : session.segments) {
            long cursor = segment.startWallMs;
            while (cursor < segment.endWallMs) {
                LocalDate day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
                // atStartOfDay handles gaps/overlaps and 23/25-hour days correctly.
                Instant nextDay = day.plusDays(1L).atStartOfDay(zone).toInstant();
                Instant segmentEnd = Instant.ofEpochMilli(segment.endWallMs);
                // Avoid overflowing epoch milliseconds at the edges of the long range.
                long boundary = nextDay.isAfter(segmentEnd)
                        ? segment.endWallMs : nextDay.toEpochMilli();
                if (boundary <= cursor) {
                    throw new IllegalArgumentException("Time zone does not advance the day boundary");
                }
                long end = Math.min(segment.endWallMs, boundary);
                long duration = end - cursor;
                totals.put(day, Math.addExact(totals.getOrDefault(day, 0L), duration));
                cursor = end;
            }
        }
        return Collections.unmodifiableMap(totals);
    }

    /** Callers should provide deduplicated records (Session.id is their unique key). */
    public static Map<LocalDate, Long> totalsByDay(Iterable<Session> sessions) {
        if (sessions == null) {
            throw new IllegalArgumentException("Sessions are required");
        }
        Map<LocalDate, Long> totals = new TreeMap<>();
        for (Session session : sessions) {
            for (Map.Entry<LocalDate, Long> entry : totalsByDay(session).entrySet()) {
                totals.put(entry.getKey(), Math.addExact(
                        totals.getOrDefault(entry.getKey(), 0L), entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(totals);
    }
}
