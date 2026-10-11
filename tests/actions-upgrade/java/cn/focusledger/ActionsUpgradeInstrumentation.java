package cn.focusledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import cn.focusledger.core.Segment;
import cn.focusledger.core.Session;
import cn.focusledger.core.TimerEngine;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Standalone test APK, compiled against real old/new production trees unchanged.
 * Seed: code5 only. Verify/routing: code7 only. No fake versionCode rollback.
 * System controls are modified exclusively by ChannelSettingsUi; this class
 * reads channels and executes production timer/completion paths.
 */
@SuppressWarnings("deprecation")
public final class ActionsUpgradeInstrumentation extends Instrumentation {
    private static final String PACKAGE = "cn.goff.shiben";
    private static final String[] IDS = {"shiben_done_sound_vibrate", "shiben_done_sound_still",
            "shiben_done_quiet_vibrate", "shiben_done_quiet_still"};
    private static final boolean[] SOUND = {true, true, false, false};
    private static final boolean[] VIBRATE = {true, false, true, false};
    private static final String[] LABEL = {"响铃和震动", "仅响铃", "仅震动", "静音（仅通知）"};
    private String phase;
    private int checks;
    private StringBuilder report = new StringBuilder();
    private Context context;
    private TimerCoordinator timer;
    private LedgerStore store;
    private NotificationManager notifications;
    private File evidence;
    private JSONObject baseline;
    private ChannelSettingsUi systemUi;
    private volatile Activity screen;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "" : args.getString("phase", "");
        start();
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity instanceof MainActivity) screen = activity;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            require(Arrays.asList("seed", "verify", "routing").contains(phase), "explicit phase seed|verify|routing required");
            context = getTargetContext();
            guard();
            evidence = new File(context.getFilesDir(), "qa-actions-upgrade");
            if ("seed".equals(phase)) require(!evidence.exists(), "seed refuses existing/incomplete fixture; use a fresh disposable AVD");
            require(evidence.isDirectory() || evidence.mkdirs(), "create private QA evidence directory");
            systemUi = new ChannelSettingsUi(this, context, evidence);
            timer = TimerCoordinator.get(context);
            store = timer.store();
            notifications = context.getSystemService(NotificationManager.class);
            if ("seed".equals(phase)) seed();
            else {
                baseline = readJson("baseline.json");
                checkIdentity();
                if ("verify".equals(phase)) verify(); else routing();
            }
            require(LocalDate.now().toString().equals(baseline.getString("day")), "fixture stayed on the same local calendar day");
            dumpSystem(phase);
            write(phase + "-passed.json", new JSONObject().put("phase", phase).put("token", baseline.getString("token"))
                    .put("versionCode", version()).put("scope", "software-only ephemeral-debug-key upgrade").toString(2));
            String pass = "ACTIONS_UPGRADE_PASS phase=" + phase;
            report.append(pass).append('\n');
            report.append("OK (").append(checks).append(" checks)\n");
            write(phase + ".log", report.toString());
            result.putString("stream", report.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            String message = "ACTIONS_UPGRADE_FAIL phase=" + phase + " " + failure;
            report.append(message).append('\n');
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            report.append(trace);
            try {
                if (systemUi != null) systemUi.capture(phase + "-failure");
                if (evidence != null) { write(phase + "-failed.log", report.toString()); dumpSystem(phase + "-failed"); }
            } catch (Throwable evidenceFailure) { report.append("Evidence capture failed: ").append(evidenceFailure).append('\n'); }
            result.putString("stream", report.toString());
            result.putString("shortMsg", message);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void guard() throws Exception {
        require(PACKAGE.equals(context.getPackageName()), "stable production applicationId");
        require((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                "only debug-target instrumentation is permitted");
        require("1".equals(shell("getprop ro.kernel.qemu").trim()), "only disposable emulator targets are permitted");
        require(Build.VERSION.SDK_INT == 35, "channel Settings driver is pinned to API 35");
        require("en".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage()),
                "AOSP system Settings must use English; fixture fails rather than guessing translated controls");
        require(version() == ("seed".equals(phase) ? 5 : 7), "real source versionCode must be 5 before and 7 after install-r");
        require(("seed".equals(phase) ? "2.0.2" : "3.0").equals(packageInfo().versionName), "real source versionName");
    }

    private void seed() throws Exception {
        require(store.getAllTasks().isEmpty() && store.getSessions().isEmpty() && !timer.engine().isActive(),
                "fresh old app has no user ledger or timer");
        require(timer.notificationsGranted(), "old app has system notification permission before seeding");
        for (String id : IDS) require(notifications.getNotificationChannel(id) == null, "no pre-existing mode channel " + id);
        baseline = new JSONObject().put("schema", 1).put("token", UUID.randomUUID().toString())
                .put("day", LocalDate.now().toString()).put("identity", identity());
        launch();
        LedgerStore.Task alpha = store.saveSimpleTask("ActionsUpgradeAlpha", 37, 1);
        LedgerStore.Task beta = store.saveSimpleTask("ActionsUpgradeBeta", 8, 2);
        baseline.put("alpha", alpha.id).put("beta", beta.id);
        long prior = LocalDate.now().minusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        store.addManualSession(Session.manual("actions-prior", alpha.id, alpha.title, alpha.projectId,
                prior, 125000, ZoneId.systemDefault().getId(), true));
        store.addManualSession(Session.manual("actions-tombstone", alpha.id, alpha.title, alpha.projectId,
                prior + 3600000, 43000, ZoneId.systemDefault().getId(), true));
        store.deleteSession("actions-tombstone");
        // Actual monotonic time, with the unchanged old public timer API.
        runOnMainSync(() -> timer.start(alpha, 15)); SystemClock.sleep(2300); runOnMainSync(timer::finish);
        require(!timer.engine().isActive(), "old real elapsed timer session was saved");
        for (int i = 0; i < IDS.length; i++) {
            setMode(i);
            expire(alpha, i, false);
            NotificationChannel channel = channel(IDS[i]);
            require(channel.getImportance() == NotificationManager.IMPORTANCE_HIGH
                    && (channel.getSound() != null) == SOUND[i] && channel.shouldVibrate() == VIBRATE[i],
                    "genuine old completion created released default channel " + IDS[i]);
        }
        write("channels-old-defaults.json", channels().toString(2));
        // Every mapping changes a default that was nontrivial in the old binary.
        systemUi.disable(IDS[0]);
        systemUi.soundNone(IDS[1]);
        systemUi.vibrationOff(IDS[2]);
        systemUi.lowImportance(IDS[3]);
        assertOverrides();
        setMode(0);
        require(settings().edit().putString("qa-unrelated-text", "retain exactly")
                .putInt("qa-unrelated-number", 202).commit(), "seed unrelated settings sentinels");
        // Keep the selected disabled sound+vibration channel to expose its warning after upgrade.
        launch();
        runOnMainSync(() -> timer.start(beta, 25)); SystemClock.sleep(2300); runOnMainSync(timer::pause);
        stopLiveService();
        require(timer.engine().isPaused() && timer.actualMs() >= 2000, "real paused old timer has elapsed time");
        appTab("专注");
        assertAppText("已暂停"); assertAppText("继续专注");
        systemUi.capture("seed-paused-old");
        awaitArchive();
        baseline.put("database", database(false)).put("databaseWithoutTimer", database(true))
                .put("preferences", preferences()).put("channels", channels()).put("timer", timerProjection())
                .put("archiveFiles", archiveNames());
        write("baseline.json", baseline.toString(2));
        note("Seed kept old channel system overrides, raw SQL/session/timer bytes, all preference types, prior history and tombstone.");
    }

    private void verify() throws Exception {
        require(!new File(evidence, "routing-started.json").exists(), "retention must be checked before destructive routing fixture");
        // Read before launching an Activity or service: compare the paused serialized BLOB exactly.
        equal(baseline.getJSONObject("database"), database(false), "all persisted SQL values/types/schema and raw session/timer bytes immediately after update");
        equal(baseline.getJSONObject("preferences"), preferences(), "all persisted preference names, types and values immediately after update");
        equal(baseline.getJSONObject("channels"), channels(), "all existing system channel controls and IDs immediately after update");
        assertOverrides();
        launch(); stopLiveService();
        appTab("专注"); assertAppText("已暂停"); assertAppText("继续专注");
        systemUi.capture("verify-paused-new");
        equal(baseline.getJSONObject("timer"), timerProjection(), "paused timer identities, original times, actual/planned milliseconds and segments");
        SystemClock.sleep(2200);
        equal(baseline.getJSONObject("timer"), timerProjection(), "paused timer accrues no additional attention");
        appTab("任务");
        assertAppDescriptionPrefix("ActionsUpgradeAlpha，进度 37%");
        assertAppDescriptionPrefix("ActionsUpgradeBeta，进度 8%");
        systemUi.capture("verify-tasks-new");
        openAppSettings();
        require(appView(v -> v instanceof Switch && same("专注结束提醒", v.getContentDescription())
                && ((Switch)v).isChecked()) != null, "retained reminder master is visible and enabled");
        require(appView(v -> v instanceof RadioButton && same(LABEL[0], ((RadioButton)v).getText())
                && ((RadioButton)v).isChecked()) != null, "new mode UI reads retained sound/vibration preference pair");
        assertAppText("此提醒方式的系统通知渠道已关闭，请在系统通知设置中检查。");
        systemUi.capture("verify-settings-and-channel-warning");
        awaitArchive();
        equal(baseline.getJSONObject("databaseWithoutTimer"), database(true), "opening all verification screens preserves ledger and history values");
        equal(baseline.getJSONObject("preferences"), preferences(), "opening new reminder UI preserves existing preferences");
        equal(baseline.getJSONObject("channels"), channels(), "opening the new app preserves all channel system controls");
        equal(baseline.getJSONArray("archiveFiles"), archiveNames(), "durable daily report dates remain present");
        write("retention-observed.json", new JSONObject().put("database", database(false))
                .put("preferences", preferences()).put("channels", channels()).put("timer", timerProjection()).toString(2));
        note("Report content is regenerable and may intentionally change format; exact SQL/session data is the retention oracle.");
    }

    private void routing() throws Exception {
        JSONObject verified = readJson("verify-passed.json");
        require(baseline.getString("token").equals(verified.getString("token")), "successful verify of this seed is required before routing");
        require(!new File(evidence, "routing-started.json").exists(), "routing is single-use; do not re-run a partially mutated fixture");
        equal(baseline.getJSONObject("channels"), channels(), "channel baseline is still intact before routing");
        equal(baseline.getJSONObject("timer"), timerProjection(), "retained paused timer is still intact before routing");
        write("routing-started.json", new JSONObject().put("token", baseline.getString("token")).toString());
        launch(); stopLiveService();
        // Only AFTER exact retention passed, intentionally end this disposable timer.
        runOnMainSync(timer::finish);
        require(!timer.engine().isActive(), "retention fixture is ended only after verification passed");
        LedgerStore.Task alpha = store.getTask(baseline.getString("alpha"));
        JSONObject controls = baseline.getJSONObject("channels");
        for (int i = 0; i < IDS.length; i++) {
            openAppSettings();
            final String label = LABEL[i];
            appClick(v -> v instanceof RadioButton && same(label, ((RadioButton)v).getText()));
            require(settings().getBoolean("sound", !SOUND[i]) == SOUND[i]
                    && settings().getBoolean("vibration", !VIBRATE[i]) == VIBRATE[i], "native mode selection uses released preference pair " + IDS[i]);
            expire(alpha, i, i == 0);
            equal(controls, channels(), "completion does not recreate, rename ID, or override system controls " + IDS[i]);
            require(store.getDaySnapshot(LocalDate.now()).items.get(0).progress == 37,
                    "completion does not change manual task progress");
            systemUi.capture("routing-mode-" + i);
        }
        note("Blocked channel produced no active completion; three remaining modes posted notification ID 2 on the exact retained channel.");
        note("Runtime checks cover channel routing/Notification overrides, not physical sound or vibration, realme firmware, background delivery, or formal release signing.");
    }

    private void expire(LedgerStore.Task task, int mode, boolean blocked) throws Exception {
        notifications.cancel(2);
        await(() -> completion() == null, "previous completion cleared");
        await(() -> notifications.getActiveNotifications().length == 0, "no stale live or completion notification before next expiry");
        require(!timer.engine().isActive(), "accelerated completion never overwrites an active timer");
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        TimerEngine engine = new TimerEngine();
        engine.start(task.id, task.title, task.projectId, 15, wall, elapsed, bootId());
        TimerEngine.State state = engine.snapshot();
        state.accumulatedMs = state.plannedMs;
        state.startedWallMs = wall - state.accumulatedMs;
        state.segments = new ArrayList<>();
        state.segments.add(new Segment(state.startedWallMs, wall, state.accumulatedMs));
        state.segmentStartWallMs = state.checkpointWallMs = wall;
        state.segmentStartElapsedMs = state.checkpointElapsedMs = elapsed;
        int count = store.getSessions().size();
        // The test APK accelerates valid elapsed state. Production expiry, persistence,
        // notification building, and NotificationManager delivery stay untouched.
        runOnMainSync(() -> {
            try {
                store.persistTimer(state, null);
                Field field = TimerCoordinator.class.getDeclaredField("engine"); field.setAccessible(true);
                synchronized (timer) { field.set(timer, new TimerEngine(state)); }
                timer.refresh();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        Session completed = store.getSession(state.sessionId);
        require(completed != null && "expired".equals(completed.reason) && completed.actualMs == 900000
                && store.getSessions().size() == count + 1 && !timer.engine().isActive() && store.loadTimer() == null,
                "real production expiry saved one terminal 900000ms record " + IDS[mode]);
        if (blocked) {
            long until = SystemClock.elapsedRealtime() + 2500;
            do { require(completion() == null, "disabled real channel suppresses completion"); SystemClock.sleep(100); }
            while (SystemClock.elapsedRealtime() < until);
        } else {
            await(() -> completion() != null, "completion on " + IDS[mode]);
            Notification notification = completion().getNotification();
            require(IDS[mode].equals(notification.getChannelId()), "actual posted completion uses exact selected released ID " + IDS[mode]);
            require(notification.sound == null && notification.vibrate == null
                    && (notification.defaults & (Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)) == 0
                    && notification.fullScreenIntent == null && (notification.flags & Notification.FLAG_INSISTENT) == 0,
                    "notification has no direct sound/vibration/default/full-screen/insistent bypass");
            write(phase + "-posted-" + mode + ".json", new JSONObject().put("id", completion().getId())
                    .put("channelId", notification.getChannelId()).put("defaults", notification.defaults)
                    .put("flags", notification.flags).put("sound", JSONObject.NULL).put("vibrate", JSONObject.NULL).toString(2));
        }
        for (StatusBarNotification n : notifications.getActiveNotifications())
            require(n.getId() == 2 && n.getTag() == null && !blocked && IDS[mode].equals(n.getNotification().getChannelId()),
                    "no replacement or fallback notification bypasses the chosen channel");
        notifications.cancel(2);
        await(() -> completion() == null, "completion removed before duplicate-expiry check");
        runOnMainSync(timer::refresh); SystemClock.sleep(350);
        require(completion() == null && store.getSessions().size() == count + 1, "repeated expiry neither replays nor duplicates the session");
    }

    private void assertOverrides() {
        require(channel(IDS[0]).getImportance() == NotificationManager.IMPORTANCE_NONE, "sound+vibration channel disabled in system Settings");
        require(channel(IDS[1]).getSound() == null, "sound-only channel sound URI null in system Settings");
        require(!channel(IDS[2]).shouldVibrate(), "vibration-only channel vibration false in system Settings");
        require(channel(IDS[3]).getImportance() == NotificationManager.IMPORTANCE_LOW, "silent channel LOW importance in system Settings");
        int[] bits = {4, 32, 16, 4}; // AOSP USER_LOCKED_IMPORTANCE, SOUND, VIBRATION, IMPORTANCE.
        for (int i = 0; i < IDS.length; i++) require((userLockedFields(channel(IDS[i])) & bits[i]) != 0,
                "system recorded a user-locked field, not an app-created substitute: " + IDS[i]);
    }

    private JSONObject channels() throws Exception {
        JSONObject result = new JSONObject();
        TreeMap<String, NotificationChannel> ordered = new TreeMap<>();
        for (NotificationChannel c : notifications.getNotificationChannels()) ordered.put(c.getId(), c);
        for (NotificationChannel c : ordered.values()) {
            JSONObject value = new JSONObject().put("importance", c.getImportance()).put("sound", nullable(c.getSound()))
                    .put("vibration", c.shouldVibrate()).put("vibrationPattern", c.getVibrationPattern() == null ? JSONObject.NULL : new JSONArray(c.getVibrationPattern()))
                    .put("audioAttributes", nullable(c.getAudioAttributes())).put("bypassDnd", c.canBypassDnd())
                    .put("lockscreenVisibility", c.getLockscreenVisibility()).put("showBadge", c.canShowBadge())
                    .put("lights", c.shouldShowLights()).put("lightColor", c.getLightColor()).put("group", nullable(c.getGroup()))
                    .put("allowBubbles", c.canBubble()).put("conversationId", nullable(c.getConversationId()))
                    .put("parentChannelId", nullable(c.getParentChannelId())).put("importantConversation", c.isImportantConversation())
                    .put("systemUserLockedFields", userLockedFields(c));
            // Name/description are app-owned labels and intentionally change with 3.0.
            // focus_done has no special assertion; if present it is retained with every other ID.
            result.put(c.getId(), value);
        }
        return result;
    }

    private int userLockedFields(NotificationChannel channel) {
        // Read-only diagnostic parsing, pinned to AOSP 35. The field is not a
        // public SDK getter; do not use reflection or a privileged setter.
        // AOSP NotificationChannel.getFieldsString uses Integer.toHexString.
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\bmUserLockedFields=([0-9a-fA-F]+)(?=,)").matcher(channel.toString());
        require(match.find(), "supported AOSP 35 channel diagnostic format");
        return Integer.parseInt(match.group(1), 16);
    }

    private JSONObject database(boolean omitTimer) throws Exception {
        synchronized (store) {
            SQLiteDatabase db = store.getReadableDatabase();
            JSONObject result = new JSONObject().put("userVersion", db.getVersion());
            JSONObject tables = new JSONObject();
            try (Cursor names = db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
                while (names.moveToNext()) {
                    String name = names.getString(0);
                    if (omitTimer && "timer_state".equals(name)) continue;
                    List<String> rows = new ArrayList<>();
                    try (Cursor row = db.rawQuery("SELECT * FROM \"" + name.replace("\"", "\"\"") + "\"", null)) {
                        while (row.moveToNext()) {
                            JSONArray values = new JSONArray();
                            for (int i = 0; i < row.getColumnCount(); i++) {
                                int type = row.getType(i);
                                Object value = type == Cursor.FIELD_TYPE_NULL ? JSONObject.NULL : type == Cursor.FIELD_TYPE_BLOB
                                        ? Base64.encodeToString(row.getBlob(i), Base64.NO_WRAP) : row.getString(i);
                                values.put(new JSONArray().put(row.getColumnName(i)).put(type).put(value));
                            }
                            rows.add(values.toString());
                        }
                    }
                    Collections.sort(rows);
                    tables.put(name, new JSONObject().put("schemaSql", names.getString(1)).put("rows", new JSONArray(rows)));
                }
            }
            return result.put("tables", tables);
        }
    }

    private JSONObject preferences() throws Exception {
        JSONObject result = new JSONObject();
        File[] files = new File(context.getApplicationInfo().dataDir, "shared_prefs").listFiles();
        require(files != null, "shared preferences directory exists");
        Arrays.sort(files);
        for (File file : files) if (file.getName().endsWith(".xml")) {
            String name = file.getName().substring(0, file.getName().length() - 4);
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> e : new TreeMap<>(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()).entrySet()) {
                Object v = e.getValue();
                Object value = v instanceof Set ? new JSONArray(new TreeSet<>((Set<?>) v)) : v;
                values.put(e.getKey(), new JSONObject().put("type", v instanceof Set ? "StringSet" : v.getClass().getSimpleName()).put("value", value));
            }
            result.put(name, values);
        }
        return result;
    }

    private JSONObject timerProjection() throws Exception {
        TimerEngine.State s = store.loadTimer();
        require(s != null && s.paused && s.sessionId != null, "durable timer remains paused");
        JSONArray segments = new JSONArray();
        for (Segment segment : s.segments) segments.put(new JSONArray().put(segment.startWallMs).put(segment.endWallMs).put(segment.actualMs));
        return new JSONObject().put("sessionId", s.sessionId).put("taskId", s.taskId).put("taskTitle", s.taskTitle)
                .put("projectId", s.projectId).put("bootId", s.bootId).put("zoneId", s.zoneId)
                .put("plannedMs", s.plannedMs).put("accumulatedMs", s.accumulatedMs).put("startedWallMs", s.startedWallMs)
                .put("paused", s.paused).put("segments", segments);
    }

    private JSONObject identity() throws Exception {
        PackageInfo p = packageInfo();
        return new JSONObject().put("package", p.packageName).put("uid", context.getApplicationInfo().uid)
                .put("firstInstallTime", p.firstInstallTime).put("signerSha256", Base64.encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(p.signingInfo.getApkContentsSigners()[0].toByteArray()), Base64.NO_WRAP))
                .put("bootId", bootId()).put("fingerprint", Build.FINGERPRINT);
    }

    private void checkIdentity() throws Exception {
        require(baseline.getInt("schema") == 1, "supported fixture baseline schema");
        equal(baseline.getJSONObject("identity"), identity(), "same package, UID, first install, signer, boot, and emulator");
        require(LocalDate.now().toString().equals(baseline.getString("day")), "same local calendar day required");
    }

    private JSONArray archiveNames() {
        File[] files = DailyArchive.get(context).getDirectory().listFiles();
        require(files != null, "daily archive exists");
        List<String> names = new ArrayList<>();
        for (File file : files) if (file.getName().endsWith(".md")) names.add(file.getName());
        Collections.sort(names); require(names.size() >= 2, "today and historical daily reports exist");
        return new JSONArray(names);
    }

    private void awaitArchive() {
        DailyArchive.schedule(context);
        await(() -> DailyArchive.get(context).getBackupStatus().startsWith("已保留 2 天"), "two-day daily archive completion");
        SystemClock.sleep(700);
    }

    private void setMode(int i) {
        require(settings().edit().putBoolean("reminders", true).putBoolean("sound", SOUND[i])
                .putBoolean("vibration", VIBRATE[i]).commit(), "set released old preference pair " + IDS[i]);
    }

    private SharedPreferences settings() { return context.getSharedPreferences("shiben-settings", Context.MODE_PRIVATE); }
    private NotificationChannel channel(String id) { NotificationChannel c = notifications.getNotificationChannel(id); require(c != null, "real channel exists " + id); return c; }
    private PackageInfo packageInfo() throws Exception { return context.getPackageManager().getPackageInfo(PACKAGE, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES); }
    private long version() throws Exception { return packageInfo().getLongVersionCode(); }
    private String bootId() throws Exception { return "boot-" + Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT); }
    private StatusBarNotification completion() { for (StatusBarNotification n : notifications.getActiveNotifications()) if (n.getId() == 2 && n.getTag() == null) return n; return null; }
    private Object nullable(Object value) { return value == null ? JSONObject.NULL : value.toString(); }

    private void launch() {
        screen = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        waitForIdleSync();
        SystemClock.sleep(500);
    }

    private void stopLiveService() {
        runOnMainSync(() -> context.stopService(new Intent(context, TimerService.class)));
        waitForIdleSync(); SystemClock.sleep(300);
        // A paused service otherwise re-anchors its checkpoint every 10 seconds.
        // Stopping only the service leaves the real durable paused timer intact.
    }

    private void openAppSettings() {
        appTab("今日");
        appClick(v -> same("更多", v.getContentDescription()));
        appClick(v -> same("提醒设置", v.getContentDescription()));
        assertAppText("提醒设置");
    }

    private void appTab(String label) {
        appClick(v -> same(label, v.getContentDescription()) || same(label + "，已选中", v.getContentDescription()));
    }

    private void appClick(Predicate<View> match) {
        final boolean[] available = {false};
        runOnMainSync(() -> {
            View view = find(screen.getWindow().getDecorView(), match);
            available[0] = view != null && view.isShown() && view.isEnabled() && view.isClickable();
            // CompoundButton changes checked state even when performClick returns
            // false because it has no OnClickListener. Verify the resulting native
            // preference pair in routing; never substitute a direct state write.
            if (available[0]) view.performClick();
        });
        require(available[0], "expected visible enabled clickable native app control");
        waitForIdleSync(); SystemClock.sleep(200);
    }

    private View appView(Predicate<View> match) {
        final View[] result = {null};
        runOnMainSync(() -> result[0] = find(screen.getWindow().getDecorView(), match));
        return result[0];
    }

    private View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = find(((ViewGroup)view).getChildAt(i), match); if (found != null) return found;
        }
        return null;
    }

    private void assertAppText(String text) { require(appView(v -> v instanceof TextView && same(text, ((TextView)v).getText())) != null, "native app text " + text); }
    private void assertAppDescriptionPrefix(String prefix) { require(appView(v -> v.getContentDescription() != null && v.getContentDescription().toString().startsWith(prefix)) != null, "native task row " + prefix); }

    private void await(BooleanSupplier condition, String description) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
        require(condition.getAsBoolean(), description);
    }

    private String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value; List<String> keys = new ArrayList<>();
            object.keys().forEachRemaining(keys::add); Collections.sort(keys);
            List<String> parts = new ArrayList<>();
            for (String key : keys) parts.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value; List<String> parts = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) parts.add(canonical(array.get(i)));
            return "[" + String.join(",", parts) + "]";
        }
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String) return JSONObject.quote((String)value);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        throw new AssertionError("Unsupported canonical JSON value " + value.getClass());
    }

    private void equal(Object expected, Object actual, String label) throws Exception {
        if (!canonical(expected).equals(canonical(actual))) {
            write(phase + "-mismatch-expected.json", canonical(expected)); write(phase + "-mismatch-actual.json", canonical(actual));
            throw new AssertionError(label + " differs; exact expected/actual evidence saved");
        }
        require(true, label);
    }

    private JSONObject readJson(String name) throws Exception { return new JSONObject(new String(Files.readAllBytes(new File(evidence, name).toPath()), StandardCharsets.UTF_8)); }
    private void write(String name, String text) throws Exception { try (FileOutputStream out = new FileOutputStream(new File(evidence, name))) { out.write(text.getBytes(StandardCharsets.UTF_8)); } }
    private void dumpSystem(String prefix) throws Exception {
        write(prefix + "-notification-dumpsys.txt", shell("dumpsys notification --noredact"));
        write(prefix + "-package-dumpsys.txt", shell("dumpsys package " + PACKAGE));
        write(prefix + "-channels.json", channels().toString(2));
    }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
             FileInputStream in = new FileInputStream(descriptor.getFileDescriptor());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192]; for (int read; (read = in.read(data)) != -1;) out.write(data, 0, read);
            return out.toString("UTF-8");
        }
    }
    private void require(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; note("CHECK " + label); }
    private void note(String text) { report.append(text).append('\n'); Bundle update = new Bundle(); update.putString("stream", text + "\n"); sendStatus(0, update); }
    private static boolean same(String expected, CharSequence actual) { return actual != null && expected.contentEquals(actual); }
}
