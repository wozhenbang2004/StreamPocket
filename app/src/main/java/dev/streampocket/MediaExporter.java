package dev.streampocket;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Publishes only completed media. Pending rows and partial copies are rolled back. */
public final class MediaExporter {
    private MediaExporter() {}
    public static final class Result {
        public final Uri uri;
        public final String mime;
        Result(Uri uri, String mime) { this.uri = uri; this.mime = mime; }
    }

    public static Result save(Context context, File source, BooleanSupplier cancelled) throws Exception {
        if (!source.isFile() || source.length() == 0) throw new IOException("完整视频文件不存在或为空");
        String fileName = source.getName();
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (mime == null) mime = extension.equals("mkv") ? "video/x-matroska" : "video/mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.SIZE, source.length());
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamPocket");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法创建下载文件，请检查手机存储空间");
            try {
                try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IOException("无法写入下载目录");
                    copy(source, output, cancelled);
                }
                checkCancelled(cancelled);
                ContentValues complete = new ContentValues(); complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, complete, null, null) <= 0) throw new IOException("无法完成文件保存");
                return new Result(uri, mime);
            } catch (Exception failure) {
                context.getContentResolver().delete(uri, null, null); throw failure;
            }
        }
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "StreamPocket");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建下载目录，请允许存储权限");
        File destination = uniqueFile(directory, source.getName());
        File temporary = File.createTempFile(".streampocket-", ".partial", directory);
        try {
            try (OutputStream output = new FileOutputStream(temporary)) { copy(source, output, cancelled); }
            checkCancelled(cancelled);
            if (!temporary.renameTo(destination)) throw new IOException("无法完成文件保存");
            values.put(MediaStore.MediaColumns.DATA, destination.getAbsolutePath());
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, destination.getName());
            Uri uri = context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
            if (uri == null) throw new IOException("无法登记已下载的文件");
            return new Result(uri, mime);
        } catch (Exception failure) { temporary.delete(); destination.delete(); throw failure; }
    }

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name); int counter = 1;
        int dot = name.lastIndexOf('.'); String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        while (file.exists()) file = new File(directory, stem + " (" + counter++ + ")" + suffix);
        return file;
    }
    private static void copy(File source, OutputStream output, BooleanSupplier cancelled) throws Exception {
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[131072]; int count;
            while ((count = input.read(buffer)) != -1) { checkCancelled(cancelled); output.write(buffer, 0, count); }
            output.flush();
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消保存");
    }
}
