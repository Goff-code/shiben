package cn.focusledger;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Android 10+ app-owned MediaStore writes need no photo or storage permission. */
final class GalleryWriter {
    static final String ALBUM_PATH = Environment.DIRECTORY_PICTURES + "/时本/";

    static List<Uri> save(Context context, List<File> files, String batchId,
                          GalleryTransaction.Control control) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("此系统请使用保存图片文件");
        if (files == null || files.isEmpty()) throw new IOException("没有可保存的图片，请重新生成");
        // Decode bounds only: validate a genuine PNG without allocating another full bitmap.
        for (File file : files) {
            control.checkCancelled();
            if (file == null || !file.isFile()) throw new IOException("临时图片已不可用，请重新生成");
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (!"image/png".equals(bounds.outMimeType) || bounds.outWidth <= 0 || bounds.outHeight <= 0)
                throw new IOException("图片文件无效，请重新生成");
        }
        return GalleryTransaction.save(files, new MediaBackend(context.getContentResolver(), batchId), control);
    }

    static final class MediaBackend implements GalleryTransaction.Backend<Uri> {
        private final ContentResolver resolver;
        private final String batchId;
        MediaBackend(ContentResolver resolver, String batchId) {
            this.resolver = resolver;
            this.batchId = batchId;
        }
        @Override public Uri create(File source, int index) throws IOException {
            if (Build.VERSION.SDK_INT < 29) throw new IOException("此系统请使用保存图片文件");
            String original = source.getName().replaceFirst("(?i)\\.png$", "");
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, original + "_" + batchId + String.format(Locale.ROOT,"_%03d.png", index + 1));
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_PATH);
            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) throw new IOException("无法创建相册图片，请检查可用空间");
            return uri;
        }
        @Override public void copy(File source, Uri destination, Runnable checkCancelled) throws IOException {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(destination, "w");
            if (descriptor == null) throw new IOException("无法写入相册，请检查可用空间");
            long written = 0;
            try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
                 FileInputStream input = new FileInputStream(source)) {
                byte[] buffer = new byte[32768]; int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled.run(); out.write(buffer, 0, read); written += read;
                }
                checkCancelled.run(); out.flush(); descriptor.getFileDescriptor().sync();
                if (written != source.length() || written == 0) throw new IOException("图片未完整保存，请重试");
            }
        }
        @Override public void publish(Uri item) throws IOException {
            ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(item, values, null, null) != 1) throw new IOException("图片无法加入相册，请重试");
        }
        @Override public void delete(Uri item) throws IOException {
            if (resolver.delete(item, null, null) != 1) throw new IOException("未能清理未完成图片");
        }
    }
    private GalleryWriter() { }
}
