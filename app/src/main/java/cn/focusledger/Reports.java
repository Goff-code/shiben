package cn.focusledger;

import android.util.Base64;

import cn.focusledger.core.Segment;
import cn.focusledger.core.Session;
import cn.focusledger.core.TimerEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Deterministic, local-only exports. No network, account, or analytics dependencies. */
public final class Reports {
    private Reports() {}

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT);

    /** Compatibility entry point: every readable daily report uses the same compact format. */
    public static String dailyMarkdown(LedgerStore store, LocalDate date) {
        if (store == null || date == null) throw new IllegalArgumentException("账本和日期不能为空");
        synchronized (store) {
            return V2Reports.dailyMarkdown(store.getDaySnapshot(date));
        }
    }

    /** RFC 4180, fixed column order, CRLF and formula-injection-safe text cells. */
    public static String csv(LedgerStore store) {
        if (store == null) throw new IllegalArgumentException("账本不能为空");
        synchronized (store) {
            StringBuilder out = new StringBuilder();
            appendCsvRow(out, "session_id", "task_id", "task_title", "project_id", "project_name",
                    "planned_ms", "actual_ms", "planned_minutes", "actual_minutes", "started_at",
                    "ended_at", "time_zone", "reason", "manual", "edited", "segments_json");
            for (Session session : store.getSessions()) {
                appendCsvRow(out, session.id, session.taskId, session.taskTitle, session.projectId,
                        store.getSessionProjectName(session), Long.toString(session.plannedMs),
                        Long.toString(session.actualMs), minutesDecimal(session.plannedMs),
                        minutesDecimal(session.actualMs), isoTimestamp(session.startedWallMs, session.zoneId),
                        isoTimestamp(session.endedWallMs, session.zoneId), session.zoneId, session.reason,
                        Boolean.toString(session.manual), Boolean.toString(session.edited), segmentsJson(session).toString());
            }
            return out.toString();
        }
    }

    /** Portable, explicit metadata plus a version-labelled exact local timer-state snapshot. */
    public static String backupJson(LedgerStore store) {
        if (store == null) throw new IllegalArgumentException("账本不能为空");
        synchronized (store) {
            try {
                JSONObject root = new JSONObject();
                root.put("format", "focus-ledger-backup");
                root.put("schemaVersion", 1);
                root.put("exportedAt", Instant.now().toString());
                root.put("dateAttribution", "session.zoneId; split actual segments at local midnight");
                JSONArray projects = new JSONArray();
                for (LedgerStore.Project project : store.getAllProjects()) {
                    projects.put(new JSONObject().put("id", project.id).put("name", project.name)
                            .put("color", project.color).put("sort", project.sort).put("archived", project.archived));
                }
                root.put("projects", projects);
                JSONArray tasks = new JSONArray();
                for (LedgerStore.Task task : store.getAllTasks()) {
                    tasks.put(new JSONObject().put("id", task.id).put("title", task.title)
                            .put("projectId", nullable(task.projectId)).put("plannedMinutes", task.plannedMinutes)
                            .put("sort", task.sort).put("weekMask", task.weekMask)
                            .put("oneOffDate", nullable(task.oneOffDate)).put("archived", task.archived));
                }
                root.put("tasks", tasks);
                JSONArray checks = new JSONArray();
                for (LedgerStore.TaskCheck check : store.getAllTaskChecks()) {
                    checks.put(new JSONObject().put("taskId", check.taskId).put("date", check.day.toString())
                            .put("done", check.done).put("taskTitle", check.taskTitle)
                            .put("projectId", nullable(check.projectId)));
                }
                root.put("taskChecks", checks);
                JSONArray sessions = new JSONArray();
                for (Session session : store.getSessions()) {
                    sessions.put(new JSONObject().put("id", session.id).put("taskId", nullable(session.taskId))
                            .put("taskTitle", nullable(session.taskTitle)).put("projectId", nullable(session.projectId))
                            .put("projectName", store.getSessionProjectName(session)).put("plannedMs", session.plannedMs)
                            .put("actualMs", session.actualMs).put("startedWallMs", session.startedWallMs)
                            .put("endedWallMs", session.endedWallMs).put("zoneId", session.zoneId)
                            .put("reason", nullable(session.reason)).put("manual", session.manual)
                            .put("edited", session.edited).put("segments", segmentsJson(session)));
                }
                root.put("sessions", sessions);
                TimerEngine.State timer = store.loadTimer();
                root.put("timerState", timer == null ? JSONObject.NULL : new JSONObject()
                        .put("encoding", "java-serialization-base64")
                        .put("class", TimerEngine.State.class.getName())
                        .put("data", Base64.encodeToString(LedgerStore.serialize(timer), Base64.NO_WRAP)));
                root.put("restoreSupported", false);
                root.put("note", "Metadata backup for safekeeping; this app version does not implement importing backups.");
                return root.toString(2);
            } catch (JSONException e) { throw new IllegalStateException("生成本地备份失败", e); }
        }
    }

    public static String duration(long millis) {
        if (millis < 0) throw new IllegalArgumentException("时长不能为负数");
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) return hours + "小时 " + minutes + "分" + (remainingSeconds > 0 ? " " + remainingSeconds + "秒" : "");
        if (minutes > 0) return minutes + "分" + (remainingSeconds > 0 ? " " + remainingSeconds + "秒" : "");
        return seconds + "秒";
    }

    private static String percentage(long value, long total) {
        return String.format(Locale.ROOT, "%.1f%%", total == 0 ? 0.0 : value * 100.0 / total);
    }

    private static String minutesDecimal(long millis) { return String.format(Locale.ROOT, "%.6f", millis / 60000.0); }

    private static String displayTimestamp(long millis, String zone) {
        return DISPLAY_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)));
    }

    private static String isoTimestamp(long millis, String zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)));
    }

    private static String reasonLabel(String reason) {
        if (reason == null) return "未注明";
        switch (reason) {
            case "expired": return "倒计时完成";
            case "early_end": return "提前结束";
            case "interrupted_restart": return "重启后中断";
            case "manual": return "补记";
            default: return reason;
        }
    }

    private static String markdown(String value) {
        if (value == null || value.isEmpty()) return "未命名";
        return value.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r", " ").replace("\n", " ").replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "\\[").replace("]", "\\]").replace("*", "\\*")
                .replace("_", "\\_").replace("`", "\\`").replace("#", "\\#");
    }

    private static void appendCsvRow(StringBuilder out, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            String value = safeSpreadsheetCell(values[i] == null ? "" : values[i]);
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    /** Quoting alone does not prevent a spreadsheet from executing a formula. */
    private static String safeSpreadsheetCell(String value) {
        int first = 0;
        while (first < value.length()) {
            char c = value.charAt(first);
            if (c == '\t' || c == '\r' || c == '\n') return "'" + value;
            if (Character.isWhitespace(c) || c == '\ufeff') first++;
            else break;
        }
        if (first < value.length() && "=+-@".indexOf(value.charAt(first)) >= 0) return "'" + value;
        return value;
    }

    private static Object nullable(String value) { return value == null ? JSONObject.NULL : value; }

    private static JSONArray segmentsJson(Session session) {
        JSONArray segments = new JSONArray();
        try {
            for (Segment segment : session.segments) {
                segments.put(new JSONObject().put("startWallMs", segment.startWallMs)
                        .put("endWallMs", segment.endWallMs).put("actualMs", segment.actualMs));
            }
            return segments;
        } catch (JSONException e) { throw new IllegalStateException("生成专注片段失败", e); }
    }
}
