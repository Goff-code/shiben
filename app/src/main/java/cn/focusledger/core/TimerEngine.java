package cn.focusledger.core;

import java.io.Serializable;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Android-independent, single-session monotonic timer.
 *
 * Feed elapsedRealtime() and a stable per-boot id from the platform. Snapshot
 * after checkpoints and persist a returned completion and that snapshot in one
 * transaction, using Session.id as a unique key. The engine never counts paused
 * time. Reboot recovery conservatively keeps only already checkpointed time.
 * Calls are synchronized, but persistence must still serialize its transaction.
 */
public final class TimerEngine {
    public static final String REASON_EXPIRED = "expired";
    public static final String REASON_EARLY_END = "early_end";
    public static final String REASON_INTERRUPTED_RESTART = "interrupted_restart";
    public static final String REASON_MANUAL = "manual";
    public static final long MINUTE_MS = 60_000L;
    public static final int MIN_PLANNED_MINUTES = 15;
    public static final int PLANNED_STEP_MINUTES = 5;

    /** Mutable DTO for persistence. Engine snapshots and restores are defensive copies. */
    public static final class State implements Serializable {
        private static final long serialVersionUID = 1L;

        public String sessionId;
        public String taskId;
        public String taskTitle;
        public String projectId;
        public String bootId;
        public String zoneId;
        public long plannedMs;
        public long startedWallMs;
        public long accumulatedMs;
        public long segmentStartWallMs;
        public long segmentStartElapsedMs;
        public long checkpointWallMs;
        public long checkpointElapsedMs;
        public boolean paused;
        public List<Segment> segments = new ArrayList<>();

        public State() { }

        private State(State source) {
            sessionId = source.sessionId;
            taskId = source.taskId;
            taskTitle = source.taskTitle;
            projectId = source.projectId;
            bootId = source.bootId;
            zoneId = source.zoneId;
            plannedMs = source.plannedMs;
            startedWallMs = source.startedWallMs;
            accumulatedMs = source.accumulatedMs;
            segmentStartWallMs = source.segmentStartWallMs;
            segmentStartElapsedMs = source.segmentStartElapsedMs;
            checkpointWallMs = source.checkpointWallMs;
            checkpointElapsedMs = source.checkpointElapsedMs;
            paused = source.paused;
            if (source.segments == null) {
                throw new IllegalArgumentException("State segments are required");
            }
            segments = new ArrayList<>(source.segments);
        }
    }

    private State state;
    private final String defaultZoneId;

    public TimerEngine() {
        this(null, null);
    }

    public TimerEngine(String zoneId) {
        this(null, zoneId);
    }

    public TimerEngine(State restored) {
        this(restored, null);
    }

    public TimerEngine(State restored, String zoneId) {
        // null means capture the device's current zone at each new start.
        defaultZoneId = zoneId == null ? null : Session.validZone(zoneId);
        state = restored == null ? new State() : new State(restored);
        validateState(state);
    }

    public synchronized State snapshot() {
        return new State(state);
    }

    public synchronized boolean isActive() {
        return state.sessionId != null;
    }

    public synchronized boolean isPaused() {
        return isActive() && state.paused;
    }

    public synchronized Session start(String taskId, String taskTitle, String projectId,
                                      int plannedMinutes, long wallMs, long elapsedMs,
                                      String bootId) {
        validateNow(elapsedMs, bootId);
        if (isActive()) {
            throw new IllegalStateException("Only one focus session can be active");
        }
        validatePlannedMinutes(plannedMinutes);
        Session.requireText(taskId, "Task id");
        if (taskTitle == null) {
            throw new IllegalArgumentException("Task title is required");
        }
        State fresh = new State();
        fresh.sessionId = UUID.randomUUID().toString();
        fresh.taskId = taskId;
        fresh.taskTitle = taskTitle;
        fresh.projectId = projectId;
        fresh.plannedMs = plannedMinutes * MINUTE_MS;
        fresh.startedWallMs = wallMs;
        fresh.bootId = bootId;
        fresh.zoneId = defaultZoneId == null ? ZoneId.systemDefault().getId() : defaultZoneId;
        anchor(fresh, wallMs, elapsedMs);
        state = fresh;
        return null;
    }

    /** Pausing at or beyond expiry completes the session rather than pausing it. */
    public synchronized Session pause(long wallMs, long elapsedMs, String bootId) {
        Session completion = reconcile(wallMs, elapsedMs, bootId);
        if (completion != null || !isActive() || state.paused) {
            return completion;
        }
        commitOpen(elapsedMs);
        state.paused = true;
        anchor(state, wallMs, elapsedMs);
        return null;
    }

    public synchronized Session resume(long wallMs, long elapsedMs, String bootId) {
        Session completion = reconcile(wallMs, elapsedMs, bootId);
        if (completion != null || !isActive() || !state.paused) {
            return completion;
        }
        state.paused = false;
        anchor(state, wallMs, elapsedMs);
        return null;
    }

    /**
     * Commits active elapsed time, then establishes a fresh wall/monotonic anchor.
     * Persist the resulting snapshot promptly. After reboot, time since this
     * persisted checkpoint is deliberately not guessed from the wall clock.
     */
    public synchronized Session checkpoint(long wallMs, long elapsedMs, String bootId) {
        Session completion = reconcile(wallMs, elapsedMs, bootId);
        if (completion != null || !isActive()) {
            return completion;
        }
        if (!state.paused) {
            commitOpen(elapsedMs);
        }
        anchor(state, wallMs, elapsedMs);
        return null;
    }

    public synchronized Session finish(long wallMs, long elapsedMs,
                                       String bootId, String reason) {
        Session completion = reconcile(wallMs, elapsedMs, bootId);
        if (completion != null || !isActive()) {
            return completion;
        }
        Session.requireText(reason, "Completion reason");
        long endpoint = wallMs;
        if (!state.paused) {
            endpoint = commitOpen(elapsedMs);
        }
        return complete(endpoint, reason);
    }

    /**
     * Detects expiry or reboot without advancing a non-expired checkpoint.
     * Returns null when no new completed record exists, including repeated calls.
     */
    public synchronized Session reconcile(long wallMs, long elapsedMs, String bootId) {
        validateNow(elapsedMs, bootId);
        if (!isActive()) {
            return null;
        }
        if (!state.bootId.equals(bootId) || elapsedMs < state.checkpointElapsedMs) {
            // A reboot (or reset monotonic source) makes the open delta unknowable.
            return complete(state.checkpointWallMs, REASON_INTERRUPTED_RESTART);
        }
        if (!state.paused && openElapsedMs(elapsedMs) >= remainingCommittedMs()) {
            long endpoint = commitOpen(elapsedMs);
            return complete(endpoint, REASON_EXPIRED);
        }
        return null;
    }

    /** Read-only capped duration. Call reconcile separately to collect completions. */
    public synchronized long currentActualMs(long wallMs, long elapsedMs, String bootId) {
        validateNow(elapsedMs, bootId);
        if (!isActive()) {
            return 0L;
        }
        if (state.paused || !state.bootId.equals(bootId)
                || elapsedMs < state.checkpointElapsedMs) {
            return state.accumulatedMs;
        }
        return state.accumulatedMs + Math.min(openElapsedMs(elapsedMs), remainingCommittedMs());
    }

    public synchronized long remainingMs(long wallMs, long elapsedMs, String bootId) {
        return isActive() ? state.plannedMs - currentActualMs(wallMs, elapsedMs, bootId) : 0L;
    }

    public static void validatePlannedMinutes(int minutes) {
        if (minutes < MIN_PLANNED_MINUTES || minutes % PLANNED_STEP_MINUTES != 0) {
            throw new IllegalArgumentException("Duration must be at least 15 minutes in 5-minute steps");
        }
    }

    private long openElapsedMs(long elapsedMs) {
        return Math.max(0L, elapsedMs - state.segmentStartElapsedMs);
    }

    private long remainingCommittedMs() {
        return state.plannedMs - state.accumulatedMs;
    }

    private long commitOpen(long elapsedMs) {
        long duration = Math.min(openElapsedMs(elapsedMs), remainingCommittedMs());
        final long endpoint;
        try {
            endpoint = Math.addExact(state.segmentStartWallMs, duration);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Wall interval overflows", overflow);
        }
        if (duration > 0L) {
            state.segments.add(new Segment(state.segmentStartWallMs, endpoint, duration));
            state.accumulatedMs += duration;
        }
        return endpoint;
    }

    private Session complete(long endpoint, String reason) {
        Session completion = new Session(state.sessionId, state.taskId, state.taskTitle,
                state.projectId, state.plannedMs, state.accumulatedMs,
                state.startedWallMs, endpoint, state.segments, reason,
                false, false, state.zoneId);
        state = new State();
        return completion;
    }

    private static void anchor(State target, long wallMs, long elapsedMs) {
        target.segmentStartWallMs = wallMs;
        target.segmentStartElapsedMs = elapsedMs;
        target.checkpointWallMs = wallMs;
        target.checkpointElapsedMs = elapsedMs;
    }

    private static void validateNow(long elapsedMs, String bootId) {
        if (elapsedMs < 0L) {
            throw new IllegalArgumentException("Monotonic time cannot be negative");
        }
        Session.requireText(bootId, "Boot id");
    }

    private static void validateState(State candidate) {
        if (candidate.sessionId == null) {
            // Empty state has no resumable record; ignore other stale fields.
            return;
        }
        Session.requireText(candidate.sessionId, "Session id");
        Session.requireText(candidate.taskId, "Task id");
        Session.requireText(candidate.bootId, "Boot id");
        if (candidate.taskTitle == null) {
            throw new IllegalArgumentException("Task title is required");
        }
        Session.validZone(candidate.zoneId);
        if (candidate.plannedMs < MIN_PLANNED_MINUTES * MINUTE_MS
                || candidate.plannedMs % (PLANNED_STEP_MINUTES * MINUTE_MS) != 0L
                || candidate.accumulatedMs < 0L
                || candidate.accumulatedMs > candidate.plannedMs
                || candidate.segmentStartElapsedMs < 0L
                || candidate.checkpointElapsedMs < 0L
                || candidate.segmentStartElapsedMs != candidate.checkpointElapsedMs
                || candidate.segmentStartWallMs != candidate.checkpointWallMs) {
            throw new IllegalArgumentException("Invalid persisted timer state");
        }
        long total = 0L;
        List<Segment> validSegments = new ArrayList<>();
        for (Segment segment : candidate.segments) {
            if (segment == null) {
                throw new IllegalArgumentException("State segment cannot be null");
            }
            Segment valid = new Segment(segment.startWallMs, segment.endWallMs, segment.actualMs);
            try {
                total = Math.addExact(total, valid.actualMs);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("State duration overflows", overflow);
            }
            validSegments.add(valid);
        }
        if (total != candidate.accumulatedMs) {
            throw new IllegalArgumentException("State elapsed time does not match its segments");
        }
        candidate.segments = validSegments;
    }
}
