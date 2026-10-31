package dev.streampocket;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Disposable, app-private thumbnails only. All probing and disk IO runs on the preview worker. */
final class ScrubStoryboardCache {
    static final int WIDTH = 128, HEIGHT = 72, MAX_FRAMES = 96;
    static final long DISK_LIMIT = 24L * 1024 * 1024;
    private final File root, directory;

    private ScrubStoryboardCache(File root, String key) {
        this.root = root; directory = new File(root, key);
        if (directory.isDirectory() || directory.mkdirs()) directory.setLastModified(System.currentTimeMillis());
        trim();
    }

    static ScrubStoryboardCache open(Context app, Uri uri, long durationMs) {
        try {
            long size, modified, generation = -1;
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath()).getCanonicalFile();
                if (!file.isFile() || !file.canRead()) return null;
                size = file.length(); modified = file.lastModified();
            } else {
                // A stale cached image must not bypass revoked access to the video.
                try (ParcelFileDescriptor fd = app.getContentResolver().openFileDescriptor(uri, "r")) {
                    if (fd == null) return null;
                }
                String[] columns = Build.VERSION.SDK_INT >= 30 && "media".equals(uri.getAuthority())
                        ? new String[]{MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_MODIFIED}
                        : new String[]{MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
                try (Cursor cursor = app.getContentResolver().query(uri, columns, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) return null;
                    size = cursor.getLong(0); modified = cursor.getLong(1);
                    if (columns.length == 3 && !cursor.isNull(2)) generation = cursor.getLong(2);
                }
            }
            // Providers without a revision cannot safely reuse persistent previews. Memory still works.
            if (size <= 0 || modified <= 0) return null;
            String identity = "storyboard-v1|" + uri + "|" + durationMs + "|" + size + "|" + modified + "|" + generation;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(); for (byte value : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return new ScrubStoryboardCache(new File(app.getCacheDir(), "seek-storyboards-v1"), key.toString());
        } catch (Exception unavailable) { return null; }
    }

    String identity() { return directory.getName(); }

    Bitmap read(long sampleMs) { return readImage(Long.toString(sampleMs), WIDTH, HEIGHT); }

    // Nearest non-sync frames are deliberately separate from rough keyframe tiles.
    Bitmap readFrame(long positionMs) { return readImage("frame-v1-" + positionMs, 256, 144); }
    void writeFrame(long positionMs, Bitmap bitmap) { writeImage("frame-v1-" + positionMs, bitmap); }

    private Bitmap readImage(String name, int maxWidth, int maxHeight) {
        File file = new File(directory, name + ".jpg");
        if (!file.isFile() || file.length() <= 0 || file.length() > 128 * 1024) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > maxWidth || options.outHeight > maxHeight) { file.delete(); return null; }
            options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap result = BitmapFactory.decodeFile(file.getPath(), options);
            if (result == null) file.delete();
            return result;
        } catch (RuntimeException | OutOfMemoryError invalid) { file.delete(); return null; }
    }

    void write(long sampleMs, Bitmap bitmap) { writeImage(Long.toString(sampleMs), bitmap); }

    private void writeImage(String name, Bitmap bitmap) {
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File target = new File(directory, name + ".jpg"), staged = new File(directory, name + ".tmp");
        if (target.isFile()) return;
        try (FileOutputStream output = new FileOutputStream(staged)) {
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)) { output.flush(); staged.renameTo(target); }
        } catch (Exception ignored) { /* Cache loss never affects the original media. */ }
        finally { staged.delete(); }
        trim();
    }

    private void trim() {
        File[] exact=directory.listFiles(file->file.getName().matches("frame-v1-[0-9]+\\.jpg"));
        if(exact!=null&&exact.length>128){Arrays.sort(exact,Comparator.comparingLong(File::lastModified));for(int i=0;i<exact.length-128;i++)exact[i].delete();}
        File[] sources = root.listFiles(file -> file.isDirectory() && file.getName().matches("[a-f0-9]{64}"));
        if (sources == null) return;
        Arrays.sort(sources, Comparator.comparingLong(File::lastModified));
        long bytes = 0; for (File source : sources) { File[] files = source.listFiles(); if (files != null) for (File file : files) bytes += file.length(); }
        int count = sources.length;
        for (File source : sources) {
            if (bytes <= DISK_LIMIT && count <= 24) break;
            if (source.equals(directory)) continue;
            File[] files = source.listFiles(); if (files != null) for (File file : files) { long size = file.length(); if (file.delete()) bytes -= size; }
            if (source.delete()) count--;
        }
    }

    static long step(long durationMs) {
        long buckets = Math.max(1, (Math.max(1, durationMs) - 1) / 500 + 1);
        return ((buckets - 1) / MAX_FRAMES + 1) * 500;
    }
}
