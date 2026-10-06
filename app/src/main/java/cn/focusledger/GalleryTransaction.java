package cn.focusledger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Writes one explicitly requested batch; on failure only this batch's new items are removed. */
final class GalleryTransaction {
    interface Backend<T> {
        T create(File source, int index) throws IOException;
        void copy(File source, T item, Runnable checkCancelled) throws IOException;
        void publish(T item) throws IOException;
        void delete(T item) throws IOException;
    }
    interface Control {
        void checkCancelled();
        void progress(int completed, int total);
        /** Atomically rejects a pending cancellation, or closes the cancellation window. */
        void commit();
    }
    static <T> List<T> save(List<File> sources, Backend<T> backend, Control control) throws IOException {
        if (sources == null || sources.isEmpty()) throw new IOException("没有可保存的图片，请重新生成");
        // Fail before inserting any album row if a cache file has already disappeared.
        for (File source : sources) {
            control.checkCancelled();
            if (source == null || !source.isFile() || source.length() == 0)
                throw new IOException("临时图片已不可用，请重新生成");
        }
        List<T> created = new ArrayList<>();
        boolean committed = false;
        try {
            for (int i = 0; i < sources.size(); i++) {
                control.checkCancelled();
                T item = backend.create(sources.get(i), i);
                if (item == null) throw new IOException("无法创建相册图片");
                created.add(item);
                backend.copy(sources.get(i), item, control::checkCancelled);
                control.progress(i + 1, sources.size());
            }
            for (T item : created) {
                control.checkCancelled();
                backend.publish(item);
            }
            control.commit();
            committed = true;
            return Collections.unmodifiableList(new ArrayList<>(created));
        } finally {
            if (!committed) {
                IOException cleanup = null;
                for (T item : created) {
                    try { backend.delete(item); }
                    catch (Exception error) {
                        if (cleanup == null) cleanup = new IOException("保存未完成，部分图片未能清理，请检查相册后重试", error);
                    }
                }
                if (cleanup != null) throw cleanup;
            }
        }
    }
    private GalleryTransaction() { }
}
