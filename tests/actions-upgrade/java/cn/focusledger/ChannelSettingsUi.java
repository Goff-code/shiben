package cn.focusledger;

import android.app.Instrumentation;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Xml;
import org.xmlpull.v1.XmlSerializer;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * AOSP API 35, en-US. Only the system Settings UI can mutate channel controls.
 * No create/deleteNotificationChannel, hidden service setters, appops, or
 * adopted shell identity. Unknown screens fail closed with evidence.
 */
final class ChannelSettingsUi {
    private static final String SETTINGS = "com.android.settings";
    private final Instrumentation test;
    private final Context context;
    private final NotificationManager notifications;
    private final File evidence;

    ChannelSettingsUi(Instrumentation test, Context context, File evidence) {
        this.test = test;
        this.context = context;
        this.evidence = evidence;
        notifications = context.getSystemService(NotificationManager.class);
    }

    void disable(String id) throws Exception {
        open(id);
        require(channel(id).getImportance() > NotificationManager.IMPORTANCE_NONE,
                "disabled fixture must change an enabled old channel");
        AccessibilityNodeInfo toggle = waitNode(n -> same(SETTINGS, n.getPackageName())
                && n.isCheckable() && n.isChecked() && isMasterSwitch(n), false);
        click(toggle);
        await(() -> channel(id).getImportance() == NotificationManager.IMPORTANCE_NONE,
                "system channel switch off " + id);
        capture("system-disabled-" + id);
    }

    void soundNone(String id) throws Exception {
        open(id);
        require(channel(id).getSound() != null, "sound-null fixture must change an audible old channel");
        click(findText("Sound", true));
        // Pinned AOSP ringtone picker. Do not accept arbitrary app dialogs.
        waitNode(n -> pickerPackage(n) && same("None", n.getText()), true);
        click(waitNode(n -> pickerPackage(n) && same("None", n.getText()), true));
        click(waitNode(n -> pickerPackage(n) && (same("OK", n.getText())
                || same("Save", n.getText()) || same("SAVE", n.getText())), false));
        await(() -> channel(id).getSound() == null, "system ringtone None " + id);
        capture("system-sound-none-" + id);
    }

    void vibrationOff(String id) throws Exception {
        open(id);
        require(channel(id).shouldVibrate(), "vibration-off fixture must change a vibrating old channel");
        AccessibilityNodeInfo label = findText("Vibration", true);
        AccessibilityNodeInfo row = label;
        AccessibilityNodeInfo toggle = null;
        for (int depth = 0; row != null && depth < 5; depth++, row = row.getParent()) {
            List<AccessibilityNodeInfo> matches = nodes(row, n -> n.isCheckable());
            if (matches.size() == 1) { toggle = matches.get(0); break; }
            require(matches.size() <= 1, "ambiguous Vibration row; refusing a different settings switch");
        }
        require(toggle != null && toggle.isChecked(), "expected checked system Vibration switch");
        click(toggle);
        await(() -> !channel(id).shouldVibrate(), "system Vibration off " + id);
        capture("system-vibration-off-" + id);
    }

    void lowImportance(String id) throws Exception {
        open(id);
        require(channel(id).getImportance() == NotificationManager.IMPORTANCE_HIGH,
                "low-importance fixture must change the old HIGH default");
        click(findText("Silent", true));
        await(() -> channel(id).getImportance() == NotificationManager.IMPORTANCE_LOW,
                "system Silent choice sets LOW importance " + id);
        capture("system-low-importance-" + id);
    }

    private boolean pickerPackage(AccessibilityNodeInfo node) {
        String pkg = String.valueOf(node.getPackageName());
        return "com.android.soundpicker".equals(pkg) || "com.android.settings".equals(pkg)
                || "com.android.providers.media.module".equals(pkg) || "android".equals(pkg);
    }

    private boolean isMasterSwitch(AccessibilityNodeInfo node) {
        String id = String.valueOf(node.getViewIdResourceName());
        if (id.endsWith(":id/main_switch_bar") || id.endsWith(":id/switch_bar")) return true;
        AccessibilityNodeInfo parent = node.getParent();
        for (int i = 0; parent != null && i < 3; i++, parent = parent.getParent()) {
            String parentId = String.valueOf(parent.getViewIdResourceName());
            if (parentId.endsWith(":id/main_switch_bar") || parentId.endsWith(":id/switch_bar")) return true;
        }
        return false;
    }

    private NotificationChannel channel(String id) {
        NotificationChannel channel = notifications.getNotificationChannel(id);
        require(channel != null, "missing real old channel " + id);
        return channel;
    }

    private void open(String id) throws Exception {
        Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, id)
                // ChannelPanelActivity reuses its prior intent when only NEW_TASK is supplied.
                // Recreate this Settings task; notification channel state is never cleared.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        await(() -> {
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            return root != null && same(SETTINGS, root.getPackageName());
        }, "AOSP channel Settings foreground " + id);
        // A second guard checks the current channel title, not merely the app page.
        waitNode(n -> same(SETTINGS, n.getPackageName())
                && same(channel(id).getName().toString(), n.getText()), true);
        capture("system-open-" + id);
    }

    private AccessibilityNodeInfo findText(String text, boolean scroll) throws Exception {
        return waitNode(n -> same(SETTINGS, n.getPackageName()) && same(text, n.getText()), scroll);
    }

    private AccessibilityNodeInfo waitNode(Predicate<AccessibilityNodeInfo> match, boolean scroll) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        int swipes = 0;
        boolean expanded = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> matches = nodes(root, n -> n.isVisibleToUser() && match.test(n));
                require(matches.size() <= 1, "ambiguous system widget; refusing arbitrary match");
                if (matches.size() == 1) return matches.get(0);
                if (scroll) {
                    List<AccessibilityNodeInfo> advanced = nodes(root, n -> n.isVisibleToUser()
                            && same(SETTINGS, n.getPackageName()) && same("Advanced", n.getText()));
                    if (!expanded && advanced.size() == 1) {
                        click(advanced.get(0)); expanded = true; continue;
                    }
                    List<AccessibilityNodeInfo> lists = nodes(root, n -> n.isVisibleToUser() && n.isScrollable());
                    if (lists.size() > 1) {
                        // Pinned API35 channel page nests its preference RecyclerView in content_parent.
                        // Prefer only the one exact observed system preference list, never an arbitrary container.
                        List<AccessibilityNodeInfo> preferenceLists = nodes(root, n -> n.isVisibleToUser()
                                && n.isScrollable() && same(SETTINGS, n.getPackageName())
                                && same("com.android.settings:id/recycler_view", n.getViewIdResourceName()));
                        if (preferenceLists.size() == 1) lists = preferenceLists;
                    }
                    if (lists.size() == 1 && swipes < 6) {
                        lists.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                        swipes++; SystemClock.sleep(450); continue;
                    }
                }
            }
            SystemClock.sleep(250);
        }
        capture("unsupported-system-settings");
        throw new AssertionError("Required AOSP 35 Settings control was not found. Inspect hierarchy/screenshot; no alternate setter was used.");
    }

    private static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root,
            Predicate<AccessibilityNodeInfo> match) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        collect(root, match, result);
        return result;
    }

    private static void collect(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> match,
            List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (match.test(node)) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), match, result);
    }

    private static void click(AccessibilityNodeInfo node) {
        while (node != null && !node.isClickable()) node = node.getParent();
        require(node != null && node.isEnabled() && node.isVisibleToUser()
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "system widget click failed");
        SystemClock.sleep(500);
    }

    void capture(String stem) throws Exception {
        Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        require(bitmap != null, "screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(evidence, stem + ".png"))) {
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "screenshot encoding failed");
        } finally { bitmap.recycle(); }
        try (FileOutputStream out = new FileOutputStream(new File(evidence, stem + ".xml"))) {
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(out, "UTF-8"); xml.startDocument("UTF-8", true);
            xml.startTag(null, "hierarchy");
            writeNode(xml, test.getUiAutomation().getRootInActiveWindow());
            xml.endTag(null, "hierarchy"); xml.endDocument();
        }
    }

    private void writeNode(XmlSerializer xml, AccessibilityNodeInfo node) throws Exception {
        if (node == null) return;
        xml.startTag(null, "node");
        xml.attribute(null, "package", String.valueOf(node.getPackageName()));
        xml.attribute(null, "resource-id", String.valueOf(node.getViewIdResourceName()));
        xml.attribute(null, "class", String.valueOf(node.getClassName()));
        xml.attribute(null, "text", String.valueOf(node.getText()));
        xml.attribute(null, "content-desc", String.valueOf(node.getContentDescription()));
        xml.attribute(null, "checked", String.valueOf(node.isChecked()));
        xml.attribute(null, "clickable", String.valueOf(node.isClickable()));
        xml.attribute(null, "enabled", String.valueOf(node.isEnabled()));
        xml.attribute(null, "visible", String.valueOf(node.isVisibleToUser()));
        xml.attribute(null, "scrollable", String.valueOf(node.isScrollable()));
        android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
        xml.attribute(null, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) writeNode(xml, node.getChild(i));
        xml.endTag(null, "node");
    }

    private static void await(BooleanSupplier condition, String description) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
        require(condition.getAsBoolean(), "timed out: " + description);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean same(String expected, CharSequence actual) {
        return actual != null && expected.contentEquals(actual);
    }
}
