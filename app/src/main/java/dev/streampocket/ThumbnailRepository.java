package dev.streampocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One frame extractor, a small memory cache and a bounded disposable disk cache. */
public final class ThumbnailRepository {
    private static final int WIDTH = 480, HEIGHT = 270;
    private static final long DISK_LIMIT = 32L * 1024 * 1024;
    private final Context app;
    private final File directory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<ImageView, Request> requests = new WeakHashMap<>();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(24), runnable -> { Thread thread = new Thread(runnable, "library-cover"); thread.setPriority(Thread.MIN_PRIORITY); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean released;

    public ThumbnailRepository(Context context) {
        app = context.getApplicationContext(); directory = new File(app.getCacheDir(), "video-covers");
        worker.allowCoreThreadTimeOut(true);
    }

    /** Bind and cancel are called on the UI thread; all decoding stays on one worker. */
    public void load(ImageView target, long videoId, String uri, String thumbnailKey, long durationMs) {
        cancel(target); target.setImageDrawable(null);
        if (released) return;
        String key = thumbnailKey.replaceAll("[^A-Za-z0-9_-]", "_");
        Bitmap cached = memory.get(key);
        if (cached != null) { target.setImageBitmap(cached); return; }
        Request request = new Request(target); requests.put(target, request);
        request.retry = () -> enqueue(request, videoId, uri, key, durationMs);
        enqueue(request, videoId, uri, key, durationMs);
    }

    private void enqueue(Request request, long videoId, String uri, String key, long durationMs) {
        ImageView target = request.target.get();
        if (released || request.cancelled || target == null || requests.get(target) != request) return;
        if (MediaWorkload.shouldPauseCovers()) { defer(request); return; }
        request.task = new FutureTask<>(() -> {
            if (released || request.cancelled) return null;
            Bitmap bitmap = readCache(key);
            if (bitmap == null && !request.cancelled) {
                // Work may have started between binding this row and reaching the
                // worker. An already running native extraction is not interrupted.
                if (MediaWorkload.shouldPauseCovers()) { main.post(() -> defer(request)); return null; }
                bitmap = extract(videoId, uri, durationMs);
            }
            if (bitmap != null && !released && !request.cancelled) {
                memory.put(key, bitmap); writeCache(key, bitmap);
                Bitmap ready = bitmap;
                main.post(() -> {
                    ImageView view = request.target.get();
                    if (!released && view != null && requests.get(view) == request && !request.cancelled) { view.setImageBitmap(ready); requests.remove(view); }
                });
            }
            return null;
        });
        try { worker.execute(request.task); }
        catch (RejectedExecutionException full) { if (!released) defer(request); }
    }

    /** One delayed retry per live binding, a fixed delay, and no strong view reference. */
    private void defer(Request request) {
        ImageView view = request.target.get();
        if (released || request.cancelled || view == null || requests.get(view) != request) return;
        main.removeCallbacks(request.retry); main.postDelayed(request.retry, 500);
    }

    public void cancel(ImageView target) {
        Request previous = requests.remove(target);
        if (previous != null) { previous.cancelled = true; main.removeCallbacks(previous.retry); if (previous.task != null) { previous.task.cancel(false); worker.remove(previous.task); } }
    }

    /** Return only an already-generated private cover. No frame decoding or public media access. */
    public String cachedCoverPath(JSONObject video) {
        if(released||video==null)return null;
        String key=video.optString("thumbnailKey").replaceAll("[^A-Za-z0-9_-]","_");
        if(key.isEmpty())return null;
        File file=new File(directory,key+".jpg");
        try{return file.isFile()&&file.length()>0&&file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())?file.getCanonicalPath():null;}
        catch(Exception unavailable){return null;}
    }

    private Bitmap readCache(String key) {
        File file = new File(directory, key + ".jpg");
        if (!file.isFile()) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.RGB_565;
        try { return BitmapFactory.decodeFile(file.getAbsolutePath(), options); }
        catch (RuntimeException | OutOfMemoryError invalid) { file.delete(); return null; }
    }

    private Bitmap extract(long videoId, String uri, long durationMs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(app, Uri.parse(uri));
            long actualDuration = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width = (int) number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = (int) number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if (actualDuration > 0 || width > 0 || height > 0) try (LibraryStore store = new LibraryStore(app)) { store.updateMetadata(videoId, actualDuration, width, height); }
            long sampleMs = Math.min(1000, Math.max(0, actualDuration / 5));
            Bitmap frame;
            if (Build.VERSION.SDK_INT >= 27) frame = retriever.getScaledFrameAtTime(sampleMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT);
            else frame = retriever.getFrameAtTime(sampleMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            if (frame.getWidth() <= WIDTH && frame.getHeight() <= HEIGHT) return frame;
            float scale = Math.min((float) WIDTH / frame.getWidth(), (float) HEIGHT / frame.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(frame, Math.max(1, (int) (frame.getWidth() * scale)), Math.max(1, (int) (frame.getHeight() * scale)), true);
            if (scaled != frame) frame.recycle(); return scaled;
        } catch (Exception | OutOfMemoryError unavailable) { return null; }
        finally { try { retriever.release(); } catch (Exception ignored) {} }
    }
    private static long number(String value) { try { return Long.parseLong(value); } catch (Exception absent) { return 0; } }

    private void writeCache(String key, Bitmap bitmap) {
        if (released) return;
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File target = new File(directory, key + ".jpg"); if (target.isFile()) return;
        File staged = new File(directory, key + ".tmp");
        try (FileOutputStream out = new FileOutputStream(staged)) {
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)) { out.flush(); staged.renameTo(target); }
        } catch (Exception ignored) { /* A cover is disposable; media is never changed. */ }
        finally { staged.delete(); }
        File[] files = directory.listFiles(file -> file.getName().endsWith(".jpg"));
        if (files == null) return;
        long bytes = 0; for (File file : files) bytes += file.length();
        if (bytes <= DISK_LIMIT) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) { long size = file.length(); if (file.delete()) bytes -= size; if (bytes <= DISK_LIMIT) break; }
    }

    public void release() {
        released = true;
        for (ImageView view : new ArrayList<>(requests.keySet())) if (view != null) cancel(view);
        worker.shutdownNow(); main.removeCallbacksAndMessages(null); memory.evictAll();
    }
    private static final class Request {
        final WeakReference<ImageView> target;
        volatile boolean cancelled;
        FutureTask<Void> task;
        Runnable retry;
        Request(ImageView view) { target = new WeakReference<>(view); }
    }
}
