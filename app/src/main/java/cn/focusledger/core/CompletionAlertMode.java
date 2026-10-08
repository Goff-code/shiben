package cn.focusledger.core;

/** Four views of the existing sound/vibration preferences; no new persisted key. */
public enum CompletionAlertMode {
    SILENT(false, false, "静音（仅通知）"),
    SOUND(true, false, "仅响铃"),
    VIBRATION(false, true, "仅震动"),
    SOUND_AND_VIBRATION(true, true, "响铃和震动");

    public final boolean sound;
    public final boolean vibration;
    public final String label;

    CompletionAlertMode(boolean sound, boolean vibration, String label) {
        this.sound = sound;
        this.vibration = vibration;
        this.label = label;
    }

    public static CompletionAlertMode from(boolean sound, boolean vibration) {
        if (sound) return vibration ? SOUND_AND_VIBRATION : SOUND;
        return vibration ? VIBRATION : SILENT;
    }

    /** Keep released channel IDs so an update never resets the user's system choices. */
    public String channelId() {
        return "shiben_done_" + (sound ? "sound" : "quiet") + "_" + (vibration ? "vibrate" : "still");
    }
}
