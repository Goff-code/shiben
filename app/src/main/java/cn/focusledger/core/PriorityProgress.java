package cn.focusledger.core;

import java.util.List;

/** V2 rule B: halve the remaining weight; the last two ranks share the remainder. */
public final class PriorityProgress {
    private PriorityProgress() { }

    public static double weight(int rank, int count) {
        if (count < 1 || rank < 1 || rank > count)
            throw new IllegalArgumentException("Priority must be in 1..task count");
        if (count == 1) return 1d;
        return Math.scalb(1d, -Math.min(rank, count - 1));
    }

    public static double[] weights(int count) {
        if (count < 0) throw new IllegalArgumentException("Task count cannot be negative");
        double[] result = new double[count];
        for (int i = 0; i < count; i++) result[i] = weight(i + 1, count);
        return result;
    }

    public static int requireProgress(int progress) {
        if (progress < 0 || progress > 100)
            throw new IllegalArgumentException("进度须在 0% 到 100% 之间");
        return progress;
    }

    /** Unknown values remain unknown; elapsed time never enters this calculation. */
    public static double total(List<Integer> progress) {
        if (progress == null) throw new IllegalArgumentException("Progress list is required");
        double result = 0d;
        for (int i = 0; i < progress.size(); i++) {
            Integer value = progress.get(i);
            if (value == null || value == -1) return Double.NaN;
            result += requireProgress(value) * weight(i + 1, progress.size());
        }
        return result;
    }
}
