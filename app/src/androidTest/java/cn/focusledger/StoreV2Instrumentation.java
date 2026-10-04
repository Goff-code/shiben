package cn.focusledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import cn.focusledger.core.Session;
import cn.focusledger.core.TimerEngine;
import java.io.File;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/** Real SQLite migration and snapshot checks; runs only in the test APK, with isolated databases. */
public final class StoreV2Instrumentation extends Instrumentation {
    private int checks;
    private final StringBuilder output = new StringBuilder();
    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++; output.append("PASS ").append(name).append('\n');
        Bundle progress = new Bundle(); progress.putString("stream", "PASS " + name + "\n"); sendStatus(0, progress);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            freshStore(); migration();
            result.putString("stream", output + "OK (" + checks + " checks)\n"); result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", output + "FAIL " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void freshStore() throws Exception {
        Context context = new IsolatedContext(getTargetContext(), "fresh-" + System.nanoTime());
        LocalDate today = LocalDate.now(), yesterday = today.minusDays(1);
        try (LedgerStore store = new LedgerStore(context)) {
            store.ensureToday();
            check(store.getTasks(today).isEmpty(), "fresh install contains no fabricated demo tasks");
            check(store.getDaySnapshot(today).totalProgress == 0, "empty day starts at zero");
            LedgerStore.Task a = store.saveSimpleTask("A", 100, 1);
            LedgerStore.Task b = store.saveSimpleTask("B", 40, 2);
            LedgerStore.Task c = store.saveSimpleTask("C", 20, 3);
            check(store.getDaySnapshot(today).totalProgress == 65, "manual 100/40/20 yields rule-B 65 percent");
            store.reorderTasks(Arrays.asList(c.id, a.id, b.id));
            check(store.getDaySnapshot(today).totalProgress == 45, "reorder recalculates current weights");
            List<LedgerStore.Task> ordered = store.getTasks(today);
            check(ordered.get(0).sort == 0 && ordered.get(1).sort == 1 && ordered.get(2).sort == 2,
                    "priorities are unique and contiguous");
            boolean rejected = false;
            try { store.reorderTasks(Arrays.asList(a.id, a.id, b.id)); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && store.getTasks(today).get(0).id.equals(c.id), "invalid reorder is atomic and leaves prior order");
            rejected = false;
            try { store.setTaskProgress(a.id, 101); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && find(store.getDaySnapshot(today), a.id).progress == 100, "out-of-range progress rejected without mutation");

            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("INSERT INTO daily_snapshots(day,complete,legacy) VALUES (?,1,0)", new Object[]{yesterday.toString()});
            db.execSQL("INSERT INTO daily_task_snapshots(day,task_id,title,rank,progress,weight,archived,legacy_done) "
                    + "SELECT ?,task_id,title,rank,progress,weight,archived,legacy_done FROM daily_task_snapshots WHERE day=?",
                    new Object[]{yesterday.toString(), today.toString()});
            LedgerStore.DaySnapshot before = store.getDaySnapshot(yesterday);
            store.updateSimpleTask(a.id, "A renamed", 1);
            store.archiveTask(c.id);
            store.setTaskProgress(b.id, 60);
            LedgerStore.DaySnapshot after = store.getDaySnapshot(yesterday);
            check(sameMetadata(before, after), "rename/reorder/progress/archive preserve all prior-day metadata");
            LedgerStore.DaySnapshot current = store.getDaySnapshot(today);
            check(current.items.size() == 3 && find(current, c.id).archived && find(current, c.id).progress == 20,
                    "archived-today task retains name and manually entered progress");
            check(current.totalProgress == 80 && find(current,b.id).rank == 2 && find(current,b.id).weight == .5,
                    "archived task excluded from active denominator while ranks remain contiguous");

            long start = System.currentTimeMillis() - 10000;
            Session session = Session.manual("completion", a.id, "A renamed", a.projectId, start, 1000,
                    ZoneId.systemDefault().getId(), false);
            store.persistTimer(new TimerEngine.State(), session);
            store.persistTimer(new TimerEngine.State(), session);
            check(store.getSessions().size() == 1 && find(store.getDaySnapshot(today),a.id).actualMs == 1000,
                    "completion retry is exactly once and focus derives from saved session");
            check(find(store.getDaySnapshot(today),a.id).progress == 100 && find(store.getDaySnapshot(today),b.id).progress == 60,
                    "timer completion never changes progress");
            store.deleteSession(session.id);
            store.persistTimer(new TimerEngine.State(), session);
            check(store.getSessions().isEmpty() && find(store.getDaySnapshot(today),a.id).actualMs == 0,
                    "tombstone prevents retry resurrection and focus immediately reflects deletion");

            LocalDate firstDay = today.minusDays(2);
            String originalZone = "Pacific/Auckland";
            long midnight = firstDay.atTime(23,59,30).atZone(ZoneId.of(originalZone)).toInstant().toEpochMilli();
            Session cross = Session.manual("cross", a.id, "Name at recording", a.projectId, midnight, 60000, originalZone, false);
            store.addManualSession(cross);
            check(store.getDaySnapshot(firstDay).totalActualMs == 30000 && store.getDaySnapshot(yesterday).totalActualMs == 30000,
                    "cross-midnight focus splits using session original zone");
            check(store.getRecordedDays().contains(firstDay) && store.getRecordedDays().contains(yesterday),
                    "all session dates appear in history including days without earlier snapshots");
            check(!store.getDaySnapshot(firstDay).completeSnapshot && find(store.getDaySnapshot(firstDay),a.id).progress == -1,
                    "session-only historical day keeps unknown metadata unknown");
            store.saveEditedSession(Session.manual(cross.id,a.id,"Correction title",a.projectId,midnight,30000,originalZone,true));
            check(store.getDaySnapshot(firstDay).totalActualMs == 30000 && store.getDaySnapshot(yesterday).totalActualMs == 0,
                    "editing sessions updates actuals without stale snapshot focus caches");
            check(sameMetadata(before, store.getDaySnapshot(yesterday)), "session edits do not relabel an existing daily snapshot");

            // Exercise exact production rollover logic for a deterministic next date, without changing device time.
            Method ensure = LedgerStore.class.getDeclaredMethod("ensureTodayInTransaction", SQLiteDatabase.class, LocalDate.class);
            ensure.setAccessible(true);
            db.beginTransaction();
            try { ensure.invoke(null, db, today.plusDays(1)); db.setTransactionSuccessful(); } finally { db.endTransaction(); }
            LedgerStore.DaySnapshot next = store.getDaySnapshot(today.plusDays(1));
            check(next.items.size() == 2 && next.totalProgress == 0 && find(next,a.id).progress == 0 && find(next,b.id).progress == 0,
                    "next-day task list carries with fresh zero progress and no archived tasks");
            check(store.getDaySnapshot(today).totalProgress == 80, "opening another day never resets the previous day");
            boolean rollbackBlocked = false;
            try { store.setTaskProgress(a.id, 10); } catch (IllegalStateException expected) { rollbackBlocked = true; }
            check(rollbackBlocked && store.getDaySnapshot(today).totalProgress == 80,
                    "device-date rollback cannot reopen previous-day progress for editing");
            Session rollbackSession = Session.manual("clock-rollback", a.id, "A renamed", a.projectId,
                    System.currentTimeMillis()-5000,1000,ZoneId.systemDefault().getId(),false);
            store.persistTimer(new TimerEngine.State(),rollbackSession);
            check(store.getSession("clock-rollback") != null && store.getDaySnapshot(today).totalProgress == 80,
                    "timer persistence still works during date rollback without mutating frozen progress");
            check(store.getTasks(yesterday).get(0).title.equals("C"), "historical getTasks reads snapshot names and order");
        }
    }

    private void migration() {
        Context context = new IsolatedContext(getTargetContext(), "legacy-" + System.nanoTime());
        LocalDate oldDay = LocalDate.now().minusDays(1000);
        Session original = Session.manual("old-session", "old-task", "Session-era name", "old-project",
                oldDay.atTime(23,59,30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),60000,"UTC",false);
        TimerEngine.State timer = new TimerEngine.State(); timer.sessionId = "old-active"; timer.taskId = "old-task";
        byte[] sessionBlob = LedgerStore.serialize(original), timerBlob = LedgerStore.serialize(timer);
        try (SQLiteDatabase db = context.openOrCreateDatabase("focus-ledger.db", Context.MODE_PRIVATE, null)) {
            db.execSQL("CREATE TABLE projects (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,color INTEGER NOT NULL,sort INTEGER NOT NULL,archived INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,project_id TEXT NOT NULL REFERENCES projects(id),planned_minutes INTEGER NOT NULL CHECK(planned_minutes>=15 AND planned_minutes%5=0),sort INTEGER NOT NULL,week_mask INTEGER NOT NULL,one_off_date TEXT,archived INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE task_checks (task_id TEXT NOT NULL REFERENCES tasks(id),day TEXT NOT NULL,done INTEGER NOT NULL,task_title TEXT NOT NULL,project_id TEXT,PRIMARY KEY(task_id,day))");
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY NOT NULL,started_wall_ms INTEGER NOT NULL,ended_wall_ms INTEGER NOT NULL,project_id TEXT,task_id TEXT,project_name TEXT,record BLOB NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX sessions_started ON sessions(started_wall_ms DESC)");
            db.execSQL("CREATE TABLE timer_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1),record BLOB NOT NULL)");
            db.execSQL("INSERT INTO projects VALUES ('old-project','Old category',0,8,0)");
            db.execSQL("INSERT INTO tasks VALUES ('old-task','Current legacy name','old-project',25,19,1,NULL,0)");
            db.execSQL("INSERT INTO task_checks VALUES ('old-task',?,1,'Checklist-era name','old-project')",new Object[]{oldDay.toString()});
            db.execSQL("INSERT INTO sessions VALUES ('old-session',?,?,'old-project','old-task','Old category',?,0)",
                    new Object[]{original.startedWallMs,original.endedWallMs,sessionBlob});
            db.execSQL("INSERT INTO timer_state VALUES (1,?)",new Object[]{timerBlob});
            db.setVersion(1);
        }
        try (LedgerStore store = new LedgerStore(context)) {
            LedgerStore.DaySnapshot old = store.getDaySnapshot(oldDay);
            LedgerStore.DayTask row = find(old,"old-task");
            check(store.getReadableDatabase().getVersion() == 2, "real SQLiteOpenHelper upgrades V1 to V2");
            check(!old.completeSnapshot && Double.isNaN(old.totalProgress), "legacy daily completeness and weighted total remain unknown");
            check(row.title.equals("Checklist-era name") && row.progress == -1 && row.rank == -1 && Double.isNaN(row.weight)
                    && Boolean.TRUE.equals(row.legacyDone), "legacy checklist name and binary check survive without inventing percentage or rank");
            check(old.totalActualMs == 30000 && store.getDaySnapshot(oldDay.plusDays(1)).totalActualMs == 30000,
                    "migration maps serialized historical sessions across midnight");
            check(store.getRecordedDays().contains(oldDay) && store.getRecordedDays().contains(oldDay.plusDays(1)),
                    "old history dates stay available with no expiry");
            check(store.loadTimer().sessionId.equals("old-active") && Arrays.equals(timerBlob, LedgerStore.serialize(store.loadTimer())),
                    "in-flight serialized timer survives schema migration unchanged");
            check(Arrays.equals(sessionBlob, LedgerStore.serialize(store.getSession("old-session"))),
                    "saved session serialization survives migration unchanged");
            store.ensureToday();
            check(store.getTasks(LocalDate.now()).size()==1 && store.getTasks(LocalDate.now()).get(0).sort==0,
                    "legacy active task carries into single-level list with normalized current priority");
            check(sameMetadata(old,store.getDaySnapshot(oldDay)), "opening today never fills unknown historical priorities");
        }
    }

    private static LedgerStore.DayTask find(LedgerStore.DaySnapshot snapshot, String id) {
        for (LedgerStore.DayTask item : snapshot.items) if (item.taskId.equals(id)) return item;
        throw new AssertionError("Missing task " + id);
    }
    private static boolean sameMetadata(LedgerStore.DaySnapshot a, LedgerStore.DaySnapshot b) {
        if (a.items.size()!=b.items.size() || a.completeSnapshot!=b.completeSnapshot) return false;
        for (LedgerStore.DayTask x : a.items) {
            LedgerStore.DayTask y = find(b,x.taskId);
            if (!x.title.equals(y.title) || x.rank!=y.rank || x.progress!=y.progress || x.archived!=y.archived
                    || Double.compare(x.weight,y.weight)!=0) return false;
        }
        return true;
    }
    private static final class IsolatedContext extends ContextWrapper {
        private final File root;
        private final String key;
        IsolatedContext(Context base, String key) {
            super(base); this.key=key; root=new File(base.getCacheDir(),"store-v2-test-"+key); root.mkdirs();
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getDatabasePath(String name) { return new File(root,name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);
        }
        @Override public File getFilesDir() { File dir=new File(root,"files");dir.mkdirs();return dir; }
        @Override public SharedPreferences getSharedPreferences(String name,int mode) { return super.getSharedPreferences(key+"-"+name,mode); }
    }
}
