package cn.focusledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import cn.focusledger.core.DailyTotals;
import cn.focusledger.core.PriorityProgress;
import cn.focusledger.core.Segment;
import cn.focusledger.core.Session;
import cn.focusledger.core.TimerEngine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** All app data stays in this app-private SQLite database. */
public final class LedgerStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "focus-ledger.db";
    private static final int DATABASE_VERSION = 2;
    private final Context appContext;

    public static final class Project {
        public String id;
        public String name;
        public int color;
        public int sort;
        public boolean archived;

        public Project() { this(UUID.randomUUID().toString(), "", 0xff4f46e5, -1, false); }
        public Project(String id, String name, int color, int sort) {
            this(id, name, color, sort, false);
        }
        public Project(String id, String name, int color, int sort, boolean archived) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.sort = sort;
            this.archived = archived;
        }
    }

    public static final class Task {
        public String id;
        public String title;
        public String projectId;
        public int plannedMinutes;
        public int sort;
        /** Monday = 1 through Sunday = 64. 127 means every day. */
        public int weekMask;
        /** ISO local date, or null for a recurring task. */
        public String oneOffDate;
        public boolean archived;

        public Task() { this(UUID.randomUUID().toString(), "", null, 25, -1, 127, null, false); }
        public Task(String id, String title, String projectId, int plannedMinutes, int sort) {
            this(id, title, projectId, plannedMinutes, sort, 127, null, false);
        }
        public Task(String id, String title, String projectId, int plannedMinutes, int sort,
                    int weekMask, String oneOffDate, boolean archived) {
            this.id = id;
            this.title = title;
            this.projectId = projectId;
            this.plannedMinutes = plannedMinutes;
            this.sort = sort;
            this.weekMask = weekMask;
            this.oneOffDate = oneOffDate;
            this.archived = archived;
        }

        public boolean scheduledOn(LocalDate day) {
            if (day == null) return false;
            if (oneOffDate != null) return oneOffDate.equals(day.toString());
            return (weekMask & (1 << (day.getDayOfWeek().getValue() - 1))) != 0;
        }
    }

    /** Snapshot so a completed checklist entry survives later rename or archive. */
    public static final class TaskCheck {
        public final String taskId;
        public final LocalDate day;
        public final boolean done;
        public final String taskTitle;
        public final String projectId;

        TaskCheck(String taskId, LocalDate day, boolean done, String taskTitle, String projectId) {
            this.taskId = taskId;
            this.day = day;
            this.done = done;
            this.taskTitle = taskTitle;
            this.projectId = projectId;
        }
    }

    /** Frozen daily metadata plus focus durations derived from the durable session ledger. */
    public static final class DayTask {
        public final String taskId, title;
        /** One-based priority; -1 means not recorded by V1. */
        public final int rank, progress;
        /** NaN means unknown, not zero. */
        public final double weight;
        public final long actualMs;
        /** Archived during this day; retained as evidence, excluded from this day's active total. */
        public final boolean archived;
        public final Boolean legacyDone;

        public DayTask(String taskId, String title, int rank, int progress, double weight,
                       long actualMs, boolean archived, Boolean legacyDone) {
            this.taskId = taskId; this.title = title; this.rank = rank; this.progress = progress;
            this.weight = weight; this.actualMs = actualMs; this.archived = archived;
            this.legacyDone = legacyDone;
        }
    }

    public static final class DaySnapshot {
        public final LocalDate day;
        public final List<DayTask> items;
        /** NaN when the daily task inventory or any active progress is unknown. */
        public final double totalProgress;
        public final boolean completeSnapshot;
        public final long totalActualMs;

        public DaySnapshot(LocalDate day, List<DayTask> items, double totalProgress,
                           boolean completeSnapshot, long totalActualMs) {
            this.day = day; this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.totalProgress = totalProgress; this.completeSnapshot = completeSnapshot;
            this.totalActualMs = totalActualMs;
        }
    }

    public LedgerStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        appContext = context.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projects (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, "
                + "color INTEGER NOT NULL, sort INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, "
                + "project_id TEXT NOT NULL REFERENCES projects(id), planned_minutes INTEGER NOT NULL "
                + "CHECK(planned_minutes >= 15 AND planned_minutes % 5 = 0), sort INTEGER NOT NULL, "
                + "week_mask INTEGER NOT NULL CHECK(week_mask BETWEEN 0 AND 127), one_off_date TEXT, "
                + "archived INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE task_checks (task_id TEXT NOT NULL REFERENCES tasks(id), "
                + "day TEXT NOT NULL, done INTEGER NOT NULL, task_title TEXT NOT NULL, project_id TEXT, "
                + "PRIMARY KEY(task_id, day))");
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY NOT NULL, started_wall_ms INTEGER NOT NULL, "
                + "ended_wall_ms INTEGER NOT NULL, project_id TEXT, task_id TEXT, project_name TEXT, "
                + "record BLOB NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX sessions_started ON sessions(started_wall_ms DESC)");
        db.execSQL("CREATE TABLE timer_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1), "
                + "record BLOB NOT NULL)");
        createV2Schema(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            createV2Schema(db);
            // Checklists are evidence of names/binary check marks, never evidence of percentages,
            // old priority ranks, the full task inventory, or V2 weights.
            db.execSQL("INSERT OR IGNORE INTO daily_snapshots(day,complete,legacy) "
                    + "SELECT DISTINCT day,0,1 FROM task_checks");
            db.execSQL("INSERT OR IGNORE INTO daily_task_snapshots "
                    + "(day,task_id,title,rank,progress,weight,archived,legacy_done) "
                    + "SELECT day,task_id,task_title,NULL,NULL,NULL,0,done FROM task_checks");
            // Session blobs are deliberately untouched. Read using their unchanged serialVersionUID.
            try (Cursor c = db.query("sessions", new String[]{"record"}, "deleted=0", null,
                    null, null, "started_wall_ms,id")) {
                while (c.moveToNext()) recordSessionEvidence(db, deserialize(c.getBlob(0), Session.class));
            }
            return;
        }
        // Never drop personal history to handle an unknown schema revision.
        throw new IllegalStateException("Unsupported database upgrade: " + oldVersion + " -> " + newVersion);
    }

    private static void createV2Schema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily_snapshots (day TEXT PRIMARY KEY NOT NULL, "
                + "complete INTEGER NOT NULL CHECK(complete IN (0,1)), legacy INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE daily_task_snapshots (day TEXT NOT NULL REFERENCES daily_snapshots(day), "
                + "task_id TEXT NOT NULL, title TEXT NOT NULL, rank INTEGER CHECK(rank >= 1), "
                + "progress INTEGER CHECK(progress BETWEEN 0 AND 100), "
                + "weight REAL CHECK(weight BETWEEN 0 AND 1), archived INTEGER NOT NULL DEFAULT 0, "
                + "legacy_done INTEGER, PRIMARY KEY(day,task_id))");
        db.execSQL("CREATE UNIQUE INDEX daily_active_rank ON daily_task_snapshots(day,rank) WHERE archived=0");
    }

    public synchronized List<Project> getProjects() {
        return readProjects("archived=0");
    }

    /** Includes archived categories, for exports and historical lookups. */
    public synchronized List<Project> getAllProjects() {
        return readProjects(null);
    }

    private List<Project> readProjects(String selection) {
        List<Project> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("projects", null, selection, null, null, null, "sort,id")) {
            while (c.moveToNext()) result.add(projectFrom(c));
        }
        return result;
    }

    /** Archived projects remain available so old records never lose their label. */
    public synchronized Project getProject(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().query("projects", null, "id=?", new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? projectFrom(c) : null;
        }
    }

    public synchronized Project addProject(String name, int color) {
        Project p = new Project(UUID.randomUUID().toString(), requireText(name, "分类名称"), color,
                nextSort("projects"), false);
        getWritableDatabase().insertOrThrow("projects", null, projectValues(p));
        return p;
    }

    public synchronized void updateProject(Project project) {
        if (project == null) throw new IllegalArgumentException("分类不能为空");
        project.id = requireText(project.id, "分类编号");
        project.name = requireText(project.name, "分类名称");
        if (getProject(project.id) == null) throw new IllegalArgumentException("找不到这个分类");
        SQLiteDatabase db = getWritableDatabase();
        LocalDate today = LocalDate.now();
        db.beginTransaction();
        try {
            requireEditableDay(db, today);
            ensureTodayInTransaction(db, today);
            db.update("projects", projectValues(project), "id=?", new String[]{project.id});
            writeOrder(db, activeTasks(db));
            refreshTodaySnapshot(db, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    /** Archive, never erase category or task history. */
    public synchronized void archiveProject(String id) {
        LocalDate today = LocalDate.now();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireEditableDay(db, today);
            ensureTodayInTransaction(db, today);
            ContentValues values = new ContentValues();
            values.put("archived", 1);
            db.update("projects", values, "id=?", new String[]{requireText(id, "分类编号")});
            db.update("tasks", values, "project_id=?", new String[]{id});
            writeOrder(db, activeTasks(db));
            refreshTodaySnapshot(db, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    /** Today's active single-level list; older dates use only recorded snapshot rows. */
    public synchronized List<Task> getTasks(LocalDate day) {
        if (day == null) throw new IllegalArgumentException("日期不能为空");
        if (day.equals(LocalDate.now()) && !isBeforeLatestDay(getReadableDatabase(), day))
            return activeTasks(getReadableDatabase());
        List<Task> result = new ArrayList<>();
        for (DayTask item : getDaySnapshot(day).items) {
            Task current = getTask(item.taskId);
            // plannedMinutes is a compatibility timer setting, not a claimed historical planned value.
            result.add(new Task(item.taskId, item.title, current == null ? null : current.projectId,
                    current == null ? 25 : current.plannedMinutes, item.rank < 1 ? -1 : item.rank - 1,
                    127, null, item.archived));
        }
        return result;
    }

    public synchronized List<Task> getAllTasks() {
        List<Task> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("tasks", null, null, null, null, null, "sort,id")) {
            while (c.moveToNext()) result.add(taskFrom(c));
        }
        return result;
    }

    public synchronized Task getTask(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().query("tasks", null, "id=?", new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? taskFrom(c) : null;
        }
    }

    public synchronized void saveTask(Task task) {
        LocalDate today = LocalDate.now();
        if (task == null) throw new IllegalArgumentException("任务不能为空");
        if (task.id == null || task.id.trim().isEmpty()) task.id = UUID.randomUUID().toString();
        task.title = requireText(task.title, "任务名称");
        task.projectId = requireText(task.projectId, "分类编号");
        Project project = getProject(task.projectId);
        if (project == null || (project.archived && !task.archived))
            throw new IllegalArgumentException("请先选择可用分类");
        if (task.plannedMinutes < 15 || task.plannedMinutes % 5 != 0)
            throw new IllegalArgumentException("计划时长至少 15 分钟，并以 5 分钟递增");
        if (task.weekMask < 0 || task.weekMask > 127) throw new IllegalArgumentException("重复日期无效");
        if (task.oneOffDate != null) {
            task.oneOffDate = task.oneOffDate.trim();
            if (!task.oneOffDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException("日期请使用 YYYY-MM-DD");
            try { LocalDate.parse(task.oneOffDate); }
            catch (RuntimeException e) { throw new IllegalArgumentException("日期无效", e); }
            task.weekMask = 0;
        }
        if (task.sort < 0) task.sort = nextSort("tasks");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireEditableDay(db, today);
            ensureTodayInTransaction(db, today);
            // UPDATE avoids REPLACE's delete/insert behavior and retains checklist references.
            if (db.update("tasks", taskValues(task), "id=?", new String[]{task.id}) == 0)
                db.insertOrThrow("tasks", null, taskValues(task));
            writeOrder(db, activeTasks(db));
            refreshTodaySnapshot(db, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    public synchronized void deleteTask(String id) { archiveTask(id); }

    public synchronized void moveTask(String id, int delta) {
        LocalDate today = LocalDate.now();
        if (delta == 0) return;
        List<String> ids = new ArrayList<>();
        for (Task task : getTasks(today)) ids.add(task.id);
        int from = ids.indexOf(id);
        if (from < 0) return;
        int to = (int) Math.max(0L, Math.min(ids.size() - 1L, (long) from + delta));
        ids.remove(from); ids.add(to, id);
        reorderTasks(ids);
    }

    public synchronized boolean taskDone(String taskId, LocalDate day) {
        if (taskId == null || day == null) return false;
        if (day.equals(LocalDate.now())) {
            ensureToday();
            try (Cursor c = getReadableDatabase().query("daily_task_snapshots", new String[]{"progress"},
                    "task_id=? AND day=?", new String[]{taskId, day.toString()}, null, null, null)) {
                return c.moveToFirst() && !c.isNull(0) && c.getInt(0) == 100;
            }
        }
        try (Cursor c = getReadableDatabase().query("task_checks", new String[]{"done"},
                "task_id=? AND day=?", new String[]{taskId, day.toString()}, null, null, null)) {
            return c.moveToFirst() && c.getInt(0) != 0;
        }
    }

    /** Explicit self-report. Completing a timer never sets this flag. */
    public synchronized void setTaskDone(String taskId, LocalDate day, boolean done) {
        LocalDate today = LocalDate.now();
        if (day == null) throw new IllegalArgumentException("日期不能为空");
        if (!day.equals(today)) throw new IllegalArgumentException("历史进度为只读快照");
        setTaskProgress(taskId, done ? 100 : 0);
        Task task = getTask(taskId);
        if (task == null) throw new IllegalArgumentException("找不到这个任务");
        ContentValues values = new ContentValues();
        values.put("task_id", task.id);
        values.put("day", day.toString());
        values.put("done", done ? 1 : 0);
        values.put("task_title", task.title);
        values.put("project_id", task.projectId);
        getWritableDatabase().insertWithOnConflict("task_checks", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<TaskCheck> getTaskChecks(LocalDate day) {
        return readTaskChecks(day == null ? null : "day=?", day == null ? null : new String[]{day.toString()});
    }

    public synchronized List<TaskCheck> getAllTaskChecks() { return readTaskChecks(null, null); }

    private List<TaskCheck> readTaskChecks(String selection, String[] args) {
        List<TaskCheck> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("task_checks", null, selection, args, null, null, "day,task_id")) {
            while (c.moveToNext()) result.add(new TaskCheck(string(c, "task_id"),
                    LocalDate.parse(string(c, "day")), integer(c, "done") != 0,
                    string(c, "task_title"), string(c, "project_id")));
        }
        return result;
    }

    /** Opens the current day with fresh progress, without rewriting any preceding day. */
    public synchronized void ensureToday() {
        LocalDate today = LocalDate.now();
        SQLiteDatabase db = getWritableDatabase();
        boolean changed;
        db.beginTransaction();
        try {
            changed = ensureTodayInTransaction(db, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (changed) notifyArchive();
    }

    public synchronized Task saveSimpleTask(String name, int initialProgress, int rank) {
        LocalDate today = LocalDate.now();
        name = requireText(name, "任务名称");
        PriorityProgress.requireProgress(initialProgress);
        SQLiteDatabase db = getWritableDatabase();
        Task task;
        db.beginTransaction();
        try {
            requireEditableDay(db, today);
            ensureTodayInTransaction(db, today);
            List<Task> active = activeTasks(db);
            requireRank(rank, active.size() + 1);
            ensureInternalProject(db);
            task = new Task(UUID.randomUUID().toString(), name, "v2-default", 25, rank - 1);
            db.insertOrThrow("tasks", null, taskValues(task));
            active.add(rank - 1, task);
            writeOrder(db, active);
            refreshTodaySnapshot(db, today);
            writeProgress(db, task.id, initialProgress, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
        return task;
    }

    public synchronized void updateSimpleTask(String id, String name, int rank) {
        LocalDate today = LocalDate.now();
        name = requireText(name, "任务名称");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireEditableDay(db, today);
            ensureTodayInTransaction(db, today);
            List<Task> active = activeTasks(db);
            requireRank(rank, active.size());
            Task found = null;
            for (Task task : active) if (task.id.equals(id)) { found = task; break; }
            if (found == null) throw new IllegalArgumentException("找不到这个任务");
            found.title = name;
            active.remove(found); active.add(rank - 1, found);
            ContentValues values = new ContentValues(); values.put("title", name);
            db.update("tasks", values, "id=?", new String[]{id});
            writeOrder(db, active);
            refreshTodaySnapshot(db, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    /** Manual input is the only path that changes a task's percentage. */
    public synchronized void setTaskProgress(String id, int progress) {
        LocalDate today = LocalDate.now();
        PriorityProgress.requireProgress(progress);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireEditableDay(db, today);
            ensureTodayInTransaction(db, today);
            boolean exists = false;
            for (Task task : activeTasks(db)) if (task.id.equals(id)) { exists = true; break; }
            if (!exists) throw new IllegalArgumentException("找不到可用的任务");
            writeProgress(db, id, progress, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    public synchronized void reorderTasks(List<String> orderedIds) {
        LocalDate today = LocalDate.now();
        if (orderedIds == null) throw new IllegalArgumentException("任务顺序不能为空");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireEditableDay(db, today);
            ensureTodayInTransaction(db, today);
            List<Task> active = activeTasks(db), ordered = new ArrayList<>();
            Map<String, Task> byId = new HashMap<>();
            for (Task task : active) byId.put(task.id, task);
            if (orderedIds.size() != active.size()) throw new IllegalArgumentException("任务顺序必须包含全部可用任务");
            for (String id : orderedIds) {
                Task task = byId.remove(id);
                if (task == null) throw new IllegalArgumentException("任务顺序重复或无效");
                ordered.add(task);
            }
            writeOrder(db, ordered);
            refreshTodaySnapshot(db, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    /** Today's archived work remains visible in history, outside the active weighted total. */
    public synchronized void archiveTask(String id) {
        LocalDate today = LocalDate.now();
        id = requireText(id, "任务编号");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireEditableDay(db, today);
            ensureTodayInTransaction(db, today);
            ContentValues values = new ContentValues(); values.put("archived", 1);
            if (db.update("tasks", values, "id=?", new String[]{id}) == 0)
                throw new IllegalArgumentException("找不到这个任务");
            writeOrder(db, activeTasks(db));
            refreshTodaySnapshot(db, today);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    public synchronized DaySnapshot getDaySnapshot(LocalDate day) {
        if (day == null) throw new IllegalArgumentException("日期不能为空");
        if (day.equals(LocalDate.now())) ensureToday();
        SQLiteDatabase db = getReadableDatabase();
        boolean complete = false;
        try (Cursor c = db.query("daily_snapshots", new String[]{"complete"}, "day=?",
                new String[]{day.toString()}, null, null, null)) {
            if (c.moveToFirst()) complete = c.getInt(0) != 0;
        }
        Map<String, Long> focus = new HashMap<>();
        long totalFocus = 0L;
        for (Session session : getSessions()) {
            long actual = DailyTotals.totalsByDay(session).getOrDefault(day, 0L);
            if (actual > 0) {
                focus.put(session.taskId, Math.addExact(focus.getOrDefault(session.taskId, 0L), actual));
                totalFocus = Math.addExact(totalFocus, actual);
            }
        }
        List<DayTask> items = new ArrayList<>();
        double total = complete ? 0d : Double.NaN;
        try (Cursor c = db.query("daily_task_snapshots", null, "day=?", new String[]{day.toString()},
                null, null, "archived,CASE WHEN rank IS NULL THEN 1 ELSE 0 END,rank,task_id")) {
            while (c.moveToNext()) {
                String id = string(c, "task_id");
                int rank = nullableInt(c, "rank", -1), progress = nullableInt(c, "progress", -1);
                int weightColumn = c.getColumnIndexOrThrow("weight");
                double weight = c.isNull(weightColumn) ? Double.NaN : c.getDouble(weightColumn);
                boolean archived = integer(c, "archived") != 0;
                int doneColumn = c.getColumnIndexOrThrow("legacy_done");
                Boolean done = c.isNull(doneColumn) ? null : c.getInt(doneColumn) != 0;
                items.add(new DayTask(id, string(c, "title"), rank, progress, weight,
                        focus.getOrDefault(id, 0L), archived, done));
                if (!archived) total += progress < 0 || rank < 1 ? Double.NaN : progress * weight;
            }
        }
        return new DaySnapshot(day, items, total, complete, totalFocus);
    }

    /** Days with recorded task/checklist/session evidence, newest first. Empty headers are not history. */
    public synchronized List<LocalDate> getRecordedDays() {
        TreeSet<LocalDate> days = new TreeSet<>(Collections.reverseOrder());
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT day FROM daily_task_snapshots UNION SELECT day FROM task_checks", null)) {
            while (c.moveToNext()) days.add(LocalDate.parse(c.getString(0)));
        }
        for (Session session : getSessions()) days.addAll(sessionDays(session));
        return new ArrayList<>(days);
    }

    private static void requireRank(int rank, int count) {
        if (rank < 1 || rank > count) throw new IllegalArgumentException("优先级须在 1 到 " + count + " 之间");
    }

    private static int nullableInt(Cursor c, String column, int fallback) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? fallback : c.getInt(index);
    }

    private static List<Task> activeTasks(SQLiteDatabase db) {
        List<Task> result = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT tasks.* FROM tasks JOIN projects ON tasks.project_id=projects.id "
                + "WHERE tasks.archived=0 AND projects.archived=0 ORDER BY tasks.sort,tasks.id", null)) {
            while (c.moveToNext()) result.add(taskFrom(c));
        }
        return result;
    }

    private static void writeOrder(SQLiteDatabase db, List<Task> tasks) {
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).sort = i;
            ContentValues values = new ContentValues(); values.put("sort", i);
            db.update("tasks", values, "id=?", new String[]{tasks.get(i).id});
        }
    }

    private static void ensureInternalProject(SQLiteDatabase db) {
        ContentValues values = projectValues(new Project("v2-default", "任务", 0xff15877d, 0));
        db.insertWithOnConflict("projects", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** A backwards device date must never reopen a finalized prior-day snapshot for editing. */
    private static boolean isBeforeLatestDay(SQLiteDatabase db, LocalDate day) {
        try (Cursor c = db.rawQuery("SELECT MAX(day) FROM daily_snapshots WHERE complete=1", null)) {
            return c.moveToFirst() && !c.isNull(0) && day.isBefore(LocalDate.parse(c.getString(0)));
        }
    }

    private static void requireEditableDay(SQLiteDatabase db, LocalDate day) {
        if (isBeforeLatestDay(db, day))
            throw new IllegalStateException("设备日期早于已记录日期，请校准日期后修改任务或进度；历史记录已保留");
    }

    private static boolean ensureTodayInTransaction(SQLiteDatabase db, LocalDate today) {
        // Reading history and saving elapsed timer evidence remain safe during a clock rollback.
        if (isBeforeLatestDay(db, today)) return false;
        boolean exists = false, complete = false;
        try (Cursor c = db.query("daily_snapshots", new String[]{"complete"}, "day=?",
                new String[]{today.toString()}, null, null, null)) {
            if (c.moveToFirst()) { exists = true; complete = c.getInt(0) != 0; }
        }
        if (exists && complete) return false;
        ContentValues values = new ContentValues();
        values.put("day", today.toString()); values.put("complete", 1); values.put("legacy", exists ? 1 : 0);
        if (exists) db.update("daily_snapshots", values, "day=?", new String[]{today.toString()});
        else db.insertOrThrow("daily_snapshots", null, values);
        writeOrder(db, activeTasks(db));
        // On the migration day V1 progress is unknown for every carried task. The next day
        // starts at the specified V2 baseline of zero. An explicit input can resolve it today.
        refreshTodaySnapshot(db, today, exists);
        return true;
    }

    private static void refreshTodaySnapshot(SQLiteDatabase db, LocalDate today) {
        refreshTodaySnapshot(db, today, false);
    }

    private static void refreshTodaySnapshot(SQLiteDatabase db, LocalDate today, boolean legacy) {
        List<Task> active = activeTasks(db);
        Set<String> activeIds = new HashSet<>();
        for (Task task : active) activeIds.add(task.id);
        // Archive non-active rows first and release all live rank slots before reassigning.
        List<String> existingIds = new ArrayList<>();
        try (Cursor c = db.query("daily_task_snapshots", new String[]{"task_id"}, "day=?",
                new String[]{today.toString()}, null, null, null)) {
            while (c.moveToNext()) existingIds.add(c.getString(0));
        }
        for (String id : existingIds) {
            ContentValues v = new ContentValues();
            if (activeIds.contains(id)) v.putNull("rank");
            else v.put("archived", 1);
            db.update("daily_task_snapshots", v, "day=? AND task_id=?", new String[]{today.toString(), id});
        }
        for (int i = 0; i < active.size(); i++) {
            Task task = active.get(i);
            ContentValues values = new ContentValues();
            values.put("day", today.toString()); values.put("task_id", task.id); values.put("title", task.title);
            values.put("rank", i + 1); values.put("weight", PriorityProgress.weight(i + 1, active.size()));
            values.put("archived", 0);
            int count = db.update("daily_task_snapshots", values, "day=? AND task_id=?",
                    new String[]{today.toString(), task.id});
            if (count == 0) {
                if (legacy) values.putNull("progress"); else values.put("progress", 0);
                db.insertOrThrow("daily_task_snapshots", null, values);
            }
        }
    }

    private static void writeProgress(SQLiteDatabase db, String id, int progress, LocalDate day) {
        ContentValues values = new ContentValues(); values.put("progress", progress);
        int changed = db.update("daily_task_snapshots", values, "day=? AND task_id=? AND archived=0",
                new String[]{day.toString(), id});
        if (changed != 1) throw new IllegalArgumentException("找不到今天的任务快照");
    }

    private static Set<LocalDate> sessionDays(Session session) {
        Set<LocalDate> days = new HashSet<>(DailyTotals.totalsByDay(session).keySet());
        if (days.isEmpty()) days.add(Instant.ofEpochMilli(session.startedWallMs)
                .atZone(ZoneId.of(session.zoneId)).toLocalDate());
        return days;
    }

    /** Unknown historical fields are never inferred from the user's current task list. */
    private static void recordSessionEvidence(SQLiteDatabase db, Session session) {
        for (LocalDate day : sessionDays(session)) {
            ContentValues header = new ContentValues(); header.put("day", day.toString());
            header.put("complete", 0); header.put("legacy", 1);
            db.insertWithOnConflict("daily_snapshots", null, header, SQLiteDatabase.CONFLICT_IGNORE);
            boolean complete = false;
            try (Cursor c = db.query("daily_snapshots", new String[]{"complete"}, "day=?",
                    new String[]{day.toString()}, null, null, null)) {
                if (c.moveToFirst()) complete = c.getInt(0) != 0;
            }
            ContentValues values = new ContentValues(); values.put("day", day.toString());
            values.put("task_id", session.taskId); values.put("title", session.taskTitle);
            values.put("archived", complete ? 1 : 0);
            db.insertWithOnConflict("daily_task_snapshots", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    private void notifyArchive() { DailyArchive.schedule(appContext); }


    /**
     * Completion + next timer state are one durable transaction. A stable session id is
     * the exactly-once key, including retries after a process dies during completion.
     * null state clears the timer; null completion only checkpoints a running/paused timer.
     */
    public synchronized void persistTimer(TimerEngine.State state, Session completion) {
        LocalDate today = LocalDate.now();
        byte[] stateBytes = state == null || state.sessionId == null ? null : serialize(state);
        ContentValues completionValues = completion == null ? null : sessionValues(completion);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureTodayInTransaction(db, today);
            if (completionValues != null) {
                long inserted = db.insertWithOnConflict("sessions", null, completionValues,
                        SQLiteDatabase.CONFLICT_IGNORE);
                if (inserted != -1) recordSessionEvidence(db, completion);
            }
            if (stateBytes == null) db.delete("timer_state", "singleton=1", null);
            else {
                ContentValues values = new ContentValues();
                values.put("singleton", 1);
                values.put("record", stateBytes);
                db.insertWithOnConflict("timer_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (completion != null) notifyArchive();
    }

    public synchronized TimerEngine.State loadTimer() {
        try (Cursor c = getReadableDatabase().query("timer_state", new String[]{"record"},
                "singleton=1", null, null, null, null)) {
            return c.moveToFirst() ? deserialize(c.getBlob(0), TimerEngine.State.class) : null;
        }
    }

    public synchronized List<Session> getSessions() {
        List<Session> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("sessions", new String[]{"record"},
                "deleted=0", null, null, null, "started_wall_ms DESC,id DESC")) {
            while (c.moveToNext()) result.add(deserialize(c.getBlob(0), Session.class));
        }
        return result;
    }

    public synchronized Session getSession(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().query("sessions", new String[]{"record"},
                "id=? AND deleted=0", new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? deserialize(c.getBlob(0), Session.class) : null;
        }
    }

    /** Tombstone prevents an old timer-completion retry from reviving a deleted record. */
    public synchronized void deleteSession(String id) {
        ContentValues values = new ContentValues();
        values.put("deleted", 1);
        getWritableDatabase().update("sessions", values, "id=?", new String[]{requireText(id, "记录编号")});
        notifyArchive();
    }

    public synchronized void saveEditedSession(Session session) {
        if (session == null || !session.edited) throw new IllegalArgumentException("更正记录需要标记为已修改");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            validateManualBoundary(session);
            ContentValues values = sessionValues(session);
            if (db.update("sessions", values, "id=? AND deleted=0", new String[]{session.id}) == 0)
                throw new IllegalArgumentException("找不到需要更正的记录");
            recordSessionEvidence(db, session);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    public synchronized void addManualSession(Session session) {
        LocalDate today = LocalDate.now();
        if (session == null || !session.manual) throw new IllegalArgumentException("补记记录需要标记为手动补记");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            validateManualBoundary(session);
            ensureTodayInTransaction(db, today);
            db.insertOrThrow("sessions", null, sessionValues(session));
            recordSessionEvidence(db, session);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        notifyArchive();
    }

    private void validateManualBoundary(Session session) {
        TimerEngine.State timer = loadTimer();
        if (timer != null && timer.sessionId != null)
            throw new IllegalArgumentException("请先结束当前计时，再补记或更正历史记录");
        Session existing = getSession(session.id);
        if (existing == null || !sameIntervals(existing, session)) {
            if (!overlappingSessions(session).isEmpty())
                throw new IllegalArgumentException("这段时间与已有专注记录重叠，请调整开始时间或时长");
            long now = System.currentTimeMillis();
            if (session.startedWallMs > now || session.endedWallMs > now)
                throw new IllegalArgumentException("只能补记或更正已发生的时间，结束时间不能晚于现在");
            for (int i = 0; i < session.segments.size(); i++) {
                Segment a = session.segments.get(i);
                if (a.startWallMs > now || a.endWallMs > now)
                    throw new IllegalArgumentException("只能补记或更正已发生的时间，结束时间不能晚于现在");
                if (a.actualMs <= 0) continue;
                for (int j = i + 1; j < session.segments.size(); j++) {
                    Segment b = session.segments.get(j);
                    if (b.actualMs > 0 && a.startWallMs < b.endWallMs && b.startWallMs < a.endWallMs)
                        throw new IllegalArgumentException("同一条补记的时间片段不能互相重叠");
                }
            }
        }
    }

    private static boolean sameIntervals(Session a, Session b) {
        if (a.actualMs != b.actualMs || a.startedWallMs != b.startedWallMs || a.endedWallMs != b.endedWallMs
                || a.segments.size() != b.segments.size()) return false;
        for (int i = 0; i < a.segments.size(); i++) {
            Segment x = a.segments.get(i), y = b.segments.get(i);
            if (x.startWallMs != y.startWallMs || x.endWallMs != y.endWallMs || x.actualMs != y.actualMs) return false;
        }
        return true;
    }

    /** Positive actual intervals only; adjacent records do not overlap. */
    public synchronized List<Session> overlappingSessions(Session candidate) {
        if (candidate == null) return Collections.emptyList();
        List<Session> result = new ArrayList<>();
        for (Session existing : getSessions()) {
            if (existing.id.equals(candidate.id)) continue;
            boolean overlap = false;
            for (Segment a : candidate.segments) {
                if (a.actualMs <= 0) continue;
                for (Segment b : existing.segments) {
                    if (b.actualMs <= 0) continue;
                    if (a.startWallMs < b.endWallMs && b.startWallMs < a.endWallMs) { overlap = true; break; }
                }
                if (overlap) break;
            }
            if (overlap) result.add(existing);
        }
        return result;
    }

    /** Category name frozen when the session is written, with safe fallback for legacy records. */
    public synchronized String getSessionProjectName(Session session) {
        if (session == null) return "未分类";
        try (Cursor c = getReadableDatabase().query("sessions", new String[]{"project_name"},
                "id=?", new String[]{session.id}, null, null, null)) {
            if (c.moveToFirst() && !c.isNull(0) && !c.getString(0).isEmpty()) return c.getString(0);
        }
        Project project = getProject(session.projectId);
        return project == null ? "未分类" : project.name;
    }

    private ContentValues sessionValues(Session session) {
        if (session == null) throw new IllegalArgumentException("记录不能为空");
        requireText(session.id, "记录编号");
        if (session.plannedMs < 0 || session.actualMs < 0) throw new IllegalArgumentException("时长不能为负数");
        ContentValues values = new ContentValues();
        values.put("id", session.id);
        values.put("started_wall_ms", session.startedWallMs);
        values.put("ended_wall_ms", session.endedWallMs);
        values.put("project_id", session.projectId);
        values.put("task_id", session.taskId);
        Project project = getProject(session.projectId);
        values.put("project_name", project == null ? "未分类" : project.name);
        // Renaming a category must not relabel old records when only their task title is corrected.
        try (Cursor c = getReadableDatabase().query("sessions", new String[]{"project_id", "project_name"},
                "id=?", new String[]{session.id}, null, null, null)) {
            if (c.moveToFirst()) {
                String oldProject = c.isNull(0) ? null : c.getString(0);
                if ((oldProject == null ? session.projectId == null : oldProject.equals(session.projectId)) && !c.isNull(1))
                    values.put("project_name", c.getString(1));
            }
        }
        values.put("record", serialize(session));
        values.put("deleted", 0);
        return values;
    }

    private int nextSort(String table) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(sort),-1) FROM " + table, null)) {
            c.moveToFirst();
            long next = c.getLong(0) + 1L;
            return (int) Math.min(Integer.MAX_VALUE, next);
        }
    }

    private static Project projectFrom(Cursor c) {
        return new Project(string(c, "id"), string(c, "name"), integer(c, "color"),
                integer(c, "sort"), integer(c, "archived") != 0);
    }

    private static Task taskFrom(Cursor c) {
        return new Task(string(c, "id"), string(c, "title"), string(c, "project_id"),
                integer(c, "planned_minutes"), integer(c, "sort"), integer(c, "week_mask"),
                string(c, "one_off_date"), integer(c, "archived") != 0);
    }

    private static ContentValues projectValues(Project p) {
        ContentValues values = new ContentValues();
        values.put("id", p.id);
        values.put("name", p.name);
        values.put("color", p.color);
        values.put("sort", p.sort);
        values.put("archived", p.archived ? 1 : 0);
        return values;
    }

    private static ContentValues taskValues(Task t) {
        ContentValues values = new ContentValues();
        values.put("id", t.id);
        values.put("title", t.title);
        values.put("project_id", t.projectId);
        values.put("planned_minutes", t.plannedMinutes);
        values.put("sort", t.sort);
        values.put("week_mask", t.weekMask);
        values.put("one_off_date", t.oneOffDate);
        values.put("archived", t.archived ? 1 : 0);
        return values;
    }

    private static String string(Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? null : c.getString(index);
    }

    private static int integer(Cursor c, String column) { return c.getInt(c.getColumnIndexOrThrow(column)); }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    static byte[] serialize(Serializable value) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("保存本地数据失败", e); }
    }

    private static <T> T deserialize(byte[] bytes, Class<T> type) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object value = in.readObject();
            if (!type.isInstance(value)) throw new IOException("Unexpected stored object");
            if (value instanceof Session) {
                Session s = (Session) value;
                value = new Session(s.id, s.taskId, s.taskTitle, s.projectId, s.plannedMs, s.actualMs,
                        s.startedWallMs, s.endedWallMs, s.segments, s.reason, s.manual, s.edited, s.zoneId);
            }
            return type.cast(value);
        } catch (IOException | ClassNotFoundException | IllegalArgumentException e) {
            // Do not erase a damaged/incompatible record or pretend the timer is idle.
            throw new IllegalStateException("无法读取本地数据，请保留数据库并联系支持", e);
        }
    }
}
