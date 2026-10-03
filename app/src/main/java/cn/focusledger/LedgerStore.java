package cn.focusledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** All app data stays in this app-private SQLite database. */
public final class LedgerStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "focus-ledger.db";
    private static final int DATABASE_VERSION = 1;

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

    public LedgerStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
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
        seedDefaults(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Never drop personal history to handle an unknown schema revision.
        throw new IllegalStateException("Unsupported database upgrade: " + oldVersion + " -> " + newVersion);
    }

    private void seedDefaults(SQLiteDatabase db) {
        String[] names = {"Web3", "英语", "锻炼", "读书", "个人IP", "代码视频"};
        String[] titles = {"阅读一篇 Web3 研究", "英语听说练习", "完成今天的训练", "阅读一章", "创作一段内容", "录制或剪辑代码视频"};
        int[] colors = {0xff7c3aed, 0xff2563eb, 0xff059669, 0xffd97706, 0xffdb2777, 0xff0891b2};
        for (int i = 0; i < names.length; i++) {
            String projectId = "project-" + i;
            db.insertOrThrow("projects", null, projectValues(new Project(projectId, names[i], colors[i], i)));
            db.insertOrThrow("tasks", null, taskValues(new Task("task-" + i, titles[i], projectId,
                    25, i, 127, null, false)));
        }
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
        getWritableDatabase().update("projects", projectValues(project), "id=?", new String[]{project.id});
    }

    /** Archive, never erase category or task history. */
    public synchronized void archiveProject(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("archived", 1);
            db.update("projects", values, "id=?", new String[]{requireText(id, "分类编号")});
            db.update("tasks", values, "project_id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized List<Task> getTasks(LocalDate day) {
        if (day == null) throw new IllegalArgumentException("日期不能为空");
        List<Task> result = new ArrayList<>();
        for (Task task : getAllTasks()) {
            Project p = getProject(task.projectId);
            if (!task.archived && p != null && !p.archived && task.scheduledOn(day)) result.add(task);
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
        // UPDATE avoids REPLACE's delete/insert behavior and retains checklist references.
        if (db.update("tasks", taskValues(task), "id=?", new String[]{task.id}) == 0)
            db.insertOrThrow("tasks", null, taskValues(task));
    }

    public synchronized void deleteTask(String id) {
        ContentValues values = new ContentValues();
        values.put("archived", 1);
        getWritableDatabase().update("tasks", values, "id=?", new String[]{requireText(id, "任务编号")});
    }

    public synchronized void moveTask(String id, int delta) {
        if (delta == 0) return;
        List<Task> active = new ArrayList<>();
        for (Task task : getAllTasks()) {
            Project project = getProject(task.projectId);
            if (!task.archived && project != null && !project.archived) active.add(task);
        }
        int from = -1;
        for (int i = 0; i < active.size(); i++) if (active.get(i).id.equals(id)) { from = i; break; }
        if (from < 0) return;
        int to = (int) Math.max(0L, Math.min(active.size() - 1L, (long) from + delta));
        if (from == to) return;
        Task moved = active.remove(from);
        active.add(to, moved);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < active.size(); i++) {
                ContentValues values = new ContentValues();
                values.put("sort", i);
                db.update("tasks", values, "id=?", new String[]{active.get(i).id});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized boolean taskDone(String taskId, LocalDate day) {
        if (taskId == null || day == null) return false;
        try (Cursor c = getReadableDatabase().query("task_checks", new String[]{"done"},
                "task_id=? AND day=?", new String[]{taskId, day.toString()}, null, null, null)) {
            return c.moveToFirst() && c.getInt(0) != 0;
        }
    }

    /** Explicit self-report. Completing a timer never sets this flag. */
    public synchronized void setTaskDone(String taskId, LocalDate day, boolean done) {
        if (day == null) throw new IllegalArgumentException("日期不能为空");
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

    /**
     * Completion + next timer state are one durable transaction. A stable session id is
     * the exactly-once key, including retries after a process dies during completion.
     * null state clears the timer; null completion only checkpoints a running/paused timer.
     */
    public synchronized void persistTimer(TimerEngine.State state, Session completion) {
        byte[] stateBytes = state == null || state.sessionId == null ? null : serialize(state);
        ContentValues completionValues = completion == null ? null : sessionValues(completion);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (completionValues != null) db.insertWithOnConflict("sessions", null, completionValues,
                    SQLiteDatabase.CONFLICT_IGNORE);
            if (stateBytes == null) db.delete("timer_state", "singleton=1", null);
            else {
                ContentValues values = new ContentValues();
                values.put("singleton", 1);
                values.put("record", stateBytes);
                db.insertWithOnConflict("timer_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
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
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void addManualSession(Session session) {
        if (session == null || !session.manual) throw new IllegalArgumentException("补记记录需要标记为手动补记");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            validateManualBoundary(session);
            db.insertOrThrow("sessions", null, sessionValues(session));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
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
