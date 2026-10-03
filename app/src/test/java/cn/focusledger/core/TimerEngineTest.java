package cn.focusledger.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class TimerEngineTest {
    private static final long M = 60_000L;
    private static final long W = 1_700_000_000_000L;
    private static final long E = 10_000L;
    private static final String BOOT = "boot-one";

    private TimerEngine start(int minutes) {
        TimerEngine engine = new TimerEngine("UTC");
        assertNull(engine.start("task-1", "Write outline", "project-1", minutes, W, E, BOOT));
        return engine;
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation rejection.
        }
    }

    @Test public void newEngineHasNoSession() {
        TimerEngine engine = new TimerEngine();
        assertFalse(engine.isActive());
        assertFalse(engine.isPaused());
        assertNull(engine.snapshot().sessionId);
        assertEquals(0L, engine.currentActualMs(W, E, BOOT));
        assertEquals(0L, engine.remainingMs(W, E, BOOT));
        assertNull(engine.finish(W, E, BOOT, TimerEngine.REASON_EARLY_END));
    }

    @Test public void nullRestoredStateIsSafe() {
        assertFalse(new TimerEngine((TimerEngine.State) null).isActive());
        assertFalse(new TimerEngine(null, "UTC").isActive());
    }

    @Test public void plansHaveFifteenMinuteMinimumAndFiveMinuteStep() {
        for (int minutes : new int[]{Integer.MIN_VALUE, -5, 0, 5, 10, 14, 16, 19, 21}) {
            rejects(() -> start(minutes));
        }
        for (int minutes : new int[]{15, 20, 25, 55, 120, 2_147_483_645}) {
            TimerEngine engine = start(minutes);
            assertEquals(minutes * M, engine.snapshot().plannedMs);
        }
    }

    @Test(expected = IllegalStateException.class)
    public void cannotStartSecondActiveSession() {
        start(15).start("task-2", "Other", null, 20, W, E, BOOT);
    }

    @Test(expected = IllegalStateException.class)
    public void cannotStartSecondSessionWhilePaused() {
        TimerEngine engine = start(15);
        engine.pause(W + M, E + M, BOOT);
        engine.start("task-2", "Other", null, 20, W + M, E + M, BOOT);
    }

    @Test public void invalidStartDataDoesNotLeaveActiveState() {
        TimerEngine engine = new TimerEngine("UTC");
        rejects(() -> engine.start("", "Title", null, 15, W, E, BOOT));
        rejects(() -> engine.start("t", null, null, 15, W, E, BOOT));
        rejects(() -> engine.start("t", "Title", null, 15, W, -1L, BOOT));
        rejects(() -> engine.start("t", "Title", null, 15, W, E, ""));
        assertFalse(engine.isActive());
    }

    @Test public void elapsedUsesMonotonicTimeInsteadOfWallTime() {
        TimerEngine engine = start(15);
        assertEquals(2 * M, engine.currentActualMs(W + 500 * M, E + 2 * M, BOOT));
        assertEquals(2 * M, engine.currentActualMs(W - 500 * M, E + 2 * M, BOOT));
        assertEquals(13 * M, engine.remainingMs(W, E + 2 * M, BOOT));
    }

    @Test public void pauseAndResumeExcludeLongPause() {
        TimerEngine engine = start(15);
        assertNull(engine.pause(W + 5 * M, E + 5 * M, BOOT));
        assertTrue(engine.isPaused());
        assertEquals(5 * M, engine.currentActualMs(W + 30 * M, E + 30 * M, BOOT));
        assertNull(engine.reconcile(W + 30 * M, E + 30 * M, BOOT));
        assertNull(engine.resume(W + 30 * M, E + 30 * M, BOOT));
        Session session = engine.finish(W + 32 * M, E + 32 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(7 * M, session.actualMs);
        assertEquals(2, session.segments.size());
        assertEquals(W + 5 * M, session.segments.get(0).endWallMs);
        assertEquals(W + 30 * M, session.segments.get(1).startWallMs);
        assertEquals(W + 32 * M, session.endedWallMs);
    }

    @Test public void repeatedPauseIsIdempotent() {
        TimerEngine engine = start(15);
        engine.pause(W + 2 * M, E + 2 * M, BOOT);
        assertNull(engine.pause(W + 7 * M, E + 7 * M, BOOT));
        assertEquals(2 * M, engine.snapshot().accumulatedMs);
        assertEquals(1, engine.snapshot().segments.size());
        Session session = engine.finish(W + 8 * M, E + 8 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(2 * M, session.actualMs);
        assertEquals(W + 8 * M, session.endedWallMs);
    }

    @Test public void resumeWhileRunningDoesNotResetAnchorOrLoseElapsed() {
        TimerEngine engine = start(15);
        assertNull(engine.resume(W + 8 * M, E + 8 * M, BOOT));
        assertEquals(10 * M, engine.currentActualMs(W + 10 * M, E + 10 * M, BOOT));
        Session session = engine.reconcile(W + 15 * M, E + 15 * M, BOOT);
        assertEquals(15 * M, session.actualMs);
        assertEquals(W + 15 * M, session.endedWallMs);
    }

    @Test public void delayedExpiryCapsDurationAndEndpoint() {
        TimerEngine engine = start(15);
        assertEquals(15 * M, engine.currentActualMs(W + 100 * M, E + 100 * M, BOOT));
        Session session = engine.reconcile(W + 100 * M, E + 100 * M, BOOT);
        assertEquals(TimerEngine.REASON_EXPIRED, session.reason);
        assertEquals(15 * M, session.actualMs);
        assertEquals(W + 15 * M, session.endedWallMs);
        assertEquals(W + 15 * M, session.segments.get(0).endWallMs);
        assertFalse(session.manual);
        assertFalse(session.edited);
        assertFalse(engine.isActive());
    }

    @Test public void expiryAfterPauseUsesResumedSegmentEndpoint() {
        TimerEngine engine = start(15);
        engine.pause(W + 5 * M, E + 5 * M, BOOT);
        engine.resume(W + 30 * M, E + 30 * M, BOOT);
        Session session = engine.reconcile(W + 100 * M, E + 100 * M, BOOT);
        assertEquals(15 * M, session.actualMs);
        assertEquals(W + 40 * M, session.endedWallMs);
        assertEquals(10 * M, session.segments.get(1).actualMs);
    }

    @Test public void pauseAtDeadlineCompletesInstead() {
        TimerEngine engine = start(15);
        Session session = engine.pause(W + 15 * M, E + 15 * M, BOOT);
        assertEquals(TimerEngine.REASON_EXPIRED, session.reason);
        assertFalse(engine.isActive());
        assertFalse(engine.isPaused());
    }

    @Test public void finishAtOrAfterDeadlineKeepsExpiredReason() {
        Session session = start(15).finish(W + 20 * M, E + 20 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(TimerEngine.REASON_EXPIRED, session.reason);
        assertEquals(15 * M, session.actualMs);
    }

    @Test public void earlyFinishPreservesIdentityAndTaskSnapshot() {
        TimerEngine engine = start(20);
        String id = engine.snapshot().sessionId;
        Session session = engine.finish(W + 3 * M, E + 3 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(id, session.id);
        assertEquals("task-1", session.taskId);
        assertEquals("Write outline", session.taskTitle);
        assertEquals("project-1", session.projectId);
        assertEquals(20 * M, session.plannedMs);
        assertEquals(3 * M, session.actualMs);
        assertEquals(W, session.startedWallMs);
        assertEquals("UTC", session.zoneId);
    }

    @Test public void finishAndReconcileAreIdempotentAndAllowNewUniqueSession() {
        TimerEngine engine = start(15);
        Session first = engine.finish(W + M, E + M, BOOT, TimerEngine.REASON_EARLY_END);
        assertNull(engine.finish(W + 2 * M, E + 2 * M, BOOT, TimerEngine.REASON_EARLY_END));
        assertNull(engine.reconcile(W + 30 * M, E + 30 * M, BOOT));
        assertNull(engine.pause(W + 30 * M, E + 30 * M, BOOT));
        assertNull(engine.resume(W + 30 * M, E + 30 * M, BOOT));
        assertNull(engine.checkpoint(W + 30 * M, E + 30 * M, BOOT));
        engine.start("task-1", "Write outline", "project-1", 15,
                W + 30 * M, E + 30 * M, BOOT);
        assertNotEquals(first.id, engine.snapshot().sessionId);
        assertEquals(0L, engine.snapshot().accumulatedMs);
    }

    @Test public void immediateEarlyFinishCreatesValidZeroDurationSession() {
        Session session = start(15).finish(W, E, BOOT, TimerEngine.REASON_EARLY_END);
        assertEquals(0L, session.actualMs);
        assertTrue(session.segments.isEmpty());
        assertTrue(DailyTotals.totalsByDay(session).isEmpty());
    }

    @Test public void checkpointCommitsAndReanchorsWithoutResettingTotal() {
        TimerEngine engine = start(15);
        assertNull(engine.checkpoint(W + 4 * M, E + 4 * M, BOOT));
        TimerEngine.State state = engine.snapshot();
        assertEquals(4 * M, state.accumulatedMs);
        assertEquals(E + 4 * M, state.checkpointElapsedMs);
        assertEquals(W + 4 * M, state.segmentStartWallMs);
        assertEquals(7 * M, engine.currentActualMs(W + 7 * M, E + 7 * M, BOOT));
        Session session = engine.reconcile(W + 30 * M, E + 30 * M, BOOT);
        assertEquals(15 * M, session.actualMs);
        assertEquals(W + 15 * M, session.endedWallMs);
        assertEquals(2, session.segments.size());
    }

    @Test public void checkpointAtDeadlineReturnsCompletion() {
        TimerEngine engine = start(15);
        Session session = engine.checkpoint(W + 17 * M, E + 17 * M, BOOT);
        assertEquals(TimerEngine.REASON_EXPIRED, session.reason);
        assertNull(engine.snapshot().sessionId);
    }

    @Test public void processDeathOnSameBootRecoversOpenMonotonicDelta() {
        TimerEngine original = start(15);
        TimerEngine restored = new TimerEngine(original.snapshot(), "UTC");
        assertEquals(8 * M, restored.currentActualMs(W + 8 * M, E + 8 * M, BOOT));
        Session session = restored.finish(W + 8 * M, E + 8 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(8 * M, session.actualMs);
        assertEquals(original.snapshot().sessionId, session.id);
    }

    @Test public void processDeathAfterCheckpointRecoversOnlyRemainingOpenDelta() {
        TimerEngine original = start(15);
        original.checkpoint(W + 5 * M, E + 5 * M, BOOT);
        TimerEngine restored = new TimerEngine(original.snapshot());
        Session session = restored.finish(W + 8 * M, E + 8 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(8 * M, session.actualMs);
        assertEquals(2, session.segments.size());
        assertEquals(5 * M, session.segments.get(0).actualMs);
        assertEquals(3 * M, session.segments.get(1).actualMs);
    }

    @Test public void restoringExpiredSessionSameBootCapsOriginalDeadline() {
        TimerEngine original = start(15);
        original.checkpoint(W + 5 * M, E + 5 * M, BOOT);
        TimerEngine restored = new TimerEngine(original.snapshot());
        Session session = restored.reconcile(W + 1000 * M, E + 1000 * M, BOOT);
        assertEquals(15 * M, session.actualMs);
        assertEquals(W + 15 * M, session.endedWallMs);
        assertNull(restored.reconcile(W + 1001 * M, E + 1001 * M, BOOT));
    }

    @Test public void rebootEndsAtLastCheckpointWithoutCountingDowntime() {
        TimerEngine original = start(15);
        original.checkpoint(W + 5 * M, E + 5 * M, BOOT);
        TimerEngine restored = new TimerEngine(original.snapshot());
        assertEquals(5 * M, restored.currentActualMs(W + 1000 * M, M, "boot-two"));
        Session session = restored.reconcile(W + 1000 * M, M, "boot-two");
        assertEquals(TimerEngine.REASON_INTERRUPTED_RESTART, session.reason);
        assertEquals(5 * M, session.actualMs);
        assertEquals(W + 5 * M, session.endedWallMs);
        assertNull(restored.reconcile(W + 1001 * M, 2 * M, "boot-two"));
    }

    @Test public void rebootWithoutCheckpointDoesNotGuessUnpersistedTime() {
        TimerEngine restored = new TimerEngine(start(15).snapshot());
        Session session = restored.reconcile(W + 1000 * M, 4 * M, "boot-two");
        assertEquals(0L, session.actualMs);
        assertEquals(W, session.endedWallMs);
        assertTrue(session.segments.isEmpty());
    }

    @Test public void rebootWhilePausedDoesNotResumeOrCountPause() {
        TimerEngine original = start(15);
        original.pause(W + 3 * M, E + 3 * M, BOOT);
        TimerEngine restored = new TimerEngine(original.snapshot());
        Session session = restored.resume(W + 800 * M, M, "boot-two");
        assertEquals(3 * M, session.actualMs);
        assertEquals(W + 3 * M, session.endedWallMs);
        assertEquals(TimerEngine.REASON_INTERRUPTED_RESTART, session.reason);
        assertFalse(restored.isActive());
    }

    @Test public void monotonicResetWithSameBootIdIsConservativelyInterrupted() {
        TimerEngine engine = start(15);
        engine.checkpoint(W + 5 * M, E + 5 * M, BOOT);
        Session session = engine.finish(W + 100 * M, E, BOOT, TimerEngine.REASON_EARLY_END);
        assertEquals(TimerEngine.REASON_INTERRUPTED_RESTART, session.reason);
        assertEquals(5 * M, session.actualMs);
    }

    @Test public void displayReadsDoNotAdvanceRecoveryCheckpoint() {
        TimerEngine engine = start(15);
        engine.checkpoint(W + 3 * M, E + 3 * M, BOOT);
        assertEquals(8 * M, engine.currentActualMs(W + 8 * M, E + 8 * M, BOOT));
        assertNull(engine.reconcile(W + 8 * M, E + 8 * M, BOOT));
        Session session = new TimerEngine(engine.snapshot()).reconcile(W + 100 * M, M, "boot-two");
        assertEquals(3 * M, session.actualMs);
    }

    @Test public void pausedCheckpointCanAdvanceRecoveryWallWithoutFocusTime() {
        TimerEngine engine = start(15);
        engine.pause(W + 2 * M, E + 2 * M, BOOT);
        engine.checkpoint(W + 40 * M, E + 40 * M, BOOT);
        Session session = new TimerEngine(engine.snapshot()).reconcile(W + 100 * M, M, "boot-two");
        assertEquals(2 * M, session.actualMs);
        assertEquals(W + 40 * M, session.endedWallMs);
        assertEquals(W + 2 * M, session.segments.get(0).endWallMs);
    }

    @Test public void snapshotsAndRestoreInputsAreDefensiveCopies() {
        TimerEngine engine = start(15);
        engine.checkpoint(W + M, E + M, BOOT);
        TimerEngine.State snapshot = engine.snapshot();
        snapshot.taskTitle = "Tampered";
        snapshot.segments.clear();
        assertEquals("Write outline", engine.snapshot().taskTitle);
        assertEquals(1, engine.snapshot().segments.size());
        TimerEngine.State input = engine.snapshot();
        TimerEngine restored = new TimerEngine(input);
        input.taskTitle = "Tampered";
        input.segments.clear();
        assertEquals("Write outline", restored.snapshot().taskTitle);
        assertEquals(1, restored.snapshot().segments.size());
    }

    @Test public void serializedStateRestoresWithoutAndroidDependencies() throws Exception {
        TimerEngine engine = start(15);
        engine.checkpoint(W + 4 * M, E + 4 * M, BOOT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(engine.snapshot());
        }
        TimerEngine.State restoredState;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restoredState = (TimerEngine.State) in.readObject();
        }
        Session session = new TimerEngine(restoredState).finish(W + 6 * M, E + 6 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(6 * M, session.actualMs);
        assertEquals(2, session.segments.size());
    }

    @Test public void forwardClockJumpNeverAddsElapsedAndReanchorsNextSegment() {
        TimerEngine engine = start(15);
        engine.checkpoint(W + 100 * M, E + 5 * M, BOOT);
        Session session = engine.finish(W + 102 * M, E + 7 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(7 * M, session.actualMs);
        assertEquals(W + 5 * M, session.segments.get(0).endWallMs);
        assertEquals(W + 100 * M, session.segments.get(1).startWallMs);
        assertEquals(W + 102 * M, session.endedWallMs);
    }

    @Test public void backwardClockJumpKeepsNonnegativeNormalizedSegments() {
        TimerEngine engine = start(15);
        engine.checkpoint(W - 100 * M, E + 5 * M, BOOT);
        Session session = engine.finish(W - 98 * M, E + 7 * M, BOOT,
                TimerEngine.REASON_EARLY_END);
        assertEquals(7 * M, session.actualMs);
        assertEquals(W + 5 * M, session.segments.get(0).endWallMs);
        assertEquals(W - 100 * M, session.segments.get(1).startWallMs);
        assertTrue(session.endedWallMs < session.startedWallMs);
        long total = 0L;
        for (long duration : DailyTotals.totalsByDay(session).values()) total += duration;
        assertEquals(session.actualMs, total);
    }

    @Test public void expiryAfterClockJumpCapsAtNewAnchorPlusRemaining() {
        TimerEngine engine = start(15);
        engine.checkpoint(W + 100 * M, E + 5 * M, BOOT);
        Session session = engine.reconcile(W + 500 * M, E + 100 * M, BOOT);
        assertEquals(15 * M, session.actualMs);
        assertEquals(W + 110 * M, session.endedWallMs);
    }

    @Test public void monotonicNearLongMaxCannotOverflowOrExceedPlan() {
        TimerEngine engine = new TimerEngine("UTC");
        engine.start("t", "Title", null, 15, W, 0L, BOOT);
        Session session = engine.reconcile(W, Long.MAX_VALUE, BOOT);
        assertEquals(15 * M, session.actualMs);
        assertEquals(W + 15 * M, session.endedWallMs);
    }

    @Test public void invalidWallOverflowDoesNotPartiallyAccount() {
        TimerEngine engine = new TimerEngine("UTC");
        engine.start("t", "Title", null, 15, Long.MAX_VALUE - 5L, 0L, BOOT);
        rejects(() -> engine.finish(Long.MAX_VALUE, 10L, BOOT, TimerEngine.REASON_EARLY_END));
        assertTrue(engine.isActive());
        assertEquals(0L, engine.snapshot().accumulatedMs);
        assertTrue(engine.snapshot().segments.isEmpty());
    }

    @Test public void invalidPersistedStatesAreRejected() {
        TimerEngine.State badTotal = start(15).snapshot();
        badTotal.accumulatedMs = M;
        rejects(() -> new TimerEngine(badTotal));
        TimerEngine.State badPlan = start(15).snapshot();
        badPlan.plannedMs = 16 * M;
        rejects(() -> new TimerEngine(badPlan));
        TimerEngine.State badAnchor = start(15).snapshot();
        badAnchor.segmentStartElapsedMs++;
        rejects(() -> new TimerEngine(badAnchor));
        TimerEngine.State badZone = start(15).snapshot();
        badZone.zoneId = "not/a-zone";
        rejects(() -> new TimerEngine(badZone));
        TimerEngine.State badSegments = start(15).snapshot();
        badSegments.segments = null;
        rejects(() -> new TimerEngine(badSegments));
    }

    @Test public void newStartsCaptureCurrentZoneButActiveSessionKeepsItsZone() {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            TimerEngine engine = new TimerEngine();
            engine.start("t", "Title", null, 15, W, E, BOOT);
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Session first = engine.finish(W + M, E + M, BOOT, TimerEngine.REASON_EARLY_END);
            assertEquals("Asia/Shanghai", first.zoneId);
            engine.start("t", "Title", null, 15, W + M, E + M, BOOT);
            assertEquals("America/New_York", engine.snapshot().zoneId);
        } finally {
            TimeZone.setDefault(originalZone);
        }
    }
}
