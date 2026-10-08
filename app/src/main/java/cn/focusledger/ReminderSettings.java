package cn.focusledger;

import android.content.Context;
import android.content.SharedPreferences;
import cn.focusledger.core.CompletionAlertMode;

/** Preserve released preferences, including the independent reminder master switch. */
final class ReminderSettings {
    private ReminderSettings() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("shiben-settings", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return preferences(context).getBoolean("reminders", true);
    }

    static CompletionAlertMode mode(Context context) {
        SharedPreferences prefs = preferences(context);
        return CompletionAlertMode.from(prefs.getBoolean("sound", true), prefs.getBoolean("vibration", true));
    }

    static void setMode(Context context, CompletionAlertMode mode) {
        preferences(context).edit().putBoolean("sound", mode.sound).putBoolean("vibration", mode.vibration).apply();
    }
}
