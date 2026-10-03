package cn.focusledger.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SessionTest {
    private static void rejects(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation rejection.
        }
    }

    private Session session(long planned, long actual, List<Segment> segments) {
        return new Session("id", "task", "Title", "project", planned, actual,
                1000L, 2000L, segments, TimerEngine.REASON_EARLY_END,
                false, false, "UTC");
    }

    @Test public void segmentRetainsNormalizedIntervalAndElapsedDuration() {
        Segment segment = new Segment(1000L, 1600L, 600L);
        assertEquals(1000L, segment.startWallMs);
        assertEquals(1600L, segment.endWallMs);
        assertEquals(600L, segment.actualMs);
    }

    @Test public void segmentRejectsNegativeReversedOrMismatchedIntervals() {
        rejects(() -> new Segment(1000L, 1001L, -1L));
        rejects(() -> new Segment(1000L, 999L, 1L));
        rejects(() -> new Segment(1000L, 2000L, 100L));
        rejects(() -> new Segment(1000L, 1100L, 1000L));
        rejects(() -> new Segment(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(0L, new Segment(1000L, 1000L, 0L).actualMs);
    }

    @Test public void constructorRequiresExactSumAndNonnegativeDurations() {
        List<Segment> segments = Collections.singletonList(new Segment(0L, 100L, 100L));
        rejects(() -> session(-1L, 100L, segments));
        rejects(() -> session(100L, -1L, segments));
        rejects(() -> session(100L, 99L, segments));
        rejects(() -> session(100L, 101L, segments));
        rejects(() -> session(100L, 100L, null));
        rejects(() -> session(100L, 100L, Arrays.asList((Segment) null)));
    }

    @Test public void sumOverflowIsRejectedEvenForIndividuallyValidSegments() {
        List<Segment> segments = Arrays.asList(
                new Segment(0L, Long.MAX_VALUE, Long.MAX_VALUE),
                new Segment(0L, 1L, 1L));
        rejects(() -> session(Long.MAX_VALUE, Long.MAX_VALUE, segments));
    }

    @Test public void completedSegmentsAreDefensivelyCopiedAndUnmodifiable() {
        List<Segment> source = new ArrayList<>();
        source.add(new Segment(0L, 100L, 100L));
        Session session = session(100L, 100L, source);
        source.clear();
        assertEquals(1, session.segments.size());
        try {
            session.segments.add(new Segment(100L, 200L, 100L));
            fail("Expected immutable segments");
        } catch (UnsupportedOperationException expected) {
            // Immutable completed records.
        }
    }

    @Test public void identityTitleReasonAndZoneAreValidated() {
        rejects(() -> new Session("", "t", "Title", null, 0L, 0L,
                0L, 0L, Collections.emptyList(), "manual", true, false, "UTC"));
        rejects(() -> new Session("id", null, "Title", null, 0L, 0L,
                0L, 0L, Collections.emptyList(), "manual", true, false, "UTC"));
        rejects(() -> new Session("id", "t", null, null, 0L, 0L,
                0L, 0L, Collections.emptyList(), "manual", true, false, "UTC"));
        rejects(() -> new Session("id", "t", "Title", null, 0L, 0L,
                0L, 0L, Collections.emptyList(), "", true, false, "UTC"));
        rejects(() -> Session.manual("id", "t", "Title", null, 0L, 0L, null, false));
        rejects(() -> Session.manual("id", "t", "Title", null, 0L, 0L, "bogus", false));
    }

    @Test public void manualFactoryCreatesExactContiguousIntervalAndFlags() {
        Session session = Session.manual("manual-id", "task-id", "Read", null,
                1000L, 120_000L, "Asia/Shanghai", true);
        assertEquals("manual-id", session.id);
        assertEquals(120_000L, session.actualMs);
        assertEquals(session.actualMs, session.plannedMs);
        assertEquals(1000L, session.startedWallMs);
        assertEquals(121_000L, session.endedWallMs);
        assertTrue(session.manual);
        assertTrue(session.edited);
        assertEquals(TimerEngine.REASON_MANUAL, session.reason);
        assertEquals("Asia/Shanghai", session.zoneId);
        assertEquals(1, session.segments.size());
    }

    @Test public void manualZeroDurationIsValidAndHasNoIntervals() {
        Session session = Session.manual("id", "t", "Title", null,
                1000L, 0L, "UTC", false);
        assertEquals(0L, session.actualMs);
        assertEquals(1000L, session.endedWallMs);
        assertTrue(session.segments.isEmpty());
    }

    @Test public void manualNegativeAndEndOverflowAreRejected() {
        rejects(() -> Session.manual("id", "t", "Title", null,
                0L, -1L, "UTC", false));
        rejects(() -> Session.manual("id", "t", "Title", null,
                Long.MAX_VALUE, 1L, "UTC", false));
    }

    @Test public void aggregateTimestampRollbackIsAllowedButIntervalsStayValid() {
        Session session = new Session("id", "t", "Title", null,
                100L, 100L, 10_000L, 5100L,
                Collections.singletonList(new Segment(5000L, 5100L, 100L)),
                TimerEngine.REASON_EARLY_END, false, false, "UTC");
        assertTrue(session.endedWallMs < session.startedWallMs);
        assertEquals(100L, session.actualMs);
    }

    @Test public void sessionsAndSegmentsSerializeAndRetainImmutability() throws Exception {
        Session original = Session.manual("id", "t", "Title", "p",
                1000L, 60_000L, "UTC", true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        Session restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Session) in.readObject();
        }
        assertEquals(original.id, restored.id);
        assertEquals(original.actualMs, restored.actualMs);
        assertEquals(original.zoneId, restored.zoneId);
        assertTrue(restored.edited);
        assertEquals(60_000L, restored.segments.get(0).actualMs);
        try {
            restored.segments.clear();
            fail("Expected immutable serialized segments");
        } catch (UnsupportedOperationException expected) {
            // Java serialization retains the unmodifiable list wrapper.
        }
    }
}
