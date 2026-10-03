package cn.focusledger.core;

import java.io.Serializable;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable completed record. The stable id is the persistence deduplication key. */
public final class Session implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String id;
    public final String taskId;
    public final String taskTitle;
    public final String projectId;
    public final long plannedMs;
    public final long actualMs;
    public final long startedWallMs;
    public final long endedWallMs;
    public final List<Segment> segments;
    public final String reason;
    public final boolean manual;
    public final boolean edited;
    public final String zoneId;

    public Session(String id, String taskId, String taskTitle, String projectId,
                   long plannedMs, long actualMs, long startedWallMs, long endedWallMs,
                   List<Segment> segments, String reason, boolean manual,
                   boolean edited, String zoneId) {
        requireText(id, "Session id");
        requireText(taskId, "Task id");
        if (taskTitle == null) {
            throw new IllegalArgumentException("Task title is required");
        }
        requireText(reason, "Completion reason");
        if (plannedMs < 0L || actualMs < 0L) {
            throw new IllegalArgumentException("Durations cannot be negative");
        }
        if (segments == null) {
            throw new IllegalArgumentException("Segments are required");
        }
        String validZone = validZone(zoneId);
        List<Segment> copy = new ArrayList<>(segments.size());
        long total = 0L;
        for (Segment segment : segments) {
            if (segment == null) {
                throw new IllegalArgumentException("Segment cannot be null");
            }
            // Revalidate records received from serialization as well as constructors.
            Segment valid = new Segment(segment.startWallMs, segment.endWallMs, segment.actualMs);
            try {
                total = Math.addExact(total, valid.actualMs);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Session duration overflows", overflow);
            }
            copy.add(valid);
        }
        if (total != actualMs) {
            throw new IllegalArgumentException("Actual duration must equal the sum of segments");
        }
        this.id = id;
        this.taskId = taskId;
        this.taskTitle = taskTitle;
        this.projectId = projectId;
        this.plannedMs = plannedMs;
        this.actualMs = actualMs;
        this.startedWallMs = startedWallMs;
        this.endedWallMs = endedWallMs;
        this.segments = Collections.unmodifiableList(copy);
        this.reason = reason;
        this.manual = manual;
        this.edited = edited;
        this.zoneId = validZone;
    }

    /** A manually entered contiguous interval; use the same id when editing a record. */
    public static Session manual(String id, String taskId, String taskTitle,
                                 String projectId, long startWallMs, long actualMs,
                                 String zoneId, boolean edited) {
        if (actualMs < 0L) {
            throw new IllegalArgumentException("Manual duration cannot be negative");
        }
        final long endWallMs;
        try {
            endWallMs = Math.addExact(startWallMs, actualMs);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Manual interval overflows", overflow);
        }
        List<Segment> intervals = actualMs == 0L
                ? Collections.emptyList()
                : Collections.singletonList(new Segment(startWallMs, endWallMs, actualMs));
        return new Session(id, taskId, taskTitle, projectId, actualMs, actualMs,
                startWallMs, endWallMs, intervals, TimerEngine.REASON_MANUAL,
                true, edited, zoneId);
    }

    static void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    static String validZone(String zoneId) {
        if (zoneId == null) {
            throw new IllegalArgumentException("Time zone is required");
        }
        try {
            return ZoneId.of(zoneId).getId();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid time zone: " + zoneId, invalid);
        }
    }
}
