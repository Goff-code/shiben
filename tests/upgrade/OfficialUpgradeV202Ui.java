package cn.focusledger.qa;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import com.android.uiautomator.core.UiDevice;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiObjectNotFoundException;
import com.android.uiautomator.core.UiScrollable;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Black-box code4 (2.0.1) to code5 (2.0.2) UI test for the original signed app. This is NOT an
 * instrumentation APK and does not read app-private data, sign, install,
 * uninstall, clear data, force-stop, or reset an existing timer.
 *
 * Run on an authorized disposable emulator ONLY, with the official code4 APK
 * already installed. Seed performs ordinary UI edits and leaves a paused timer.
 * Upgrade externally with the official same-certificate code5 APK, then verify.
 *
 * adb push artifacts/v2.0.2/runtime/official-ui-v202-qa.jar /data/local/tmp/
 * adb shell uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/official-ui-v202-qa.jar \
 *   -c cn.focusledger.qa.OfficialUpgradeV202Ui -e mode seed
 * adb shell uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/official-ui-v202-qa.jar \
 *   -c cn.focusledger.qa.OfficialUpgradeV202Ui -e mode verify
 * adb pull /data/local/tmp/shiben-official-upgrade-v202
 *
 * Optional parameters: dir (evidence directory), baseline (properties path),
 * alpha / beta (distinct ASCII task names), session_ms (2000..120000).
 * resume_created_tasks=true is only for an evidenced interrupted seed with
 * exactly the original two zero-progress tasks, no session/timer, no baseline.
 * Seed refuses to overwrite a completed baseline or reuse its task names.
 * Verify only navigates/reads: it never resumes/ends a timer or changes settings.
 * Both phases must run on the same device and same local calendar day.
 * APK version/signature and SQLite/prefs/archive comparisons are external checks.
 * Raw timer/session milliseconds must be compared exactly by those checks; the
 * one-second bound here applies only to summed, already-floored public UI text.
 */
@SuppressWarnings("deprecation")
public final class OfficialUpgradeV202Ui extends UiAutomatorTestCase {
    private static final String PACKAGE = "cn.goff.shiben";
    private static final long UI_TIMEOUT_MS = 30000L;
    private static final String COMPONENT = PACKAGE + "/cn.focusledger.MainActivity";
    private static final Pattern PROGRESS = Pattern.compile("，进度 (\\d+)%");
    private UiDevice device;
    private File directory;
    private File baselineFile;
    private PrintWriter log;
    private String mode;
    private String alpha;
    private String beta;
    private String runId;
    private final Properties baseline = new Properties();
    private final Properties observed = new Properties();

    public void testOfficialUpgrade() throws Exception {
        Bundle parameters = getParams();
        mode = parameters.getString("mode", "");
        assertTrue("Explicit -e mode seed or -e mode verify is required",
                "seed".equals(mode) || "verify".equals(mode));
        device = getUiDevice();
        device.setCompressedLayoutHeirarchy(false);
        directory = new File(parameters.getString("dir", "/data/local/tmp/shiben-official-upgrade-v202"));
        assertTrue("Cannot create evidence directory " + directory,
                directory.isDirectory() || directory.mkdirs());
        baselineFile = new File(parameters.getString("baseline", new File(directory, "baseline.properties").getPath()));
        runId = mode + "-" + System.currentTimeMillis();
        log = new PrintWriter(new FileOutputStream(new File(directory, runId + ".log")), true);
        try {
            note("BEGIN " + mode + "; only public UI and shell activity launch are used");
            if ("seed".equals(mode)) {
                assertFalse("Baseline already exists; refusing to replace it: " + baselineFile,
                        baselineFile.exists());
                alpha = parameters.getString("alpha", "Upgrade202Alpha");
                beta = parameters.getString("beta", "Upgrade202Beta");
                assertTrue("Use distinct ASCII names of 1..30 characters",
                        alpha.matches("[A-Za-z][A-Za-z0-9_-]{0,29}")
                        && beta.matches("[A-Za-z][A-Za-z0-9_-]{0,29}") && !alpha.equals(beta));
                seed(parameters);
            } else {
                assertTrue("Missing seed baseline: " + baselineFile, baselineFile.isFile());
                FileInputStream in = new FileInputStream(baselineFile);
                try { baseline.load(in); } finally { in.close(); }
                assertEquals("Unexpected code4-to-code5 baseline schema", "202", baseline.getProperty("schema"));
                alpha = required(baseline, "alpha");
                beta = required(baseline, "beta");
                assertEquals("Source version contract", "4", required(baseline, "sourceVersionCode"));
                assertEquals("Target version contract", "5", required(baseline, "targetVersionCode"));
                assertEquals("UI daily progress resets at local midnight; use the same day", required(baseline, "day"), day());
                verify();
            }
            store(observed, new File(directory, runId + "-observed.properties"));
            note("PASS " + mode + "; evidence: " + directory + "; baseline: " + baselineFile);
        } catch (Throwable failure) {
            note("FAIL " + mode + ": " + failure);
            failure.printStackTrace(log);
            try { capture("failure"); } catch (Throwable evidenceFailure) {
                note("Failure capture also failed: " + evidenceFailure);
            }
            store(observed, new File(directory, runId + "-partial.properties"));
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new RuntimeException(failure);
        } finally {
            log.close();
        }
    }

    private void seed(Bundle parameters) throws Exception {
        observed.setProperty("schema", "202");
        observed.setProperty("sourceVersionCode", "4");
        observed.setProperty("targetVersionCode", "5");
        observed.setProperty("day", day());
        observed.setProperty("alpha", alpha);
        observed.setProperty("beta", beta);
        launch();
        tab("专注");
        assertFalse("An existing timer is active; refusing to change it", object(text("暂停")).exists());
        assertFalse("An existing timer is paused; refusing to change it", object(text("已暂停")).exists());
        tab("任务");
        if (Boolean.parseBoolean(parameters.getString("resume_created_tasks", "false"))) {
            // Explicit recovery only after evidence proves the preceding attempt
            // stopped immediately after creating these two zero-progress tasks.
            assertEquals("Resume requires the existing alpha task at zero", 0,
                    progress(must(taskSelector(alpha)).getContentDescription()));
            assertEquals("Resume requires the existing beta task at zero", 0,
                    progress(must(taskSelector(beta)).getContentDescription()));
            note("Resuming the two existing zero-progress seed tasks without deleting or recreating them");
        } else {
            assertFalse("Seed task already exists: " + alpha, existsWithScroll(taskSelector(alpha)));
            assertFalse("Seed task already exists: " + beta, existsWithScroll(taskSelector(beta)));
            addTask(alpha);
            addTask(beta);
        }
        tap(taskSelector(alpha));
        must(text("调整任务进度"));
        UiObject seek = must(new UiSelector().packageName(PACKAGE).className("android.widget.SeekBar"));
        Rect bounds = seek.getVisibleBounds();
        assertTrue("Progress slider must have usable width", bounds.width() > 40);
        // A real touchscreen adjustment. Exact resulting percentage is saved,
        // rather than assuming pixel-to-progress rounding/padding across devices.
        assertTrue("Could not touch progress slider", device.click(bounds.left + (int) (bounds.width() * .37), bounds.centerY()));
        settle();
        capture("manual-progress-dialog");
        tap(text("保存进度"));
        String alphaDescription = must(taskSelector(alpha)).getContentDescription();
        int percentage = progress(alphaDescription);
        assertTrue("Manual progress must be non-zero and non-complete: " + alphaDescription,
                percentage > 0 && percentage < 100);
        observed.setProperty("alphaDescription", alphaDescription);
        observed.setProperty("betaDescription", must(taskSelector(beta)).getContentDescription());
        assertEquals("New beta task starts at zero", 0, progress(observed.getProperty("betaDescription")));
        capture("tasks");

        settings();
        setSwitchOff("声音");
        setSwitchOff("振动");
        readSettings();
        capture("settings");

        tab("专注");
        chooseTask(alpha);
        tap(text("开始专注"));
        must(text("专注中"));
        long sessionMs = Long.parseLong(parameters.getString("session_ms", "6500"));
        assertTrue("session_ms must be 2000..120000", sessionMs >= 2000 && sessionMs <= 120000);
        sleep(sessionMs);
        must(new UiSelector().packageName(PACKAGE).textStartsWith("已专注 "));
        capture("first-session-running");
        tap(text("结束"));
        must(text("结束本次专注？"));
        capture("end-confirmation");
        tap(text("结束并保存"));
        must(text("开始专注"));
        note("Saved a real early-ended timer session for " + alpha);
        todayAndHistory();

        tab("专注");
        chooseTask(beta);
        tap(text("开始专注"));
        must(text("专注中"));
        sleep(5500);
        tap(text("暂停"));
        readPaused();
        capture("paused");
        sleep(2200);
        assertPausedMatches(observed);
        assertEquals("Seed crossed local midnight; daily comparison is no longer valid", observed.getProperty("day"), day());
        store(observed, baselineFile);
        note("Seed baseline saved; leave the timer paused and perform the signed APK upgrade externally");
    }

    private void verify() throws Exception {
        launch();
        tab("专注");
        readPaused();
        assertPausedMatches(baseline);
        capture("paused-restored");
        sleep(2200);
        assertPausedMatches(baseline);
        tab("任务");
        compare("alphaDescription", must(taskSelector(alpha)).getContentDescription());
        compare("betaDescription", must(taskSelector(beta)).getContentDescription());
        capture("tasks-preserved");
        todayAndHistory();
        settings();
        readSettings();
        capture("settings-preserved");
        tab("专注");
        assertPausedMatches(baseline);
        capture("paused-final");
        assertEquals("Verify crossed local midnight; daily comparison is no longer valid", required(baseline, "day"), day());
        note("Task names/progress, today progress, completed-only history summary, settings and paused values are preserved; new detail attention equals completed plus paused time within the one-second floor-sum bound");
    }

    private void addTask(String title) throws Exception {
        tab("任务");
        tap(description("新增任务"));
        UiObject field = must(new UiSelector().packageName(PACKAGE).className("android.widget.EditText"));
        assertTrue("Could not type ASCII task name", field.setText(title));
        assertEquals("Task name entry differs", title, field.getText());
        // Enter hides the single-line editor IME without navigating out of the form.
        device.pressEnter();
        settle();
        tap(text("保存任务"));
        must(taskSelector(title));
        note("Created task via UI: " + title);
    }

    private void chooseTask(String title) throws Exception {
        tap(new UiSelector().packageName(PACKAGE).descriptionStartsWith("任务，"));
        must(text("选择任务"));
        tap(new UiSelector().packageName(PACKAGE).textStartsWith(title + "   "));
        tap(text("确定"));
        must(description("任务，" + title));
    }

    private void settings() throws Exception {
        tab("今日");
        tap(description("更多"));
        tap(description("提醒设置"));
        must(text("提醒设置"));
    }

    private void setSwitchOff(String label) throws Exception {
        UiSelector selector = description(label).className("android.widget.Switch");
        UiObject control = must(selector);
        if (control.isChecked()) {
            dispatchClick(control, "toggle " + label);
            settle();
        }
        assertFalse(label + " must be disabled", must(selector).isChecked());
    }

    private void readSettings() throws Exception {
        for (String label : new String[]{"声音", "振动"}) {
            boolean checked = must(description(label).className("android.widget.Switch")).isChecked();
            assertFalse(label + " must remain disabled", checked);
            recordOrCompare(label.equals("声音") ? "sound" : "vibration", Boolean.toString(checked));
        }
    }

    private void todayAndHistory() throws Exception {
        tab("今日");
        must(text("今日进度"));
        // Both code4 and code5 expose the same computed percentage.
        // Compare its exact displayed decimal precision independently of labels.
        String todayDescription = must(new UiSelector().packageName(PACKAGE)
                .descriptionMatches("[0-9]+(\\.[0-9]+)?%(，今日已完成)?")).getContentDescription();
        recordOrCompare("todayPercent", todayDescription.split("，", 2)[0]);
        must(text(alpha));
        must(text(beta));
        capture("today");
        tap(description("更多"));
        tap(description("历史记录"));
        // History cards deliberately show completed sessions only in both versions.
        // Canonicalize the code4 专注 label to the code5 注意力 label, retaining
        // the exact task count and completed duration before reading detail.
        UiObject dateLabel = must(text(historyDate()));
        recordOrCompare("historyDate", dateLabel.getText());
        recordOrCompare("historyProgress", must(new UiSelector().packageName(PACKAGE)
                .textStartsWith("当日进度  ")).getText());
        UiObject summary = must(new UiSelector().packageName(PACKAGE)
                .textMatches("[0-9]+ 个任务 · (专注|注意力) .*"));
        String summaryText = summary.getText();
        observed.setProperty("historySummaryRaw", summaryText);
        recordOrCompare("historySummaryCanonical", canonicalHistorySummary(summaryText));
        long completedSeconds = durationSeconds(summaryText);
        recordOrCompare("historyCompletedSeconds", Long.toString(completedSeconds));
        assertTrue("Recorded real timer session must have nonzero actual duration", completedSeconds > 0);
        capture("history-list");
        // Tap the visible date's coordinates: the enclosing card owns the click
        // listener in both versions, while the TextView itself is not clickable.
        Rect dateBounds = must(text(historyDate())).getVisibleBounds();
        assertTrue("Could not touch today's history card",
                device.click(dateBounds.centerX(), dateBounds.centerY()));
        settle();
        must(new UiSelector().packageName(PACKAGE)
                .descriptionMatches("[0-9]+(\\.[0-9]+)?%，(" + day() + "|当日进度)"));
        capture("history-detail-top");
        must(text(alpha));
        must(text(beta));
        String actualText = must(new UiSelector().packageName(PACKAGE)
                .textMatches("实际(专注|注意力)合计  .*" )).getText();
        observed.setProperty("historyActualRaw", actualText);
        long actualSeconds = durationSeconds(actualText);
        if ("seed".equals(mode)) {
            // This read occurs before the second timer starts. It is the completed
            // ledger total, not an estimate containing any paused live time.
            assertEquals("Code4 detail must equal its completed-only history summary", completedSeconds, actualSeconds);
            observed.setProperty("historyDetailCompletedSeconds", Long.toString(actualSeconds));
        } else {
            assertTrue("Code5 detail must use the new attention label", actualText.startsWith("实际注意力合计  "));
            long completed = Long.parseLong(required(baseline, "historyDetailCompletedSeconds"));
            long paused = durationSeconds(required(baseline, "pausedElapsed"));
            long lowerBound = Math.addExact(completed, paused);
            // UI duration values floor to seconds. floor(a+b) differs from
            // floor(a)+floor(b) only by 0 or 1, never an arbitrary tolerance.
            assertTrue("Code5 detail attention must equal completed + paused seconds (only a one-second floor-sum difference allowed): completed="
                    + completed + ", paused=" + paused + ", actual=" + actualSeconds,
                    actualSeconds == lowerBound || actualSeconds == lowerBound + 1);
            observed.setProperty("expectedAttentionSecondsLower", Long.toString(lowerBound));
            observed.setProperty("observedAttentionSeconds", Long.toString(actualSeconds));
        }
        capture("history-detail-bottom");
    }

    private String canonicalHistorySummary(String value) {
        Matcher count = Pattern.compile("^([0-9]+) 个任务 · (专注|注意力) ").matcher(value);
        assertTrue("Unrecognized public history summary: " + value, count.find());
        return count.group(1) + " 个任务 · 注意力 " + durationSeconds(value) + " 秒";
    }

    private long durationSeconds(String value) {
        Matcher duration = Pattern.compile("([0-9]+)\\s*分\\s*([0-9]+)\\s*秒\\s*$").matcher(value);
        assertTrue("Unrecognized displayed minute/second duration: " + value, duration.find());
        long minutes = Long.parseLong(duration.group(1));
        long seconds = Long.parseLong(duration.group(2));
        assertTrue("Displayed seconds must be 0..59: " + value, seconds >= 0 && seconds <= 59);
        return Math.addExact(Math.multiplyExact(minutes, 60L), seconds);
    }

    private String historyDate() {
        return new SimpleDateFormat("yyyy年 M月d日", Locale.ROOT).format(new Date());
    }

    private void readPaused() throws Exception {
        must(text("已暂停"));
        must(text("继续专注"));
        must(text(beta));
        assertFalse("Pause UI must not offer the active Pause button", object(text("暂停")).exists());
        observed.setProperty("pausedTask", beta);
        observed.setProperty("pausedTitle", "已暂停");
        observed.setProperty("pausedElapsed", must(new UiSelector().packageName(PACKAGE).textStartsWith("已专注 ")).getText());
        observed.setProperty("pausedRemaining", must(new UiSelector().packageName(PACKAGE).descriptionMatches("[0-9]+:[0-9]{2}(:[0-9]{2})?")).getContentDescription());
    }

    private void assertPausedMatches(Properties expected) throws Exception {
        must(text(required(expected, "pausedTitle")));
        must(text("继续专注"));
        must(text(required(expected, "pausedTask")));
        assertEquals("Paused elapsed display changed", required(expected, "pausedElapsed"),
                must(new UiSelector().packageName(PACKAGE).textStartsWith("已专注 ")).getText());
        assertEquals("Paused remaining display changed", required(expected, "pausedRemaining"),
                must(new UiSelector().packageName(PACKAGE).descriptionMatches("[0-9]+:[0-9]{2}(:[0-9]{2})?")).getContentDescription());
        assertFalse("Paused timer unexpectedly resumed", object(text("暂停")).exists());
    }

    private void launch() throws Exception {
        device.wakeUp();
        Process process = new ProcessBuilder("am", "start", "-W", "-n", COMPONENT).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        copy(process.getInputStream(), bytes);
        int result = process.waitFor();
        String output = bytes.toString("UTF-8");
        note("Activity launch exit=" + result + "\n" + output);
        assertEquals("Activity launch failed: " + output, 0, result);
        settle();
        waitForAny("App did not become visible within 30 seconds; dismiss device lock/setup externally",
                description("专注"), description("专注，已选中"));
    }

    private void tab(String name) throws Exception {
        UiObject target = waitForAny("Missing exact tab description after 30 seconds: " + name,
                description(name), description(name + "，已选中"));
        dispatchClick(target, "tab " + name);
        settle();
        must(description(name + "，已选中"));
    }

    private UiSelector taskSelector(String title) {
        return new UiSelector().packageName(PACKAGE).descriptionStartsWith(title + "，进度 ");
    }

    private UiSelector text(String value) { return new UiSelector().packageName(PACKAGE).text(value); }
    private UiSelector description(String value) { return new UiSelector().packageName(PACKAGE).description(value); }
    private UiObject object(UiSelector selector) { return new UiObject(selector); }

    private boolean existsWithScroll(UiSelector selector) throws UiObjectNotFoundException {
        if (object(selector).exists()) return true;
        UiScrollable scroll = new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true));
        if (!scroll.exists()) return false;
        scroll.setAsVerticalList().setMaxSearchSwipes(15);
        return scroll.scrollIntoView(selector) && object(selector).exists();
    }

    private UiObject must(UiSelector selector) throws UiObjectNotFoundException {
        long deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS;
        boolean searchedScroll = false;
        do {
            recoverSystemUiAnr();
            UiObject found = object(selector);
            if (found.waitForExists(300)) return found;
            UiScrollable scroll = new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true));
            if (!searchedScroll && scroll.exists()) {
                searchedScroll = true;
                if (existsWithScroll(selector)) return object(selector);
            }
            sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Missing UI element after 30-second wait (including launch/IME readiness): " + selector);
        return object(selector); // Unreachable: fail() throws an assertion failure.
    }

    private UiObject waitForAny(String failure, UiSelector... selectors) throws UiObjectNotFoundException {
        long deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS;
        do {
            recoverSystemUiAnr();
            for (UiSelector selector : selectors) {
                UiObject found = object(selector);
                if (found.exists()) return found;
            }
            sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(failure);
        return null; // Unreachable: assertions are never silently skipped.
    }

    private void tap(UiSelector selector) throws Exception {
        dispatchClick(must(selector), selector.toString());
        settle();
    }

    private void dispatchClick(UiObject target, String description) throws UiObjectNotFoundException {
        // Android 35's legacy driver can return false after the tap has already
        // opened/dismissed a window. The following mandatory UI-state assertions
        // establish success; a dispatch return value does not establish it.
        if (!target.click()) note("DRIVER: click returned false; requiring subsequent UI state for " + description);
    }

    private void settle() throws UiObjectNotFoundException {
        recoverSystemUiAnr();
        // Timer accessibility events continue every second; never wait indefinitely
        // for global idle on the software-rendered emulator.
        device.waitForIdle(500);
        sleep(350);
        recoverSystemUiAnr();
    }

    private void recoverSystemUiAnr() throws UiObjectNotFoundException {
        // Exact known environment error only. Never dismiss a 时本/app ANR,
        // crash, generic error, permission prompt, or a similarly worded message.
        UiObject title = object(new UiSelector().packageNameMatches("android|com.android.systemui")
                .className("android.widget.TextView")
                .textMatches("System UI isn['’]t responding"));
        if (!title.exists()) return;
        String exactTitle = title.getText();
        UiObject wait = object(new UiSelector().packageName(title.getPackageName())
                .textMatches("(?i)wait"));
        assertTrue("Known System UI ANR has no Wait action; refusing any other action",
                wait.waitForExists(1000));
        dispatchClick(wait, "Wait for exact System UI ANR");
        assertTrue("Exact System UI ANR did not dismiss after Wait", title.waitUntilGone(5000));
        note("ENV: selected Wait for software-emulator overlay: " + exactTitle);
        sleep(350);
    }

    private void recordOrCompare(String key, String value) {
        if ("verify".equals(mode)) compare(key, value);
        else observed.setProperty(key, value);
        note(key + "=" + value);
    }

    private void compare(String key, String value) {
        observed.setProperty(key, value);
        assertEquals("Preserved UI field differs: " + key, required(baseline, key), value);
    }

    private String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertNotNull("Missing baseline key: " + key, value);
        return value;
    }

    private int progress(String description) {
        Matcher matcher = PROGRESS.matcher(description);
        assertTrue("Task row does not expose numeric progress: " + description, matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    private String day() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()); }

    private void capture(String label) throws Exception {
        String stem = "shiben-" + runId + "-" + label;
        File png = new File(directory, stem + ".png");
        assertTrue("Screenshot failed: " + png, device.takeScreenshot(png));
        assertTrue("Screenshot is empty: " + png, png.length() > 0);
        // Legacy UiDevice documents dump names relative to /data/local/tmp.
        // Use a unique simple name, then copy the public dump to our evidence dir.
        device.dumpWindowHierarchy(stem + ".xml");
        File xml = new File("/data/local/tmp", stem + ".xml");
        assertTrue("Hierarchy dump not created: " + xml, xml.isFile() && xml.length() > 0);
        FileInputStream in = new FileInputStream(xml);
        FileOutputStream out = new FileOutputStream(new File(directory, stem + ".xml"));
        try { copy(in, out); } finally { in.close(); out.close(); }
        note("Captured " + label + ": " + png.getName());
    }

    private void store(Properties properties, File file) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try { properties.store(out, "Public UI-only official upgrade evidence; " + new Date()); }
        finally { out.close(); }
    }

    private void copy(InputStream in, java.io.OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) != -1;) out.write(buffer, 0, read);
    }

    private void note(String message) {
        System.out.println("OFFICIAL_UI_QA " + message);
        if (log != null) log.println(new Date() + " " + message);
    }
}
