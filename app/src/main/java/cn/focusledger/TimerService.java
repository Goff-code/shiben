package cn.focusledger;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.*;
import cn.focusledger.core.*;

public final class TimerService extends Service {
    private static final String LIVE="focus_live";
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
        // The released focus_done channel is no longer used. Leave any existing
        // channel and its user settings intact, without creating it on new installs.
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
    static String completionChannel(Context context) {
        CompletionAlertMode mode=ReminderSettings.mode(context);
        String id=mode.channelId();
        NotificationChannel channel=new NotificationChannel(id,"专注结束 · "+mode.label,NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("声音和振动仍由系统音量、勿扰及渠道设置共同控制");
        channel.enableVibration(mode.vibration);
        if(mode.vibration)channel.setVibrationPattern(new long[]{0,250,150,250});
        if(mode.sound)channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        else channel.setSound(null,null);
        ((NotificationManager)context.getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        return id;
    }
    static String completionChannelWarning(Context context) {
        CompletionAlertMode mode=ReminderSettings.mode(context);
        NotificationChannel channel=((NotificationManager)context.getSystemService(NOTIFICATION_SERVICE)).getNotificationChannel(mode.channelId());
        if(channel==null)return "";
        if(channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return "此提醒方式的系统通知渠道已关闭，请在系统通知设置中检查。";
        if((channel.getSound()!=null)!=mode.sound||channel.shouldVibrate()!=mode.vibration
            ||((mode.sound||mode.vibration)&&channel.getImportance()<NotificationManager.IMPORTANCE_DEFAULT))
            return "此提醒方式的系统渠道设置与所选模式不同，实际以系统设置为准。";
        return "";
    }
    static String format(long ms){long sec=(ms+999)/1000;return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    public static void notifyFinished(Context context,Session session){
        channels(context);
        if(!ReminderSettings.enabled(context)||!TimerCoordinator.get(context).notificationsGranted())return;
        String title="expired".equals(session.reason)?"本轮专注已结束":"计时已安全恢复";
        String text="expired".equals(session.reason)?session.taskTitle+" · 已记录 "+format(session.actualMs)+"，任务进度需手动更新。":"设备重启，本轮仅记录重启前已保存的专注时间。";
        Notification n=new Notification.Builder(context,completionChannel(context)).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
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
