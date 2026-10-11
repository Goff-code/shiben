package cn.focusledger;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import cn.focusledger.core.CompletionAlertMode;
import cn.focusledger.core.Segment;
import cn.focusledger.core.Session;
import cn.focusledger.core.TimerEngine;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in, destructive-fixture checks for a fresh disposable debug emulator only.
 * Acceleration and reflection exist only in this test APK. A posted notification
 * and channel configuration do not prove audible output or physical vibration.
 */
public final class ReminderInstrumentationChecks {
    interface Ui {
        void openSettings();
        void select(CompletionAlertMode mode);
        void assertSelection(CompletionAlertMode mode, boolean enabled);
        void toggleMaster();
        void recreate();
        void backAndReopen();
        void screenshot(String name) throws Exception;
        void background();
        void foreground();
        void close();
    }

    private final Instrumentation instrumentation;
    private final Context context;
    private final Ui ui;
    private final Consumer<String> report;
    private final TimerCoordinator timer;
    private final LedgerStore store;
    private final NotificationManager notifications;
    private final SharedPreferences preferences;
    private LedgerStore.Task task;
    private final File evidence;

    private ReminderInstrumentationChecks(Instrumentation instrumentation, Ui ui, Consumer<String> report, String evidenceName) {
        this.instrumentation = instrumentation;
        this.context = instrumentation.getTargetContext();
        this.ui = ui;
        this.report = report;
        this.timer = TimerCoordinator.get(context);
        this.store = timer.store();
        this.notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.preferences = ReminderSettings.preferences(context);
        this.evidence = new File(context.getFilesDir(), evidenceName);
    }

    static void run(Instrumentation instrumentation, Ui ui, Consumer<String> report) throws Exception {
        new ReminderInstrumentationChecks(instrumentation, ui, report, "qa-reminders").run();
    }

    static void runDenied(Instrumentation instrumentation, Consumer<String> report) throws Exception {
        new ReminderInstrumentationChecks(instrumentation, null, report, "qa-reminders-denied").runDenied();
    }

    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        report.accept(name);
    }

    private void requireFreshFixture(boolean granted) throws Exception {
        check((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        && (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator")
                        || Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")
                        || ("Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys".equals(Build.FINGERPRINT)
                        && "ranchu".equals(Build.HARDWARE)))
                        && "1".equals(shell("getprop ro.kernel.qemu").trim()),
                "reminders suite is restricted to a disposable debug emulator");
        check(store.getAllTasks().isEmpty() && store.getSessions().isEmpty() && !timer.engine().isActive(),
                "reminders suite requires a fresh empty disposable ledger, never existing user data");
        if (granted) check(timer.notificationsGranted(),
                "notification permission is granted before reminders suite (install the debug APK with adb install -g)");
        else assertRuntimeNotificationDenied();
        for (CompletionAlertMode mode : CompletionAlertMode.values())
            check(notifications.getNotificationChannel(mode.channelId()) == null,
                    "fresh fixture has no pre-existing mode channel: " + mode.channelId());
    }

    private void run() throws Exception {
        requireFreshFixture(true);
        if (!evidence.mkdirs() && !evidence.isDirectory()) throw new AssertionError("Cannot create test evidence folder");
        Map<String, ?> savedPreferences = new HashMap<>(preferences.getAll());
        try {
            preferenceAndUiChecks();
            ui.close();
            channelChecks();
            timerChecks();
            backgroundScreenOffCheck();
            check(store.getDaySnapshot(LocalDate.now()).items.get(0).progress == 40,
                    "all completion and recovery paths leave manual task progress at 40 percent");
            note("Software-only assertions: native settings, persisted data, Android channel configuration, "
                    + "posted notifications, and a screen-off/background fixture. No microphone, hearing, "
                    + "physical vibration, realme behavior, real process kill, or real reboot is claimed.");
        } finally {
            shell("input keyevent 224");
            context.stopService(new Intent(context, TimerService.class));
            notifications.cancel(1);
            notifications.cancel(2);
            restorePreferences(savedPreferences);
        }
        scope(Build.VERSION.SDK_INT >= 33
                ? "REMINDERS_PHASE_PASS phase=granted denial=separate-required suite=reminders_denied"
                : "REMINDERS_PHASE_PASS phase=legacy-combined");
    }

    private void assertRuntimeNotificationDenied() {
        check(Build.VERSION.SDK_INT >= 33, "runtime-denied suite requires Android 13 or later");
        check(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == android.content.pm.PackageManager.PERMISSION_DENIED,
                "POST_NOTIFICATIONS runtime permission is actually ungranted");
        check(!notifications.areNotificationsEnabled(), "Android reports notifications disabled for the ungranted app");
        check(!timer.notificationsGranted(), "production notification permission check agrees with the system denial");
    }

    /** Separate fresh installation: never changes permission while instrumentation is running. */
    private void runDenied() throws Exception {
        requireFreshFixture(false);
        if (!evidence.mkdirs() && !evidence.isDirectory()) throw new AssertionError("Cannot create denied test evidence folder");
        Map<String, ?> savedPreferences = new HashMap<>(preferences.getAll());
        try {
            task = store.saveSimpleTask("QA 未授予通知权限四种提醒", 40, 1);
            check(preferences.edit().putBoolean("reminders", true).commit(), "denied fixture keeps reminder master enabled");
            for (CompletionAlertMode mode : CompletionAlertMode.values()) {
                clearCompletion();
                ReminderSettings.setMode(context, mode);
                check(ReminderSettings.enabled(context) && ReminderSettings.mode(context) == mode,
                        "denial is tested with enabled reminders and the actual selected mode: " + mode);
                assertRuntimeNotificationDenied();
                TimerEngine.State denied = fixture(0);
                int count = store.getSessions().size();
                instrumentation.runOnMainSync(timer::refresh);
                assertTerminal(denied.sessionId, 900000, "expired", count + 1);
                assertNoCompletion("actual runtime notification denial suppresses completion while saving the record: " + mode);
                check(notifications.getActiveNotifications().length == 0,
                        "denied completion posts no fallback notification under any ID: " + mode);
                instrumentation.runOnMainSync(() -> {
                    timer.refresh();
                    TimerReceiver receiver = new TimerReceiver();
                    receiver.onReceive(context, new Intent(TimerReceiver.EXPIRE));
                    receiver.onReceive(context, new Intent(TimerReceiver.EXPIRE));
                });
                assertTerminal(denied.sessionId, 900000, "expired", count + 1);
                assertNoCompletion("repeated refresh and EXPIRE neither replay nor duplicate denied completion: " + mode);
                check(notifications.getActiveNotifications().length == 0,
                        "repeated denied completion still has no notification under any ID: " + mode);
                check(store.getDaySnapshot(LocalDate.now()).items.get(0).progress == 40,
                        "denied completion and repeated expiry preserve manual progress at 40 percent: " + mode);
                assertRuntimeNotificationDenied();
                note("RUNTIME_DENIED mode=" + mode + " channel=" + mode.channelId()
                        + " session=" + denied.sessionId + " actualMs=900000 records=" + (count + 1)
                        + " runtimePermission=DENIED notificationsEnabled=false activeNotifications=0 manualProgress=40");
            }
            check(store.getSessions().size() == CompletionAlertMode.values().length,
                    "four denied modes persist exactly four terminal sessions");
            note("Software-only fresh-install runtime-permission denial. This does not simulate clicking Deny, "
                    + "revocation of a previously granted permission, physical output, or realme behavior. "
                    + "Full API33+ reminder coverage also requires the separate granted reminders suite to pass.");
        } finally {
            context.stopService(new Intent(context, TimerService.class));
            notifications.cancel(1);
            notifications.cancel(2);
            restorePreferences(savedPreferences);
        }
        scope("REMINDERS_PHASE_PASS phase=runtime-denied modes=4");
    }

    private void scope(String message) throws Exception {
        note(message);
        android.os.Bundle update = new android.os.Bundle();
        update.putString("stream", message + "\n");
        instrumentation.sendStatus(0, update);
    }

    private void preferenceAndUiChecks() throws Exception {
        preferences.edit().remove("reminders").remove("sound").remove("vibration")
                .putString("qa-unrelated-preference", "keep exactly").commit();
        Map<String, ?> missing = new HashMap<>(preferences.getAll());
        check(ReminderSettings.enabled(context) && ReminderSettings.mode(context) == CompletionAlertMode.SOUND_AND_VIBRATION,
                "missing legacy preferences still default to enabled, sound=true and vibration=true");
        check(missing.equals(preferences.getAll()), "default preference reads do not materialize new keys");
        task = store.saveSimpleTask("QA 倒计时四种提醒", 40, 1);
        ui.openSettings();
        ui.assertSelection(CompletionAlertMode.SOUND_AND_VIBRATION, true);
        check(missing.equals(preferences.getAll()), "opening default settings does not write reminder preferences");
        String ledgerBefore = ledgerSnapshot();
        Map<String, ?> unrelatedUi = new HashMap<>(context.getSharedPreferences("shiben-v2-ui", Context.MODE_PRIVATE).getAll());
        for (boolean enabled : new boolean[]{false, true}) {
            for (CompletionAlertMode mode : CompletionAlertMode.values()) {
                check(preferences.edit().putBoolean("reminders", enabled).putBoolean("sound", mode.sound)
                                .putBoolean("vibration", mode.vibration).commit(), "seed exact old preference keys: " + enabled + "/" + mode);
                Map<String, ?> before = new HashMap<>(preferences.getAll());
                check(ReminderSettings.mode(context) == mode && ReminderSettings.enabled(context) == enabled,
                        "read legacy master/sound/vibration combination: " + enabled + "/" + mode);
                ui.backAndReopen();
                ui.assertSelection(mode, enabled);
                check(before.equals(preferences.getAll()), "rendering legacy combination is read-only: " + enabled + "/" + mode);
            }
        }
        // Selecting while off is important: mode choice must not silently turn reminders back on.
        preferences.edit().putBoolean("reminders", false).commit();
        ui.backAndReopen();
        for (CompletionAlertMode mode : CompletionAlertMode.values()) {
            Map<String, Object> expected = new HashMap<>(preferences.getAll());
            expected.put("sound", mode.sound); expected.put("vibration", mode.vibration);
            ui.select(mode);
            ui.assertSelection(mode, false);
            check(expected.equals(preferences.getAll()), "selecting " + mode + " writes only the two released keys");
            ui.recreate();
            ui.assertSelection(mode, false);
            ui.backAndReopen();
            ui.assertSelection(mode, false);
            check(expected.equals(preferences.getAll()), "selection survives recreation and back navigation: " + mode);
        }
        ui.toggleMaster();
        ui.assertSelection(CompletionAlertMode.SOUND_AND_VIBRATION, true);
        ui.select(CompletionAlertMode.SILENT);
        ui.toggleMaster(); ui.toggleMaster();
        ui.assertSelection(CompletionAlertMode.SILENT, true);
        check(ReminderSettings.mode(context) == CompletionAlertMode.SILENT,
                "switching reminder master off and on retains selected mode");
        check(ledgerBefore.equals(ledgerSnapshot()), "reading/selecting reminder modes does not mutate any ledger table");
        check(unrelatedUi.equals(context.getSharedPreferences("shiben-v2-ui", Context.MODE_PRIVATE).getAll()),
                "reminder selection leaves unrelated UI preferences untouched");
        ui.screenshot("70-reminder-four-modes");
    }

    private void channelChecks() throws Exception {
        NotificationChannel legacy = new NotificationChannel("focus_done", "QA existing legacy", NotificationManager.IMPORTANCE_LOW);
        legacy.setSound(null, null); legacy.enableVibration(false); legacy.setDescription("retained old channel");
        notifications.createNotificationChannel(legacy);
        Set<String> expectedIds = channelIds();
        expectedIds.add("focus_live");
        preferences.edit().putBoolean("reminders", true).commit();
        for (CompletionAlertMode mode : CompletionAlertMode.values()) {
            clearCompletion();
            ReminderSettings.setMode(context, mode);
            TimerEngine.State state = fixture(0);
            int count = store.getSessions().size();
            instrumentation.runOnMainSync(timer::refresh);
            StatusBarNotification posted = awaitCompletion();
            assertTerminal(state.sessionId, 900000, "expired", count + 1);
            Notification notification = posted.getNotification();
            check(mode.channelId().equals(notification.getChannelId()), "actual posted notification uses released channel: " + mode);
            NotificationChannel channel = notifications.getNotificationChannel(notification.getChannelId());
            check(channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_HIGH,
                    "new mode channel retains HIGH importance: " + mode);
            check(mode.sound ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).equals(channel.getSound())
                            : channel.getSound() == null,
                    "actual channel sound URI matches mode: " + mode);
            check(channel.shouldVibrate() == mode.vibration,
                    "actual channel vibration flag matches mode: " + mode);
            if (mode.vibration) check(Arrays.equals(channel.getVibrationPattern(), new long[]{0, 250, 150, 250}),
                    "actual vibration channel has the existing bounded pattern: " + mode);
            if (mode.sound) check(channel.getAudioAttributes() != null
                            && channel.getAudioAttributes().getUsage() == AudioAttributes.USAGE_NOTIFICATION,
                    "sound uses notification audio attributes: " + mode);
            check(TimerService.completionChannelWarning(context).isEmpty(), "fresh channel has no mismatch warning: " + mode);
            expectedIds.add(mode.channelId());
            check(expectedIds.equals(channelIds()), "mode selection creates no replacement channel IDs: " + mode);
            note("POSTED " + mode + " channel=" + notification.getChannelId() + " sound=" + channel.getSound()
                    + " vibrate=" + channel.shouldVibrate() + " pattern=" + Arrays.toString(channel.getVibrationPattern()));
        }
        NotificationChannel retained = notifications.getNotificationChannel("focus_done");
        check(retained != null && retained.getSound() == null && !retained.shouldVibrate()
                        && retained.getImportance() == NotificationManager.IMPORTANCE_LOW
                        && "retained old channel".equals(retained.getDescription()),
                "legacy focus_done is retained with existing configuration, never deleted or recreated");
        clearCompletion();
        preferences.edit().putBoolean("reminders", false).commit();
        TimerEngine.State disabled = fixture(0);
        int disabledCount = store.getSessions().size();
        instrumentation.runOnMainSync(timer::refresh);
        assertTerminal(disabled.sessionId, 900000, "expired", disabledCount + 1);
        assertNoCompletion("disabled reminder master suppresses completion notification but preserves the record");
        preferences.edit().putBoolean("reminders", true).commit();
        if (Build.VERSION.SDK_INT >= 33) {
            scope("REMINDERS_COVERAGE runtime-denial=not-covered-here required-suite=reminders_denied fresh-install=without-g");
        } else {
            try {
                shell("cmd appops set " + context.getPackageName() + " POST_NOTIFICATION ignore");
                await(() -> !timer.notificationsGranted(), 10000, "system notification denial to take effect");
                TimerEngine.State denied = fixture(0);
                int deniedCount = store.getSessions().size();
                instrumentation.runOnMainSync(timer::refresh);
                assertTerminal(denied.sessionId, 900000, "expired", deniedCount + 1);
                assertNoCompletion("actual system notification denial suppresses the alert while saving completion");
            } finally {
                shell("cmd appops set " + context.getPackageName() + " POST_NOTIFICATION allow");
                await(timer::notificationsGranted, 10000, "notification grant restoration");
            }
        }
        // Android permits an app to lower an existing channel's importance. This is
        // an existing-system-configuration fixture, not a claim of physical output
        // or of automating the user's Settings application.
        ReminderSettings.setMode(context, CompletionAlertMode.SOUND);
        NotificationChannel customized = notifications.getNotificationChannel(CompletionAlertMode.SOUND.channelId());
        customized.setImportance(NotificationManager.IMPORTANCE_LOW);
        notifications.createNotificationChannel(customized);
        check(notifications.getNotificationChannel(customized.getId()).getImportance() == NotificationManager.IMPORTANCE_LOW,
                "test fixture establishes a persisted customized system-channel importance");
        TimerService.completionChannel(context);
        check(notifications.getNotificationChannel(customized.getId()).getImportance() == NotificationManager.IMPORTANCE_LOW,
                "reselecting a mode does not override existing lower system-channel importance");
        check(!TimerService.completionChannelWarning(context).isEmpty(), "existing system override produces a visible-settings warning");
        check(expectedIds.equals(channelIds()), "preserving an override does not bypass it with a new channel ID");
    }

    private void timerChecks() throws Exception {
        ReminderSettings.setMode(context, CompletionAlertMode.SILENT);
        preferences.edit().putBoolean("reminders", true).commit();
        clearCompletion();
        TimerEngine.State expired = fixture(0);
        int before = store.getSessions().size();
        instrumentation.runOnMainSync(timer::refresh);
        awaitCompletion();
        assertTerminal(expired.sessionId, 900000, "expired", before + 1);
        clearCompletion();
        instrumentation.runOnMainSync(() -> {
            timer.refresh(); timer.checkpoint(); timer.pause(); timer.resume(); timer.finish();
            TimerReceiver receiver = new TimerReceiver();
            for (String action : new String[]{TimerReceiver.EXPIRE, TimerReceiver.EXPIRE, TimerReceiver.PAUSE,
                    TimerReceiver.RESUME, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED})
                receiver.onReceive(context, new Intent(action));
        });
        assertTerminal(expired.sessionId, 900000, "expired", before + 1);
        assertNoCompletion("refresh, repeated EXPIRE, pause/resume/finish and clock broadcasts do not replay completion");

        TimerEngine.State early = fixture(60000);
        before = store.getSessions().size();
        instrumentation.runOnMainSync(timer::finish);
        Session saved = store.getSession(early.sessionId);
        check(saved != null && "early_end".equals(saved.reason) && saved.actualMs < 900000
                        && store.getSessions().size() == before + 1,
                "early finish saves actual duration once with early_end reason");
        assertNoCompletion("early manual finish never posts an ending alert");

        TimerEngine.State paused = fixture(30000);
        instrumentation.runOnMainSync(timer::schedule);
        check(hasScheduledExpiry("before-pause"), "accelerated running fixture has an actual scheduled expiry alarm");
        instrumentation.runOnMainSync(timer::pause);
        long pausedMs = timer.actualMs();
        check(timer.engine().isPaused() && store.loadTimer().paused, "pause is durable");
        check(!hasScheduledExpiry("after-pause"), "pause removes the actual pending expiry alarm");
        SystemClock.sleep(31000);
        instrumentation.runOnMainSync(() -> new TimerReceiver().onReceive(context, new Intent(TimerReceiver.EXPIRE)));
        check(timer.engine().isPaused() && timer.actualMs() == pausedMs && store.getSession(paused.sessionId) == null,
                "paused time and a stale alarm cannot expire or accrue the session");
        assertNoCompletion("pause and stale EXPIRE produce no completion alert");
        instrumentation.runOnMainSync(timer::resume);
        check(hasScheduledExpiry("after-resume"), "resume installs a new actual expiry alarm");
        await(() -> { timer.refresh(); return !timer.engine().isActive(); }, 45000, "resumed expiration");
        awaitCompletion();
        assertTerminal(paused.sessionId, 900000, "expired", before + 2);
        context.stopService(new Intent(context, TimerService.class));
        clearCompletion();

        // Disk round-trip is a process-recreation simulation, not an actual kill.
        TimerEngine.State restored = fixture(1000);
        setEngine(new TimerEngine(store.loadTimer()));
        SystemClock.sleep(1200);
        instrumentation.runOnMainSync(timer::refresh);
        awaitCompletion();
        assertTerminal(restored.sessionId, 900000, "expired", before + 3);
        clearCompletion();
        setEngine(new TimerEngine(store.loadTimer()));
        instrumentation.runOnMainSync(timer::refresh);
        assertNoCompletion("terminal persisted state survives engine reconstruction without a second alert");

        TimerEngine.State reboot = fixture(5000);
        reboot.bootId = "qa-different-boot";
        store.persistTimer(reboot, null); setEngine(new TimerEngine(store.loadTimer()));
        instrumentation.runOnMainSync(() -> new TimerReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED)));
        awaitCompletion();
        assertTerminal(reboot.sessionId, reboot.accumulatedMs, "interrupted_restart", before + 4);
        clearCompletion();
        instrumentation.runOnMainSync(() -> new TimerReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED)));
        assertNoCompletion("simulated changed boot ID keeps only the saved checkpoint and never replays recovery");
        faultInjectionCheck();
    }

    private void faultInjectionCheck() throws Exception {
        TimerEngine.State expired = fixture(0);
        int count = store.getSessions().size();
        Field field = TimerCoordinator.class.getDeclaredField("context"); field.setAccessible(true);
        Context original = (Context) field.get(timer);
        final int[] stops = {0};
        ContextWrapper broken = new ContextWrapper(original) {
            @Override public Object getSystemService(String name) {
                if (Context.NOTIFICATION_SERVICE.equals(name)) throw new SecurityException("QA denied notification side effect");
                return super.getSystemService(name);
            }
            @Override public boolean stopService(Intent service) {
                stops[0]++;
                throw new IllegalStateException("QA denied service cleanup");
            }
        };
        try {
            field.set(timer, broken);
            instrumentation.runOnMainSync(timer::refresh);
            assertTerminal(expired.sessionId, 900000, "expired", count + 1);
            check(stops[0] == 1 && !timer.warning().isEmpty(),
                    "notification failure still attempts service cleanup and reports a warning");
        } finally { field.set(timer, original); }
        instrumentation.runOnMainSync(timer::refresh);
        assertTerminal(expired.sessionId, 900000, "expired", count + 1);
        assertNoCompletion("notification/cleanup exceptions never roll back a durable completion or replay it later");
    }

    private void backgroundScreenOffCheck() throws Exception {
        clearCompletion();
        ReminderSettings.setMode(context, CompletionAlertMode.SOUND_AND_VIBRATION);
        ui.foreground();
        note("BACKGROUND sdk=" + Build.VERSION.SDK_INT + " exactAlarmGranted=" + timer.exactAlarmGranted()
                + " notificationsGranted=" + timer.notificationsGranted());
        TimerEngine.State state = fixture(20000);
        int count = store.getSessions().size();
        instrumentation.runOnMainSync(timer::restoreFromForeground);
        ui.background();
        shell("input keyevent 223");
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        await(() -> !power.isInteractive(), 10000, "emulator display to turn off");
        check(!power.isInteractive() && store.getSession(state.sessionId) == null,
                "accelerated background fixture becomes screen-off before its completion");
        // Do not call refresh in this wait: the alarm/service must deliver in background.
        await(() -> store.getSession(state.sessionId) != null, 65000, "background alarm or service completion");
        StatusBarNotification posted = awaitCompletion();
        check(CompletionAlertMode.SOUND_AND_VIBRATION.channelId().equals(posted.getNotification().getChannelId()),
                "screen-off/background completion posts the selected combined channel without foreground reconciliation");
        assertTerminal(state.sessionId, 900000, "expired", count + 1);
        clearCompletion();
        shell("input keyevent 224");
        ui.foreground();
        instrumentation.runOnMainSync(timer::restoreFromForeground);
        assertNoCompletion("returning from screen-off completion does not replay its notification");
        ui.close();
    }

    /** Valid production 15-minute state, accelerated exclusively in the test APK. */
    private TimerEngine.State fixture(long remaining) throws Exception {
        check(!timer.engine().isActive(), "next accelerated fixture starts only after prior timer is terminal");
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        String boot = "boot-" + Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        TimerEngine engine = new TimerEngine();
        engine.start(task.id, task.title, task.projectId, 15, wall, elapsed, boot);
        TimerEngine.State state = engine.snapshot();
        state.accumulatedMs = state.plannedMs - remaining;
        state.startedWallMs = wall - state.accumulatedMs;
        state.segments = new ArrayList<>();
        if (state.accumulatedMs > 0) state.segments.add(new Segment(state.startedWallMs, wall, state.accumulatedMs));
        state.segmentStartWallMs = state.checkpointWallMs = wall;
        state.segmentStartElapsedMs = state.checkpointElapsedMs = elapsed;
        store.persistTimer(state, null);
        setEngine(new TimerEngine(state));
        return state;
    }

    private void setEngine(TimerEngine replacement) throws Exception {
        Field field = TimerCoordinator.class.getDeclaredField("engine"); field.setAccessible(true);
        synchronized (timer) { field.set(timer, replacement); }
    }

    private void assertTerminal(String id, long actual, String reason, int count) {
        Session session = store.getSession(id);
        check(session != null && session.actualMs == actual && reason.equals(session.reason)
                        && store.getSessions().size() == count && !timer.engine().isActive() && store.loadTimer() == null,
                "durable terminal record once: " + reason + ", actual=" + actual + ", totalRecords=" + count);
    }

    private Set<String> channelIds() {
        Set<String> ids = new HashSet<>();
        for (NotificationChannel channel : notifications.getNotificationChannels()) ids.add(channel.getId());
        return ids;
    }

    private StatusBarNotification completion() {
        for (StatusBarNotification notification : notifications.getActiveNotifications())
            if (notification.getId() == 2 && notification.getTag() == null) return notification;
        return null;
    }

    private StatusBarNotification awaitCompletion() {
        await(() -> completion() != null, 10000, "actual completion notification to be posted");
        return completion();
    }

    private void clearCompletion() {
        notifications.cancel(2);
        await(() -> completion() == null, 10000, "previous test completion to be cancelled");
        // Avoid platform rapid-update throttling across independent notification fixtures.
        SystemClock.sleep(250);
    }

    private void assertNoCompletion(String name) {
        SystemClock.sleep(600);
        check(completion() == null, name);
    }

    private void await(BooleanSupplier condition, long timeout, String description) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        if (!condition.getAsBoolean()) throw new AssertionError("Timed out waiting for " + description);
    }

    private boolean hasScheduledExpiry(String name) throws Exception {
        String dump = shell("dumpsys alarm");
        Files.write(new File(evidence, "alarms-" + name + ".txt").toPath(), dump.getBytes(StandardCharsets.UTF_8));
        // Only inspect currently pending alarm lists, not historical delivery counts.
        int begin = dump.indexOf("Pending alarms:");
        if (begin < 0) begin = dump.indexOf("Pending alarm batches:");
        if (begin < 0) {
            // API35 LazyAlarmStore prefixes the active list with its item count.
            Matcher counted = Pattern.compile("(?m)^\\h*\\d+ pending alarms:\\h*$").matcher(dump);
            if (counted.find()) begin = counted.start();
        }
        if (begin < 0) throw new AssertionError("Unsupported dumpsys alarm active-list format; see alarms-" + name + ".txt");
        String active = dump.substring(begin);
        for (String heading : new String[]{"Pending user blocked", "Pending background", "Pending non-wakeup",
                "Past-due non-wakeup", "Alarm Stats:", "Top Alarms:", "Recent problems", "Broadcast ref count"}) {
            int end = active.indexOf(heading);
            if (end >= 0) active = active.substring(0, end);
        }
        String[] lines = active.split("\\n");
        Pattern alarm = Pattern.compile("^(\\s*)(?:ELAPSED_WAKEUP|ELAPSED_REALTIME_WAKEUP|RTC_WAKEUP|RTC|ELAPSED) #\\d+:.*$");
        for (int i = 0; i < lines.length; i++) {
            Matcher match = alarm.matcher(lines[i]);
            if (!match.matches()) continue;
            int indent = match.group(1).length();
            StringBuilder block = new StringBuilder(lines[i]);
            for (int next = i + 1; next < lines.length; next++) {
                if (lines[next].trim().isEmpty()) continue;
                int padding = 0;
                while (padding < lines[next].length() && Character.isWhitespace(lines[next].charAt(padding))) padding++;
                if (padding <= indent) break;
                block.append('\n').append(lines[next]);
            }
            if (block.indexOf(context.getPackageName()) >= 0 && block.indexOf(TimerReceiver.EXPIRE) >= 0) return true;
        }
        return false;
    }

    private String ledgerSnapshot() {
        StringBuilder snapshot = new StringBuilder();
        try (Cursor tables = store.getReadableDatabase().rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
            while (tables.moveToNext()) {
                String table = tables.getString(0); snapshot.append(table).append('\n');
                List<String> rows = new ArrayList<>();
                try (Cursor row = store.getReadableDatabase().rawQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"", null)) {
                    while (row.moveToNext()) {
                        StringBuilder values = new StringBuilder();
                        for (int i = 0; i < row.getColumnCount(); i++) {
                            values.append(row.getColumnName(i)).append('=');
                            if (row.getType(i) == Cursor.FIELD_TYPE_BLOB)
                                values.append(android.util.Base64.encodeToString(row.getBlob(i), android.util.Base64.NO_WRAP));
                            else values.append(row.isNull(i) ? "<NULL>" : row.getString(i));
                            values.append('\u001f');
                        }
                        rows.add(values.toString());
                    }
                }
                java.util.Collections.sort(rows); snapshot.append(rows).append('\n');
            }
        }
        return snapshot.toString();
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            String text = output.toString(StandardCharsets.UTF_8.name());
            if (text.contains("Exception") || text.contains("Error:")) throw new AssertionError(command + ": " + text);
            return text;
        }
    }

    private void note(String text) throws Exception {
        Files.write(new File(evidence, "software-evidence.txt").toPath(), (text + "\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    @SuppressWarnings("unchecked")
    private void restorePreferences(Map<String, ?> values) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
            else throw new AssertionError("Unknown preference type: " + key);
        }
        check(edit.commit(), "test restores exact pre-suite reminder preferences");
    }
}
