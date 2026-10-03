package cn.focusledger;

import android.util.Base64;

import cn.focusledger.core.DailyTotals;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic, local-only exports. No network, account, or analytics dependencies. */
public final class Reports {
    private Reports() {}

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT);

    private static final class DayEntry {
        final Session session;
        final long actualMs;
        DayEntry(Session session, long actualMs) { this.session = session; this.actualMs = actualMs; }
    }

    private static final class Category {
        final String name;
        long actualMs;
        Category(String name) { this.name = name; }
    }

    /** Recorded session timezone controls midnight splits, even after device timezone changes. */
    public static String dailyMarkdown(LedgerStore store, LocalDate date) {
        if (store == null || date == null) throw new IllegalArgumentException("账本和日期不能为空");
        synchronized (store) {
            List<DayEntry> entries = new ArrayList<>();
            Map<String, Category> categories = new LinkedHashMap<>();
            Set<String> zones = new LinkedHashSet<>();
            long total = 0;
            for (Session session : store.getSessions()) {
                Long part = DailyTotals.totalsByDay(session).get(date);
                long amount = part == null ? 0 : part;
                boolean zeroOnDay = session.actualMs == 0 && Instant.ofEpochMilli(session.startedWallMs)
                        .atZone(ZoneId.of(session.zoneId)).toLocalDate().equals(date);
                if (amount <= 0 && !zeroOnDay) continue;
                entries.add(new DayEntry(session, amount));
                total = Math.addExact(total, amount);
                zones.add(session.zoneId);
                String key = session.projectId == null ? "" : session.projectId;
                Category category = categories.get(key);
                if (category == null) {
                    category = new Category(store.getSessionProjectName(session));
                    categories.put(key, category);
                }
                category.actualMs = Math.addExact(category.actualMs, amount);
            }
            entries.sort(Comparator.comparingLong(entry -> entry.session.startedWallMs));
            List<Category> sortedCategories = new ArrayList<>(categories.values());
            sortedCategories.sort((a, b) -> Long.compare(b.actualMs, a.actualMs));

            StringBuilder out = new StringBuilder();
            out.append("# 专注账本 · ").append(date).append("\n\n");
            out.append("- 实际专注：**").append(duration(total)).append("**\n");
            out.append("- 当日相关记录：").append(entries.size()).append(" 条\n");
            out.append("- 统计依据：仅已保存的实际专注时间；暂停、休息和未保存的当前计时不计入。\n\n");

            out.append("## 分类投入\n\n");
            if (sortedCategories.isEmpty()) out.append("当天还没有专注记录。\n\n");
            else {
                out.append("| 分类 | 实际时长 | 占比 |\n| --- | ---: | ---: |\n");
                for (Category category : sortedCategories) {
                    out.append("| ").append(markdown(category.name)).append(" | ").append(duration(category.actualMs))
                            .append(" | ").append(percentage(category.actualMs, total)).append(" |\n");
                }
                out.append('\n');
            }

            out.append("## 任务自报完成\n\n");
            Map<String, LedgerStore.Task> scheduled = new LinkedHashMap<>();
            for (LedgerStore.Task task : store.getTasks(date)) scheduled.put(task.id, task);
            // Keep checked historical tasks visible after archive, rename or schedule changes.
            Map<String, LedgerStore.TaskCheck> checks = new LinkedHashMap<>();
            for (LedgerStore.TaskCheck check : store.getTaskChecks(date)) checks.put(check.taskId, check);
            for (LedgerStore.TaskCheck check : checks.values()) {
                if (!scheduled.containsKey(check.taskId)) {
                    LedgerStore.Task task = new LedgerStore.Task(check.taskId, check.taskTitle, check.projectId,
                            25, 0, 0, date.toString(), true);
                    scheduled.put(check.taskId, task);
                }
            }
            int done = 0;
            for (String taskId : scheduled.keySet()) if (store.taskDone(taskId, date)) done++;
            out.append("自报完成 ").append(done).append(" / ").append(scheduled.size())
                    .append("（按当前任务计划，保留当日勾选）。\n")
                    .append("完成勾选与计时记录独立；此清单不是当时任务计划的完整快照。\n\n");
            if (scheduled.isEmpty()) out.append("当天没有安排任务。\n\n");
            else {
                for (LedgerStore.Task task : scheduled.values()) {
                    LedgerStore.TaskCheck check = checks.get(task.id);
                    String title = check == null ? task.title : check.taskTitle;
                    String projectId = check == null ? task.projectId : check.projectId;
                    LedgerStore.Project project = store.getProject(projectId);
                    out.append("- [").append(store.taskDone(task.id, date) ? 'x' : ' ').append("] ")
                            .append(markdown(title)).append("（")
                            .append(markdown(project == null ? "未分类" : project.name)).append('）');
                    if (task.archived) out.append(" · 历史任务");
                    out.append('\n');
                }
                out.append('\n');
            }

            out.append("## 专注明细\n\n");
            if (entries.isEmpty()) out.append("暂无记录。\n\n");
            else {
                out.append("| 开始 → 结束（记录时区） | 任务 | 分类 | 本日实际 | 整段计划 / 实际 | 结束方式与标记 |\n")
                        .append("| --- | --- | --- | ---: | ---: | --- |\n");
                for (DayEntry entry : entries) {
                    Session session = entry.session;
                    out.append("| ").append(displayTimestamp(session.startedWallMs, session.zoneId)).append(" → ")
                            .append(displayTimestamp(session.endedWallMs, session.zoneId)).append(" · ")
                            .append(markdown(session.zoneId)).append(" | ").append(markdown(session.taskTitle))
                            .append(" | ").append(markdown(store.getSessionProjectName(session))).append(" | ")
                            .append(duration(entry.actualMs)).append(" | ").append(duration(session.plannedMs)).append(" / ")
                            .append(duration(session.actualMs)).append(" | ").append(markdown(reasonLabel(session.reason)))
                            .append(session.manual ? " · 手动补记" : "")
                            .append(session.edited ? " · 已更正" : "").append(" |\n");
                }
                out.append('\n');
            }

            out.append("## 口径说明\n\n")
                    .append("- 跨午夜记录按每个实际专注片段分摊到日期；只统计本日部分，不把整段重复记到两天。\n")
                    .append("- 日期采用记录创建时保存的时区。当前设备时区变更不会改写旧记录的日期。\n")
                    .append("- 本日报告包含的记录时区：")
                    .append(zones.isEmpty() ? "无记录" : markdown(String.join("、", zones))).append("。\n")
                    .append("- 计时结束不代表任务已经完成；任务完成由本人勾选。手动补记和更正保留标记。\n")
                    .append("- 任务清单保留当日勾选时的任务名称与分类归属；清单分类名称采用当前分类信息。\n")
                    .append("- 分类占比以本日实际专注总时长为分母；零总时长显示 0.0%。\n");
            return out.toString();
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
