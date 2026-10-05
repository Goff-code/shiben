package cn.focusledger;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import cn.focusledger.core.Segment;
import cn.focusledger.core.Session;
import cn.focusledger.core.TimerEngine;
import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Arrays;
import java.util.function.Consumer;

/** Version 2.0.2 contracts against real Android SQLite, in test-only databases. */
final class AttentionV202Instrumentation {
    private final Context context;
    private final Consumer<String> result;
    private AttentionV202Instrumentation(Context context, Consumer<String> result) {
        this.context=context; this.result=result;
    }
    static void run(Context context, Consumer<String> result) throws Exception {
        AttentionV202Instrumentation suite=new AttentionV202Instrumentation(context,result);
        suite.gradePersistence();
        suite.attentionSources();
        suite.historicalNamesAndMidnight();
        suite.v2Migration();
    }
    private void check(boolean value,String label) {
        if(!value)throw new AssertionError(label);
        result.accept(label);
    }
    private void rejects(Runnable action,String label) {
        boolean rejected=false;
        try {action.run();} catch(IllegalArgumentException|IllegalStateException expected) {rejected=true;}
        check(rejected,label);
    }
    private Context isolated(String name) {return new IsolatedContext(context,name+"-"+System.nanoTime());}
    private void gradePersistence() {
        Context isolated=isolated("grades");
        LocalDate today=LocalDate.now(),yesterday=today.minusDays(1);
        String id;
        try(LedgerStore store=new LedgerStore(isolated)) {
            store.ensureToday();
            check("E".equals(store.getDaySnapshot(today).settlementGrade)&&!store.getDaySnapshot(today).genuineCompletion,
                    "empty native snapshot earns E and is not genuine completion");
            rejects(()->store.setSettlementGrade(today,"SSS+"),"empty day cannot receive a manual top grade");
            LedgerStore.Task task=store.saveSimpleTask("原始任务",100,1); id=task.id;
            check("S".equals(store.getDaySnapshot(today).settlementGrade)&&store.getDaySnapshot(today).genuineCompletion,
                    "genuinely completed task defaults to S");
            freezeCopy(store,today,yesterday);
            store.setSettlementGrade(yesterday,"SS");
            store.setSettlementGrade(today,"SSS+");
            check("SS".equals(store.getDaySnapshot(yesterday).settlementGrade)&&"SSS+".equals(store.getDaySnapshot(today).settlementGrade),
                    "manual settlement overrides are scoped independently to their actual dates");
            store.updateSimpleTask(id,"重新命名",1);
            check("SSS+".equals(store.getDaySnapshot(today).settlementGrade),"renaming a completed task preserves valid settlement choice");
        }
        try(LedgerStore store=new LedgerStore(isolated)) {
            check("SSS+".equals(store.getDaySnapshot(today).settlementGrade)&&"SS".equals(store.getDaySnapshot(yesterday).settlementGrade),
                    "per-day settlement overrides survive closing and reopening SQLite");
            store.setTaskProgress(id,97);
            check("A+".equals(store.getDaySnapshot(today).settlementGrade)&&!store.getDaySnapshot(today).genuineCompletion,
                    "reducing progress immediately invalidates an old top-grade override");
            rejects(()->store.setSettlementGrade(today,"SSS"),"incomplete day cannot set an elevated settlement grade");
            store.setTaskProgress(id,100);
            check("S".equals(store.getDaySnapshot(today).settlementGrade),"recompleting a reverted day does not resurrect its old override");
            check("SS".equals(store.getDaySnapshot(yesterday).settlementGrade),"today's grade invalidation does not mutate a historical override");
            store.setSettlementGrade(today,"SSS");
            LedgerStore.Task newTask=store.saveSimpleTask("新增未完成",0,2);
            check(!store.getDaySnapshot(today).genuineCompletion&&!"SSS".equals(store.getDaySnapshot(today).settlementGrade),
                    "adding an incomplete task invalidates a formerly completed day");
            store.setTaskProgress(newTask.id,100);
            check("S".equals(store.getDaySnapshot(today).settlementGrade),"completing the newly added task starts with S again");
            store.setSettlementGrade(today,"SSS+");
            store.setSettlementGrade(today,"S");
            check("S".equals(store.getDaySnapshot(today).settlementGrade),"choosing default S clears an elevated grade");
            rejects(()->store.setSettlementGrade(today,"A+"),"automatic letter grades cannot be forged as manual overrides");
            rejects(()->store.setSettlementGrade(today.plusDays(1),"SSS+"),"future dates cannot receive manual settlement overrides");
        }
    }
    private void attentionSources() {
        LocalDate today=LocalDate.now(),past=today.minusDays(2);
        ZoneId zone=ZoneId.systemDefault();
        try(LedgerStore store=new LedgerStore(isolated("attention"))) {
            LedgerStore.Task task=store.saveSimpleTask("独立进度",97,1);
            freezeCopy(store,today,past);
            long morning=past.atTime(8,0).atZone(zone).toInstant().toEpochMilli();
            Session manual=Session.manual("manual-time",task.id,task.title,task.projectId,morning,3600000,zone.getId(),false);
            store.addManualSession(manual);
            long start=morning+7200000,end=start+120000;
            Session completed=new Session("saved-timer",task.id,task.title,task.projectId,900000,120000,start,end,
                    Collections.singletonList(new Segment(start,end,120000)),"early_end",false,false,zone.getId());
            store.persistTimer(new TimerEngine.State(),completed);
            LedgerStore.DaySnapshot before=store.getDaySnapshot(past);
            check(before.totalActualMs==3720000&&before.totalManualMs==3600000,
                    "attention total sums manual and completed timer sources with separate manual subtotal");
            check(before.totalProgress==97&&"A+".equals(before.settlementGrade),
                    "hours of attention never change manual progress or settlement grade");
            long previewStart=end+3600000;
            Session projected=new Session("active-preview",task.id,task.title,task.projectId,900000,12345,
                    previewStart,previewStart+12345,Collections.singletonList(new Segment(previewStart,previewStart+12345,12345)),
                    "report_preview",false,false,zone.getId());
            LedgerStore.DaySnapshot visible=store.getDaySnapshot(past,projected);
            check(visible.totalActualMs==3732345&&visible.totalManualMs==3600000,
                    "one live projection contributes actual time without inflating manual subtotal");
            check(store.getSessions().size()==2&&store.getSession(projected.id)==null&&store.getDaySnapshot(past).totalActualMs==3720000,
                    "read-only live snapshot does not create or persist a fake terminal session");
            LedgerStore.DaySnapshot duplicate=store.getDaySnapshot(past,completed);
            check(duplicate.totalActualMs==3720000,"already-persisted session id is not double-counted as a live projection");
            store.deleteSession(manual.id);
            LedgerStore.DaySnapshot deleted=store.getDaySnapshot(past);
            check(deleted.totalActualMs==120000&&deleted.totalManualMs==0&&deleted.totalProgress==97&&"A+".equals(deleted.settlementGrade),
                    "deleting attention updates totals without touching progress or grade");
        }
    }
    private void historicalNamesAndMidnight() {
        LocalDate today=LocalDate.now(),past=today.minusDays(4),next=past.plusDays(1);
        ZoneId zone=ZoneId.systemDefault();
        try(LedgerStore store=new LedgerStore(isolated("history"))) {
            LedgerStore.Task task=store.saveSimpleTask("当时的任务名",40,1);
            freezeCopy(store,today,past);freezeCopy(store,today,next);
            store.updateSimpleTask(task.id,"现在的任务名",1);store.archiveTask(task.id);
            LedgerStore.Task historical=null;
            for(LedgerStore.Task item:store.getManualTasks(past))if(item.id.equals(task.id))historical=item;
            check(historical!=null&&"当时的任务名".equals(historical.title),
                    "manual task choices preserve frozen historical names after rename and archive");
            long start=past.atTime(23,45).atZone(zone).toInstant().toEpochMilli();
            Session cross=Session.manual("actual-past-midnight",task.id,historical.title,task.projectId,start,1800000,zone.getId(),false);
            store.addManualSession(cross);
            LedgerStore.DaySnapshot first=store.getDaySnapshot(past),second=store.getDaySnapshot(next);
            check(first.totalActualMs==900000&&second.totalActualMs==900000&&first.totalManualMs==900000&&second.totalManualMs==900000,
                    "actual-past manual interval crosses midnight and splits both totals by original zone");
            check(first.items.get(0).title.equals("当时的任务名")&&first.items.get(0).progress==40&&second.items.get(0).progress==40,
                    "historical manual entry changes neither frozen name nor past progress");
            rejects(()->store.addManualSession(Session.manual("overlap",task.id,task.title,task.projectId,start+60000,60000,zone.getId(),false)),
                    "manual overlapping interval is rejected atomically");
            rejects(()->store.addManualSession(Session.manual("future",task.id,task.title,task.projectId,System.currentTimeMillis()+60000,60000,zone.getId(),false)),
                    "manual future interval is rejected atomically");
            check(store.getSessions().size()==1,"rejected overlap and future writes create no stray sessions");
        }
    }
    private void v2Migration() {
        Context isolated=isolated("migration");LocalDate past=LocalDate.now().minusDays(10);
        long start=past.atTime(8,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Session saved=Session.manual("preserved-session","task","旧任务名称","project",start,120000,ZoneId.systemDefault().getId(),false);
        TimerEngine.State paused=new TimerEngine.State();paused.sessionId="preserved-paused";paused.taskId="task";
        paused.taskTitle="旧任务名称";paused.projectId="project";paused.plannedMs=900000;paused.accumulatedMs=45678;paused.paused=true;
        byte[] sessionBytes=LedgerStore.serialize(saved),timerBytes=LedgerStore.serialize(paused);
        try(SQLiteDatabase db=isolated.openOrCreateDatabase("focus-ledger.db",Context.MODE_PRIVATE,null)) {
            db.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,color INTEGER NOT NULL,sort INTEGER NOT NULL,archived INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,project_id TEXT NOT NULL REFERENCES projects(id),planned_minutes INTEGER NOT NULL CHECK(planned_minutes>=15 AND planned_minutes%5=0),sort INTEGER NOT NULL,week_mask INTEGER NOT NULL CHECK(week_mask BETWEEN 0 AND 127),one_off_date TEXT,archived INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE task_checks(task_id TEXT NOT NULL REFERENCES tasks(id),day TEXT NOT NULL,done INTEGER NOT NULL,task_title TEXT NOT NULL,project_id TEXT,PRIMARY KEY(task_id,day))");
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY NOT NULL,started_wall_ms INTEGER NOT NULL,ended_wall_ms INTEGER NOT NULL,project_id TEXT,task_id TEXT,project_name TEXT,record BLOB NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX sessions_started ON sessions(started_wall_ms DESC)");
            db.execSQL("CREATE TABLE timer_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),record BLOB NOT NULL)");
            db.execSQL("CREATE TABLE daily_snapshots(day TEXT PRIMARY KEY NOT NULL,complete INTEGER NOT NULL CHECK(complete IN(0,1)),legacy INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE daily_task_snapshots(day TEXT NOT NULL REFERENCES daily_snapshots(day),task_id TEXT NOT NULL,title TEXT NOT NULL,rank INTEGER CHECK(rank>=1),progress INTEGER CHECK(progress BETWEEN 0 AND 100),weight REAL CHECK(weight BETWEEN 0 AND 1),archived INTEGER NOT NULL DEFAULT 0,legacy_done INTEGER,PRIMARY KEY(day,task_id))");
            db.execSQL("CREATE UNIQUE INDEX daily_active_rank ON daily_task_snapshots(day,rank) WHERE archived=0");
            db.execSQL("INSERT INTO projects VALUES('project','旧项目',-1,0,0)");
            db.execSQL("INSERT INTO tasks VALUES('task','现任务名称','project',25,0,127,NULL,0)");
            db.execSQL("INSERT INTO daily_snapshots VALUES(?,1,0)",new Object[]{past.toString()});
            db.execSQL("INSERT INTO daily_task_snapshots VALUES(?,'task','旧任务名称',1,100,1,0,NULL)",new Object[]{past.toString()});
            ContentValues values=new ContentValues();values.put("id",saved.id);values.put("started_wall_ms",start);
            values.put("ended_wall_ms",saved.endedWallMs);values.put("project_id","project");values.put("task_id","task");
            values.put("project_name","旧项目");values.put("record",sessionBytes);values.put("deleted",0);db.insertOrThrow("sessions",null,values);
            values.put("id","preserved-tombstone");values.put("deleted",1);db.insertOrThrow("sessions",null,values);
            ContentValues state=new ContentValues();state.put("singleton",1);state.put("record",timerBytes);db.insertOrThrow("timer_state",null,state);
            db.setVersion(2);
        }
        try(LedgerStore store=new LedgerStore(isolated)) {
            SQLiteDatabase db=store.getReadableDatabase();
            check(db.getVersion()==3,"real Android SQLite applies v2 to v3 schema upgrade");
            try(Cursor rows=db.rawQuery("SELECT record,deleted FROM sessions ORDER BY id",null)) {
                check(rows.getCount()==2,"v2 upgrade retains live session and deletion tombstone rows");
                rows.moveToFirst();check(Arrays.equals(rows.getBlob(0),sessionBytes)&&rows.getInt(1)==0,
                        "v2 upgrade preserves completed-session serialized bytes exactly");
                rows.moveToNext();check(Arrays.equals(rows.getBlob(0),sessionBytes)&&rows.getInt(1)==1,
                        "v2 upgrade preserves deleted-session serialized bytes and tombstone exactly");
            }
            try(Cursor rows=db.rawQuery("SELECT record FROM timer_state WHERE singleton=1",null)) {
                check(rows.moveToFirst()&&Arrays.equals(rows.getBlob(0),timerBytes),"v2 upgrade preserves paused-timer serialized bytes exactly");
            }
            TimerEngine.State restored=store.loadTimer();
            check(restored.paused&&restored.sessionId.equals(paused.sessionId)&&restored.accumulatedMs==45678,
                    "v2 paused timer still deserializes with same identity and actual time");
            LedgerStore.DaySnapshot history=store.getDaySnapshot(past);
            check(history.completeSnapshot&&history.items.size()==1&&history.items.get(0).title.equals("旧任务名称")
                            &&history.items.get(0).rank==1&&history.items.get(0).progress==100&&history.items.get(0).weight==1,
                    "v2 upgrade preserves all frozen historical task metadata");
            check(history.totalActualMs==120000&&history.totalManualMs==120000&&"S".equals(history.settlementGrade),
                    "v2 history derives new grade and manual subtotal without rewriting its evidence");
            try(Cursor rows=db.rawQuery("SELECT settlement_grade FROM daily_snapshots WHERE day=?",new String[]{past.toString()})) {
                check(rows.moveToFirst()&&rows.isNull(0),"v2 migration adds an empty override rather than inventing user grade choices");
            }
        }
    }
    static void freezeCopy(LedgerStore store,LocalDate from,LocalDate to) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            db.execSQL("INSERT INTO daily_snapshots(day,complete,legacy) VALUES (?,1,0)",new Object[]{to.toString()});
            db.execSQL("INSERT INTO daily_task_snapshots(day,task_id,title,rank,progress,weight,archived,legacy_done) "
                    +"SELECT ?,task_id,title,rank,progress,weight,archived,legacy_done FROM daily_task_snapshots WHERE day=?",
                    new Object[]{to.toString(),from.toString()});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    private static final class IsolatedContext extends ContextWrapper {
        private final File root;private final String key;
        IsolatedContext(Context base,String key) {super(base);this.key=key;root=new File(base.getCacheDir(),"attention-v202-"+key);root.mkdirs();}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getDatabasePath(String name){return new File(root,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        @Override public File getFilesDir(){File directory=new File(root,"files");directory.mkdirs();return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(key+"-"+name,mode);}
    }
}
