import cn.focusledger.core.TimerEngine;
import cn.focusledger.core.Segment;
import java.io.*;
import java.util.Objects;

/** Compare test-device snapshots without requiring a debuggable release APK. */
public final class ComparePausedTimer {
    private static int passed;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
        System.out.println("PASS " + message);
    }
    private static TimerEngine.State read(String path) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(path))) {
            in.setObjectInputFilter(info -> info.depth() > 12 || info.references() > 10000 || info.streamBytes() > 1048576
                ? ObjectInputFilter.Status.REJECTED : ObjectInputFilter.Status.UNDECIDED);
            return (TimerEngine.State) in.readObject();
        }
    }
    public static void main(String[] args) throws Exception {
        TimerEngine.State before = read(args[0]), after = read(args[1]);
        check(before.paused && after.paused, "paused timer remains paused after installed-package upgrade");
        check(before.sessionId != null && Objects.equals(before.sessionId, after.sessionId), "timer session identity retained");
        check(Objects.equals(before.taskId, after.taskId) && Objects.equals(before.taskTitle, after.taskTitle)
            && Objects.equals(before.projectId, after.projectId), "timer task title and identity retained");
        check(before.plannedMs == after.plannedMs, "planned timer duration retained");
        check(before.accumulatedMs == after.accumulatedMs, "actual accumulated timer duration retained");
        check(before.startedWallMs == after.startedWallMs && Objects.equals(before.zoneId, after.zoneId), "original timer date and timezone retained");
        check(before.segments.size() == after.segments.size(), "timer segment count retained");
        for (int i = 0; i < before.segments.size(); i++) {
            Segment a = before.segments.get(i), b = after.segments.get(i);
            check(a.startWallMs == b.startWallMs && a.endWallMs == b.endWallMs && a.actualMs == b.actualMs,
                "accounted focus segment " + i + " retained exactly");
        }
        System.out.println("OK (" + passed + " paused-timer checks)");
    }
}
