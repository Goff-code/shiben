package cn.focusledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import cn.focusledger.core.*;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;

/** Runs only in a disposable emulator. No test hooks or demo records ship in the app APK. */
public final class LedgerInstrumentation extends Instrumentation {
    private int passed;
    private final StringBuilder log=new StringBuilder();
    private void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;log.append("PASS ").append(name).append('\n');Bundle status=new Bundle();status.putString("stream","PASS "+name+"\n");sendStatus(0,status);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle(); Activity activity=null;
        try {
            Context context=getTargetContext();
            activity=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync(); final Activity screen=activity;
            TimerCoordinator timer=TimerCoordinator.get(context); LedgerStore store=timer.store();
            check(store.getProjects().size()==6,"six default categories");
            check(store.getTasks(LocalDate.now()).size()==6,"six editable recurring default tasks");
            check(context.checkSelfPermission("android.permission.INTERNET")!=0,"no Internet permission");
            check((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)==0,"automatic cloud backup disabled");
            runOnMainSync(()->{check(find(screen.getWindow().getDecorView(),"专注账本")!=null,"native launch and Chinese brand");});

            LedgerStore.Task task=store.getTasks(LocalDate.now()).get(0);
            store.setTaskDone(task.id,LocalDate.now(),false);
            runOnMainSync(()->timer.start(task,15));
            SystemClock.sleep(1300);
            long first=timer.actualMs(); check(first>=1000&&first<10000,"real timer accrues only elapsed seconds");
            runOnMainSync(timer::pause); long paused=timer.actualMs(); SystemClock.sleep(1200);
            check(timer.actualMs()==paused,"pause does not accrue time");
            check(store.loadTimer().paused,"pause state durable in SQLite");
            boolean duplicate=false;try{timer.start(task,15);}catch(IllegalStateException expected){duplicate=true;}
            check(duplicate,"second active timer rejected");
            runOnMainSync(timer::resume); SystemClock.sleep(1100); runOnMainSync(timer::finish);
            List<Session> sessions=store.getSessions(); check(sessions.size()==1,"one early-ended session persisted");
            Session early=sessions.get(0); check(early.actualMs<15000 && early.actualMs>=2000,"early end saves actual only");
            check(!store.taskDone(task.id,LocalDate.now()),"timer ending does not mark task complete");
            store.persistTimer(new TimerEngine().snapshot(),early);
            check(store.getSessions().size()==1,"duplicate terminal ID does not double count");
            check(store.loadTimer()==null,"idle timer storage cleared");

            long yesterday=LocalDate.now().minusDays(1).atTime(8,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Session manual=Session.manual("manual-test","manual-task-test","=SUM(A1:A2)",task.projectId,yesterday,5*60000,ZoneId.systemDefault().getId(),false);
            store.addManualSession(manual);check(store.getSessions().size()==2,"manual historical session persisted");
            check(Reports.csv(store).contains("\"'=SUM(A1:A2)\""),"CSV formula injection protected");
            boolean overlap=false;try{store.addManualSession(Session.manual("overlap-test","manual-task-test","overlap",task.projectId,yesterday+60000,60000,manual.zoneId,false));}catch(IllegalArgumentException expected){overlap=true;}
            check(overlap,"overlapping manual record rejected");
            boolean future=false;try{store.addManualSession(Session.manual("future-test","manual-task-test","future",task.projectId,System.currentTimeMillis()+60000,60000,manual.zoneId,false));}catch(IllegalArgumentException expected){future=true;}
            check(future,"future manual record rejected");
            Session edited=Session.manual(manual.id,"manual-task-test","调整后记录",task.projectId,yesterday,4*60000,manual.zoneId,true);
            store.saveEditedSession(edited);check(store.getSessions().size()==2,"editing keeps record identity");
            check(store.getSessions().get(1).edited,"edited flag retained");
            String csv=Reports.csv(store);check(csv.contains("actual_ms") || csv.contains("actualMs") || csv.contains("actual"),"CSV has actual-duration field");
            check(Reports.dailyMarkdown(store,LocalDate.now().minusDays(1)).contains("调整后记录"),"daily report includes changed record");
            check(Reports.backupJson(store).contains("projects")&&Reports.backupJson(store).contains("sessions"),"JSON export includes local data");
            store.deleteSession(edited.id);check(store.getSessions().size()==1,"session deletion removes visible totals");
            store.persistTimer(new TimerEngine().snapshot(),edited);check(store.getSessions().size()==1,"deletion tombstone prevents resurrection");

            // Accelerated fixture: planned duration remains 15 minutes; prior 14m55s are explicitly injected only in this test APK.
            runOnMainSync(()->timer.start(task,15));
            TimerEngine.State state=timer.engine().snapshot(); long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
            long prior=895000;
            state.startedWallMs=wall-prior;
            state.accumulatedMs=prior;
            state.segments=new ArrayList<>();state.segments.add(new Segment(wall-prior,wall,prior));
            state.segmentStartWallMs=wall;state.segmentStartElapsedMs=elapsed;
            state.checkpointWallMs=wall;state.checkpointElapsedMs=elapsed;
            Field engine=TimerCoordinator.class.getDeclaredField("engine");engine.setAccessible(true);engine.set(timer,new TimerEngine(state));
            store.persistTimer(state,null);timer.schedule();
            runOnMainSync(()->check(screen.moveTaskToBack(true),"own app moved to background without injection permission"));
            SystemClock.sleep(6500);
            check(!timer.engine().isActive(),"background expiry ends timer without UI refresh");
            boolean completionNotification=false;
            for(android.service.notification.StatusBarNotification n:((android.app.NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).getActiveNotifications())if(n.getId()==2)completionNotification=true;
            check(completionNotification,"completion notification posted with granted permission");
            Session expired=store.getSession(state.sessionId);
            check("expired".equals(expired.reason)&&expired.actualMs==900000,"expiry caps at planned duration");
            check(store.getSessions().size()==2,"expiry creates exactly one terminal record");
            check(!store.taskDone(task.id,LocalDate.now()),"expiry keeps independent self-report unchecked");
            context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            waitForIdleSync();
            runOnMainSync(()->{check(clickDescription(screen.getWindow().getDecorView(),"统计，"),"statistics tab tap dispatched");});waitForIdleSync();
            runOnMainSync(()->{check(find(screen.getWindow().getDecorView(),"每一段投入，都有迹可循")!=null,"statistics navigation works");});
            runOnMainSync(()->{clickDescription(screen.getWindow().getDecorView(),"设置，");});waitForIdleSync();
            runOnMainSync(()->{check(find(screen.getWindow().getDecorView(),"按你的节奏来")!=null,"settings navigation works");});
            result.putString("stream",log.toString()+"\nOK ("+passed+" checks)\n");result.putInt("checks",passed);
            finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream",log.toString()+"FAIL "+error.toString()+"\n"+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
    }
    private View find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private boolean clickDescription(View view,String prefix){CharSequence description=view.getContentDescription();if(description!=null&&description.toString().startsWith(prefix)){view.performClick();return true;}if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)if(clickDescription(g.getChildAt(i),prefix))return true;}return false;}
}
