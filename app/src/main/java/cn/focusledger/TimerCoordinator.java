package cn.focusledger;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import cn.focusledger.core.Session;
import cn.focusledger.core.TimerEngine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Single-process authority. Every state transition and terminal record is persisted atomically. */
public final class TimerCoordinator {
    @android.annotation.SuppressLint("StaticFieldLeak") // Application context only, never an Activity.
    private static TimerCoordinator instance;
    private final Context context;
    private final LedgerStore store;
    private TimerEngine engine;
    private long lastCheckpointElapsed;
    private String warning = "";
    private final String fallbackBootId="unknown-"+java.util.UUID.randomUUID();
    public static synchronized TimerCoordinator get(Context context) {
        if (instance == null) instance = new TimerCoordinator(context.getApplicationContext());
        return instance;
    }
    private TimerCoordinator(Context context) {
        this.context = context;
        store = new LedgerStore(context);
        engine = new TimerEngine(store.loadTimer());
        lastCheckpointElapsed = SystemClock.elapsedRealtime();
    }
    public LedgerStore store() { return store; }
    public synchronized TimerEngine engine() { return engine; }
    public synchronized String warning() { return warning; }
    private String bootId() {
        try { return "boot-" + Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT); }
        catch (Exception ignored) { return fallbackBootId; }
    }
    private void persist(Session session) {
        store.persistTimer(engine.snapshot(), session);
        lastCheckpointElapsed = SystemClock.elapsedRealtime();
    }
    public synchronized void start(LedgerStore.Task task, int minutes) {
        refresh();
        TimerEngine.State before = engine.snapshot();
        try {
            engine.start(task.id, task.title, task.projectId, minutes, System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId());
            persist(null);
        } catch (RuntimeException failure) { engine = new TimerEngine(before); throw failure; }
        schedule();
        startService();
    }
    public synchronized void pause() {
        Session expired = refresh();
        if (!engine.isActive() || expired != null) return;
        TimerEngine.State before = engine.snapshot();
        try { Session s = engine.pause(System.currentTimeMillis(),SystemClock.elapsedRealtime(),bootId()); persist(s); if(s != null) completeSideEffects(s); }
        catch (RuntimeException failure) { engine = new TimerEngine(before); throw failure; }
        cancelAlarm();
        if(engine.isActive()) startService();
    }
    public synchronized void resume() {
        refresh();
        if (!engine.isActive()) return;
        TimerEngine.State before = engine.snapshot();
        try { Session s = engine.resume(System.currentTimeMillis(),SystemClock.elapsedRealtime(),bootId()); persist(s); if(s != null) completeSideEffects(s); }
        catch (RuntimeException failure) { engine = new TimerEngine(before); throw failure; }
        schedule();
        if(engine.isActive()) startService();
    }
    public synchronized void finish() {
        Session expired = refresh();
        if (!engine.isActive() || expired != null) return;
        TimerEngine.State before = engine.snapshot();
        try { Session s = engine.finish(System.currentTimeMillis(),SystemClock.elapsedRealtime(),bootId(), "early_end"); persist(s); if(s != null && !"early_end".equals(s.reason)) completeSideEffects(s); }
        catch (RuntimeException failure) { engine = new TimerEngine(before); throw failure; }
        cancelAlarm();
        context.stopService(new Intent(context,TimerService.class));
    }
    public synchronized Session refresh() {
        TimerEngine.State before = engine.snapshot();
        Session completed;
        try {
            completed = engine.reconcile(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId());
            if (completed != null) persist(completed);
        } catch (RuntimeException failure) { engine = new TimerEngine(before); throw failure; }
        if (completed != null) completeSideEffects(completed);
        return completed;
    }
    public synchronized void checkpoint() {
        if (refresh() != null || !engine.isActive()) return;
        long now = SystemClock.elapsedRealtime();
        if (now-lastCheckpointElapsed < 10000) return;
        TimerEngine.State before=engine.snapshot();
        try { Session s=engine.checkpoint(System.currentTimeMillis(),now,bootId()); persist(s); if(s != null) completeSideEffects(s); }
        catch (RuntimeException failure) { engine=new TimerEngine(before); throw failure; }
    }
    private void completeSideEffects(Session session) {
        cancelAlarm();
        TimerService.notifyFinished(context,session);
        context.stopService(new Intent(context,TimerService.class));
    }
    public synchronized long actualMs() {
        return engine.isActive() ? engine.currentActualMs(System.currentTimeMillis(),SystemClock.elapsedRealtime(),bootId()) : 0;
    }
    /** One read-only timer projection shared by the Today screen and exported reports.
     * The clone uses the same monotonic clock/zone/segments as the actual timer, so
     * pauses and midnight are accounted identically without saving a fake session.
     */
    private Session projectActiveSession() {
        TimerEngine copy = new TimerEngine(engine.snapshot());
        return copy.finish(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId(), "report_preview");
    }
    public synchronized LedgerStore.DaySnapshot snapshotForDay(LocalDate day) {
        synchronized (store) { return store.getDaySnapshot(day, projectActiveSession()); }
    }
    /** Captures all selected days at one instant before background formatting begins. */
    public synchronized List<LedgerStore.DaySnapshot> snapshotsForDays(List<LocalDate> days) {
        if (days == null) throw new IllegalArgumentException("日期不能为空");
        synchronized (store) {
            Session active = projectActiveSession();
            List<LedgerStore.DaySnapshot> snapshots = new ArrayList<>(days.size());
            for (LocalDate day : days) snapshots.add(store.getDaySnapshot(day, active));
            return Collections.unmodifiableList(snapshots);
        }
    }
    public synchronized long remainingMs() {
        TimerEngine.State state=engine.snapshot();
        return state == null ? 0 : Math.max(0,state.plannedMs-actualMs());
    }
    public boolean exactAlarmGranted() {
        AlarmManager alarms=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms();
    }
    public boolean notificationsGranted() {
        return (Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            && ((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled();
    }
    private PendingIntent alarmIntent() {
        return PendingIntent.getBroadcast(context,31,new Intent(context,TimerReceiver.class).setAction(TimerReceiver.EXPIRE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    public synchronized void schedule() {
        cancelAlarm();
        if (!engine.isActive() || engine.isPaused()) return;
        AlarmManager alarms=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        long due=SystemClock.elapsedRealtime()+remainingMs();
        try {
            if(exactAlarmGranted()) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,due,alarmIntent());
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,due,alarmIntent());
        } catch(SecurityException revoked) {
            warning="精确提醒权限已关闭，锁屏提醒可能延后；记录仍按计划时长封顶。";
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,due,alarmIntent());
        }
    }
    private void cancelAlarm() { ((AlarmManager)context.getSystemService(Context.ALARM_SERVICE)).cancel(alarmIntent()); }
    public synchronized void restoreFromForeground() {
        refresh();
        if(engine.isActive()) { schedule(); startService(); }
    }
    private void startService() {
        try { context.startForegroundService(new Intent(context,TimerService.class)); warning=""; }
        catch(RuntimeException denied) { warning="系统未允许后台计时服务，请保持应用可用并开启精确提醒；时间记录仍会在返回时校正。"; }
    }
}
