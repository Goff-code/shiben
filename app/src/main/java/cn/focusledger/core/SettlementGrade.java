package cn.focusledger.core;

/** Settlement uses the raw weighted percentage, never the rounded display value. */
public final class SettlementGrade {
    public static final String UNKNOWN = "未知";
    private SettlementGrade() { }

    /** Genuine completion must be established from every active task's known progress. */
    public static String automatic(double rawProgress, boolean genuineCompletion) {
        if (Double.isNaN(rawProgress) || Double.isInfinite(rawProgress)) return UNKNOWN;
        if (genuineCompletion) return "S";
        if (rawProgress >= 97d) return "A+";
        if (rawProgress >= 94d) return "A";
        if (rawProgress >= 90d) return "A-";
        if (rawProgress >= 87d) return "B+";
        if (rawProgress >= 84d) return "B";
        if (rawProgress >= 80d) return "B-";
        if (rawProgress >= 70d) return "C";
        if (rawProgress >= 60d) return "D";
        return "E";
    }

    public static boolean isManualGrade(String grade) {
        return "S".equals(grade) || "SS".equals(grade) || "SSS".equals(grade) || "SSS+".equals(grade);
    }

    public static String resolve(double rawProgress, boolean genuineCompletion, String manualGrade) {
        if (genuineCompletion && !Double.isNaN(rawProgress) && !Double.isInfinite(rawProgress)
                && isManualGrade(manualGrade)) return manualGrade;
        return automatic(rawProgress, genuineCompletion);
    }
}
