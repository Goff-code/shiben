package cn.focusledger.core;

import java.io.Serializable;

/**
 * One uninterrupted, accounted-for focus interval.
 *
 * The end is mapped from monotonic elapsed time, rather than a later wall-clock
 * reading. This makes the interval safe to split by calendar day even when the
 * device clock is adjusted. Separate segments may overlap after a clock rollback;
 * that does not duplicate focus time because each has its own elapsed duration.
 */
public final class Segment implements Serializable {
    private static final long serialVersionUID = 1L;

    public final long startWallMs;
    public final long endWallMs;
    public final long actualMs;

    public Segment(long startWallMs, long endWallMs, long actualMs) {
        if (actualMs < 0L) {
            throw new IllegalArgumentException("Segment duration cannot be negative");
        }
        final long intervalMs;
        try {
            intervalMs = Math.subtractExact(endWallMs, startWallMs);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Segment interval overflows", overflow);
        }
        if (intervalMs < 0L || intervalMs != actualMs) {
            throw new IllegalArgumentException("Segment interval must equal its elapsed duration");
        }
        this.startWallMs = startWallMs;
        this.endWallMs = endWallMs;
        this.actualMs = actualMs;
    }
}
