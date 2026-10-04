package cn.focusledger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Regenerable daily Markdown retained in filesDir, never an expiring cache. SQLite remains truth.
 * Call schedule after mutations/startup; an application-scoped queue survives Activity recreation.
 * Optional SAF mirroring is used only after the user selects a tree with the system picker.
 */
public final class DailyArchive {
    private static volatile DailyArchive instance;
    private static final String PREFS = "daily_archive_v2", TREE = "backup_tree", STATUS = "backup_status";
    private final Context app;
    private final SharedPreferences prefs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "shiben-daily-archive"); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
    });
    private final AtomicLong changes = new AtomicLong();
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile String status;

    private DailyArchive(Context context) {
        app = context.getApplicationContext(); prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        status = prefs.getString(STATUS, "尚未选择外部备份文件夹");
    }
    public static DailyArchive get(Context context) {
        if (instance == null) synchronized (DailyArchive.class) {
            if (instance == null) instance = new DailyArchive(context);
        }
        return instance;
    }
    public static void schedule(Context context) { get(context).schedule(); }
    public void schedule() {
        changes.incrementAndGet();
        enqueue();
    }
    private void enqueue() {
        if (!queued.compareAndSet(false, true)) return;
        executor.schedule(() -> {
            long observed = -1;
            try {
                do {
                    observed = changes.get();
                    try { regenerate(); }
                    catch (Exception error) { setStatus("每日文件生成失败：" + friendly(error)); }
                } while (observed != changes.get());
            } finally {
                queued.set(false);
                if (observed != changes.get()) enqueue();
            }
        }, 250, TimeUnit.MILLISECONDS);
    }

    public File getDirectory() {
        File directory = new File(app.getFilesDir(), "daily-markdown-v2");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IllegalStateException("无法创建每日记录文件夹");
        return directory;
    }
    public File getDailyFile(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("日期不能为空");
        return new File(getDirectory(), "时本_" + date + ".md");
    }
    public Uri getBackupTree() {
        String value = prefs.getString(TREE, null);
        return value == null ? null : Uri.parse(value);
    }
    public String getBackupStatus() { return status; }

    /** Pass the original ACTION_OPEN_DOCUMENT_TREE result URI and its Intent.getFlags(). */
    public void setBackupTree(Uri tree, int pickerFlags) {
        if (tree == null || !DocumentsContract.isTreeUri(tree))
            throw new IllegalArgumentException("请选择一个文件夹");
        int allowed = pickerFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        int needed = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (allowed != needed || (pickerFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0)
            throw new IllegalArgumentException("此位置未提供长期读写权限，请重新选择支持的文件夹");
        // This method is never called autonomously: only the explicit system-picker result grants access.
        app.getContentResolver().takePersistableUriPermission(tree, allowed);
        Uri previous = getBackupTree();
        SharedPreferences.Editor editor = prefs.edit().putString(TREE, tree.toString());
        // Reselecting a folder explicitly retries every file, including anything removed externally.
        for (String key : prefs.getAll().keySet()) if (key.startsWith("mirror:")) editor.remove(key);
        editor.apply();
        if (previous != null && !previous.equals(tree)) release(previous);
        setStatus("外部文件夹已选择，正在备份每日 Markdown");
        schedule();
    }
    /** Disables future mirroring; existing external files are not deleted. */
    public void clearBackupTree() {
        Uri previous = getBackupTree();
        SharedPreferences.Editor editor = prefs.edit().remove(TREE);
        for (String key : prefs.getAll().keySet()) if (key.startsWith("mirror:")) editor.remove(key);
        editor.apply();
        if (previous != null) release(previous);
        setStatus("外部自动备份已关闭；已保存的外部文件仍保留");
    }
    private void release(Uri tree) {
        try { app.getContentResolver().releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (RuntimeException ignored) { /* Provider may already have revoked the grant. */ }
    }

    private void regenerate() throws IOException {
        File directory = getDirectory();
        TreeSet<LocalDate> days = new TreeSet<>();
        // Previously archived dates stay on disk even if the last corresponding session is deleted.
        // Rewrite from SQLite, rather than silently leaving obsolete Markdown behind.
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.matches("时本_[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")) {
                try { days.add(LocalDate.parse(name.substring(3, 13))); }
                catch (RuntimeException ignored) { /* Unrecognized file is not a generated report. */ }
            }
        }
        Uri tree = getBackupTree();
        boolean mirrorOkay = true;
        Map<String, Uri> mirrorFiles = null;
        if (tree != null) {
            try { mirrorFiles = listMirrorFiles(tree); }
            catch (Exception error) {
                mirrorOkay = false;
                setStatus("本机 Markdown 将继续保留，外部备份无法访问：" + friendly(error) + "。请检查或重新选择文件夹");
            }
        }
        int count = 0;
        try (LedgerStore store = new LedgerStore(app)) {
            days.addAll(store.getRecordedDays());
            for (LocalDate date : days) {
                byte[] bytes = V2Reports.dailyMarkdown(store.getDaySnapshot(date)).getBytes(StandardCharsets.UTF_8);
                File file = getDailyFile(date);
                writeAtomicUtf8(file, bytes);
                if (tree != null && mirrorFiles != null && tree.equals(getBackupTree())) {
                    try { mirror(tree, mirrorFiles, file.getName(), bytes); }
                    catch (Exception error) {
                        mirrorOkay = false; mirrorFiles = null;
                        if (tree.equals(getBackupTree()))
                            setStatus("本机 Markdown 已保留，外部备份失败：" + friendly(error) + "。请检查或重新选择文件夹");
                    }
                }
                count++;
            }
        }
        if (tree == null && getBackupTree() == null) setStatus("已保留 " + count + " 天本机 Markdown；未选择外部备份文件夹");
        else if (tree != null && mirrorOkay && tree.equals(getBackupTree()))
            setStatus("已备份 " + count + " 天 Markdown 到所选文件夹（不可直接导入账本）");
    }

    /** AtomicFile keeps the previous complete UTF-8 file on a failed or interrupted write. */
    static void writeAtomicUtf8(File file, byte[] utf8) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        if (file.exists()) {
            try (FileInputStream input = atomic.openRead()) {
                if (Arrays.equals(readAll(input), utf8)) return;
            } catch (IOException ignored) { /* A fresh atomic rewrite may recover an unreadable file. */ }
        }
        FileOutputStream output = null;
        try {
            output = atomic.startWrite(); output.write(utf8); atomic.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("写入每日 Markdown 失败", error);
        }
    }
    private static byte[] readAll(FileInputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int length;
        while ((length = input.read(buffer)) != -1) out.write(buffer, 0, length);
        return out.toByteArray();
    }

    private Map<String, Uri> listMirrorFiles(Uri tree) throws IOException {
        Map<String, Uri> result = new HashMap<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor cursor = app.getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取备份文件夹");
            while (cursor.moveToNext()) result.put(cursor.getString(1),
                    DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
        }
        return result;
    }
    private void mirror(Uri tree, Map<String, Uri> documents, String name, byte[] bytes) throws IOException {
        String digest = sha256(bytes);
        String key = "mirror:" + tree + ":" + name;
        Uri target = documents.get(name);
        // Check provider inventory before skipping: deleted files are recreated on the next pass.
        if (target != null && (target + ":" + digest).equals(prefs.getString(key, null))) return;
        if (target == null) {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            target = DocumentsContract.createDocument(app.getContentResolver(), parent, "text/markdown", name);
            if (target == null) throw new IOException("文件夹无法创建 Markdown");
            documents.put(name, target);
        }
        // SAF providers do not promise atomic replacement. The atomic private copy remains authoritative
        // if a provider disconnects during this best-effort external mirror.
        try (OutputStream output = app.getContentResolver().openOutputStream(target, "wt")) {
            if (output == null) throw new IOException("无法写入外部文件");
            output.write(bytes); output.flush();
        }
        if (tree.equals(getBackupTree())) prefs.edit().putString(key, target + ":" + digest).apply();
    }
    private void setStatus(String value) { status = value; prefs.edit().putString(STATUS, value).apply(); }
    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String friendly(Throwable error) {
        String text = error.getMessage(); return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text;
    }
}
