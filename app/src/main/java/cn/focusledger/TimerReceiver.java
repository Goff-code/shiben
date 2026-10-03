package cn.focusledger;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class TimerReceiver extends BroadcastReceiver {
    public static final String EXPIRE="cn.focusledger.EXPIRE";
    public static final String PAUSE="cn.focusledger.PAUSE";
    public static final String RESUME="cn.focusledger.RESUME";
    @Override public void onReceive(Context context, Intent intent) {
        TimerCoordinator timer=TimerCoordinator.get(context);
        String action=intent.getAction();
        try {
            if(PAUSE.equals(action)) timer.pause();
            else if(RESUME.equals(action)) timer.resume();
            else { timer.refresh(); timer.schedule(); }
        } catch (RuntimeException failure) {
            // Leave durable state intact. UI reports recovery/storage issues on next open.
            android.util.Log.e("FocusLedger","Timer transition failed",failure);
        }
    }
}
