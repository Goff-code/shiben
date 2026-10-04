package cn.focusledger;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.*;
import cn.focusledger.core.*;

public final class TimerService extends Service {
    private static final String LIVE="focus_live", DONE="focus_done";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){
        TimerCoordinator timer=TimerCoordinator.get(TimerService.this);
        try {
            timer.checkpoint();
            if(!timer.engine().isActive()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return;}
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,liveNotification());
        } catch(RuntimeException error){android.util.Log.e("FocusLedger","Timer checkpoint failed",error);}
        handler.postDelayed(this,10000);
    }};
    static void channels(Context context){
        NotificationManager n=(NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel live=new NotificationChannel(LIVE,"正在专注",NotificationManager.IMPORTANCE_LOW);
        live.setDescription("手动倒计时与暂停、继续控制");live.setSound(null,null);
        n.createNotificationChannel(live);
        NotificationChannel done=new NotificationChannel(DONE,"专注到时",NotificationManager.IMPORTANCE_HIGH);
        done.setDescription("倒计时结束时提醒；声音与震动可在系统设置中调整");
        done.enableVibration(true);done.setVibrationPattern(new long[]{0,250,150,250});
        n.createNotificationChannel(done);
    }
    static PendingIntent open(Context c){return PendingIntent.getActivity(c,1,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification liveNotification(){
        TimerCoordinator t=TimerCoordinator.get(this); TimerEngine.State s=t.engine().snapshot();
        String title=s==null?"时本":s.taskTitle;
        boolean paused=t.engine().isPaused(); long remaining=t.remainingMs();
        String action=paused?TimerReceiver.RESUME:TimerReceiver.PAUSE;
        PendingIntent control=PendingIntent.getBroadcast(this,paused?3:2,new Intent(this,TimerReceiver.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,LIVE).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(paused?"已暂停 · "+title:title).setContentText("剩余 "+format(remaining)+" · 点击返回账本")
            .setContentIntent(open(this)).setOngoing(true).setOnlyAlertOnce(true).setColor(Color.rgb(35,105,90))
            .setCategory(Build.VERSION.SDK_INT>=31?Notification.CATEGORY_STOPWATCH:Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null,paused?"继续":"暂停",control).build());
        if(!paused)b.setWhen(System.currentTimeMillis()+remaining).setUsesChronometer(true).setChronometerCountDown(true);
        return b.build();
    }
    static String format(long ms){long sec=(ms+999)/1000;return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    public static void notifyFinished(Context context,Session session){
        channels(context);
        if(!TimerCoordinator.get(context).notificationsGranted())return;
        String title="expired".equals(session.reason)?"本轮专注已结束":"计时已安全恢复";
        String text="expired".equals(session.reason)?session.taskTitle+" · 已记录 "+format(session.actualMs)+"，请自行确认任务完成。":"设备重启，本轮仅记录重启前已保存的专注时间。";
        Notification n=new Notification.Builder(context,DONE).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open(context))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        ((NotificationManager)context.getSystemService(NOTIFICATION_SERVICE)).notify(2,n);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        channels(this);
        if(Build.VERSION.SDK_INT>=34)startForeground(1,liveNotification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1,liveNotification());
        handler.removeCallbacks(tick);handler.post(tick);
        return START_STICKY;
    }
    @Override public void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
