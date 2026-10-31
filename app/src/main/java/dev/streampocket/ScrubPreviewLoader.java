package dev.streampocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Prepared local storyboards with a synchronous memory hot path and bounded detail refinement. */
public final class ScrubPreviewLoader {
    private static final int MAX_WIDTH = 256, MAX_HEIGHT = 144;
    private static final long THROTTLE_MS = 90, BUCKET_MS = 500, WARM_GAP_MS = 60;
    private static final long PREPARE_BUDGET_MS = 15_000;
    private final Context app;
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "scrub-preview"); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
    });
    // Both caches are accessed under lock, never while decoding or doing IO.
    // 2 MiB details + at most 96 x 128 x 72 x 2 bytes storyboard = under 4 MiB.
    // Eviction never recycles a bitmap still displayed by ImageView.
    private final LruCache<Long, Bitmap> memory = new LruCache<Long, Bitmap>(2 * 1024 * 1024) {
        @Override protected int sizeOf(Long key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final TreeMap<Long, Bitmap> storyboard = new TreeMap<>();
    private final boolean[] attempted = new boolean[ScrubStoryboardCache.MAX_FRAMES];
    private final DecoderFactory factory;
    private Listener listener;
    private Uri source;
    private long durationMs, sourceVersion, scrubVersion, requestVersion, warmStep = 500, focusMs, warmCost;
    private int warmCount, warmFailures;
    private Request desired, pending, latest, warmPending;
    private Preview displayed;
    private boolean released, scheduled, dispatchPosted, closeRequested, preparationEnabled;
    // Decoder and disk cache belong exclusively to the single worker.
    private FrameDecoder decoder;
    private long decoderVersion = -1, diskVersion = -1;
    private ScrubStoryboardCache disk;
    private final Runnable dispatch = () -> {
        synchronized (lock) {
            dispatchPosted = false;
            if (released || desired == null) return;
            pending = desired; desired = null; scheduleLocked();
        }
    };
    private final Runnable warm = this::queueWarm;

    public interface Listener {
        /** On main. samplePositionMs is a nearby-keyframe lookup, NOT an exact PTS guarantee. */
        void onPreview(long requestedPositionMs, long samplePositionMs, Bitmap bitmap);
        void onUnavailable(long positionMs);
        default void onReset() {}
    }

    public ScrubPreviewLoader(Context context, Listener listener) { this(context, listener, NativeDecoder::new); }
    ScrubPreviewLoader(Context context, Listener listener, DecoderFactory factory) {
        if (listener == null || factory == null) throw new IllegalArgumentException("Preview listener is required");
        app = context.getApplicationContext(); this.listener = listener; this.factory = factory;
    }

    public void setSource(Uri uri, long durationMs) {
        requireMain();
        if (uri != null && !"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme()))
            throw new IllegalArgumentException("Seek previews require a local video");
        main.removeCallbacks(dispatch); main.removeCallbacks(warm);
        synchronized (lock) {
            if (released) return;
            source = uri; this.durationMs = Math.max(0, durationMs);
            sourceVersion++; scrubVersion++; requestVersion++; desired = pending = latest = warmPending = null;
            dispatchPosted = false; preparationEnabled = false; displayed = null; memory.evictAll(); storyboard.clear();
            java.util.Arrays.fill(attempted, false); warmStep = ScrubStoryboardCache.step(this.durationMs);
            warmCount = this.durationMs > 0 ? (int) Math.min(ScrubStoryboardCache.MAX_FRAMES, (this.durationMs - 1) / warmStep + 1) : 0;
            warmCost = 0; warmFailures = 0; closeRequested = true; scheduleLocked();
        }
        listener.onReset();
    }

    /** Start after playback is ready, not during its first frame/buffering. Work yields between tiles. */
    public void prepare(long positionMs) {
        requireMain();
        synchronized (lock) {
            if (released || source == null || warmCount == 0) return;
            focusMs = Math.max(0, positionMs); preparationEnabled = true;
        }
        main.removeCallbacks(warm); main.postDelayed(warm, 350);
    }

    /** Background/buffering does not keep generating thumbnails or holding a decoder. */
    public void pausePreparation() {
        requireMain(); main.removeCallbacks(warm);
        synchronized (lock) {
            if (released) return;
            preparationEnabled = false; warmPending = null; closeRequested = true; scheduleLocked();
        }
    }

    /** Hot thumbnails are delivered in this MOVE call: no timer, executor, file IO or codec. */
    public void request(long positionMs) {
        requireMain();
        Preview cached = null; long at; boolean absent;
        synchronized (lock) {
            if (released) return;
            at = Math.max(0, positionMs); if (durationMs > 0) at = Math.min(at, durationMs);
            absent = source == null; requestVersion++; focusMs = at;
            if (!absent) {
                long sample = (at / BUCKET_MS) * BUCKET_MS;
                if (durationMs > 0) sample = Math.min(sample, durationMs - 1);
                latest = new Request(source, sourceVersion, scrubVersion, requestVersion, at, sample, false, durationMs);
                cached = cachedLocked(latest, null);
                boolean unchanged = cached != null && displayed != null && cached.bitmap == displayed.bitmap && cached.sampleMs == displayed.sampleMs;
                displayed = cached;
                if (cached != null && (cached.sampleMs == sample || Math.abs(cached.sampleMs-at) <= BUCKET_MS/2)) {
                    // Drop an obsolete pending target even when its codec is still running.
                    desired = pending = null; dispatchPosted = false; main.removeCallbacks(dispatch);
                } else {
                    desired = latest;
                    if (pending != null) { pending = desired; desired = null; }
                    else if (!dispatchPosted) { dispatchPosted = true; main.postDelayed(dispatch, THROTTLE_MS); }
                    if (unchanged) cached = null;
                }
            } else desired = latest = null;
        }
        if (cached != null) listener.onPreview(at, cached.sampleMs, cached.bitmap);
        else if (absent) listener.onUnavailable(at);
    }

    public void clear() {
        requireMain(); main.removeCallbacks(dispatch);
        synchronized (lock) {
            if (released) return;
            scrubVersion++; requestVersion++; desired = pending = latest = null; displayed = null; dispatchPosted = false;
            closeRequested = true; scheduleLocked();
        }
        listener.onReset();
    }

    public void release() {
        requireMain(); main.removeCallbacks(dispatch); main.removeCallbacks(warm); Listener target;
        synchronized (lock) {
            if (released) return;
            target = listener; released = true; listener = null; scrubVersion++; requestVersion++;
            desired = pending = latest = warmPending = null; dispatchPosted = false; preparationEnabled = false;
            memory.evictAll(); storyboard.clear(); displayed = null; closeRequested = true; scheduleLocked(); worker.shutdown();
        }
        target.onReset();
    }

    private void queueWarm() {
        synchronized (lock) {
            if (released || !preparationEnabled) return;
            if (warmCost >= PREPARE_BUDGET_MS || warmFailures >= 3) { closeRequested = true; scheduleLocked(); return; }
            if (desired != null || pending != null || warmPending != null) { main.postDelayed(warm, WARM_GAP_MS); return; }
            // Prepare the current neighbourhood first, then progressively fill the entire timeline.
            int near = (int) Math.min(warmCount - 1, focusMs / warmStep), index = -1;
            for (int offset : new int[]{0, 1, -1}) {
                int candidate = near + offset;
                if (candidate >= 0 && candidate < warmCount && !attempted[candidate]) { index = candidate; break; }
            }
            if (index < 0) for (int i = 0; i < 128; i++) {
                int candidate = Integer.reverse(i) >>> 25;
                if (candidate < warmCount && !attempted[candidate]) { index = candidate; break; }
            }
            if (index < 0) { closeRequested = true; scheduleLocked(); return; }
            attempted[index] = true;
            warmPending = new Request(source, sourceVersion, scrubVersion, requestVersion,
                    index * warmStep, index * warmStep, true, durationMs);
            scheduleLocked();
        }
    }

    private void scheduleLocked() {
        if (!scheduled) { scheduled = true; worker.execute(this::drain); }
    }

    private void drain() {
        while (true) {
            Request next; boolean close, stop; long currentSource;
            synchronized (lock) {
                close = closeRequested; closeRequested = false; stop = released; currentSource = sourceVersion;
                next = pending; pending = null;
                if (next == null) { next = warmPending; warmPending = null; }
                if (!close && !stop && next == null) { scheduled = false; return; }
            }
            if (close || stop || (decoder != null && decoderVersion != currentSource)) closeDecoder();
            if (diskVersion != currentSource) { disk = null; diskVersion = -1; }
            if (stop) { synchronized (lock) { scheduled = false; } return; }
            if (next == null) continue;
            if (!current(next)) { abandonWarm(next); continue; }
            final Request task = next;
            long started = SystemClock.elapsedRealtime(); Bitmap frame; boolean diskHit = false;
            synchronized (lock) { frame = task.warming ? storyboard.get(task.sampleMs) : memory.get(task.sampleMs); }
            if (frame == null) {
                try {
                    if (task.warming) {
                        if (diskVersion != task.sourceVersion) {
                            disk = ScrubStoryboardCache.open(app, task.uri, task.durationMs); diskVersion = task.sourceVersion;
                        }
                        if (disk != null) { frame = disk.read(task.sampleMs); diskHit = frame != null; }
                    }
                    if (frame == null && current(task)) {
                        if (decoder == null || decoderVersion != task.sourceVersion) {
                            closeDecoder(); decoder = factory.open(app, task.uri); decoderVersion = task.sourceVersion;
                        }
                        frame = bounded(decoder.frameAt(task.sampleMs), MAX_WIDTH, MAX_HEIGHT);

                    }
                    if (frame != null && task.warming) frame = compact(frame);
                } catch (Exception | OutOfMemoryError unavailable) { closeDecoder(); frame = null; }
                synchronized (lock) {
                    if (frame != null && current(task)) {
                        if (task.warming) storyboard.put(task.sampleMs, frame); else memory.put(task.sampleMs, frame);
                    }
                }
                if (task.warming && frame != null && current(task) && disk != null) disk.write(task.sampleMs, frame);
            }
            if (task.warming) {
                synchronized (lock) {
                    if (task.sourceVersion == sourceVersion) {
                        warmCost += Math.max(0, SystemClock.elapsedRealtime() - started);
                        warmFailures = frame == null ? warmFailures + 1 : 0;
                    }
                }
                main.postDelayed(warm, diskHit ? 0 : WARM_GAP_MS);
            }
            if (!current(task)) {
                abandonWarm(task);
                continue;
            }
            final Bitmap ready = frame;
            main.post(() -> {
                if (!current(task)) return;
                Request active; Preview best;
                synchronized (lock) {
                    active = latest; if (active == null) return;
                    best = cachedLocked(active, ready == null ? null : new Preview(task.sampleMs, ready)); displayed = best;
                }
                Listener target = listener; if (target == null) return;
                if (best != null) target.onPreview(task.warming ? active.positionMs : task.positionMs, best.sampleMs, best.bitmap);
                else if (!task.warming && task.requestVersion == active.requestVersion) target.onUnavailable(active.positionMs);
            });
        }
    }

    private Preview cachedLocked(Request request, Preview fallback) {
        Bitmap exact = memory.get(request.sampleMs);
        if (exact != null) return new Preview(request.sampleMs, exact);
        Preview best = fallback;
        if (displayed != null && (best == null || Math.abs(displayed.sampleMs - request.positionMs) < Math.abs(best.sampleMs - request.positionMs))) best = displayed;
        Map.Entry<Long, Bitmap> low = storyboard.floorEntry(request.positionMs), high = storyboard.ceilingEntry(request.positionMs);
        if (low != null && (best == null || Math.abs(low.getKey() - request.positionMs) < Math.abs(best.sampleMs - request.positionMs))) best = new Preview(low.getKey(), low.getValue());
        if (high != null && (best == null || Math.abs(high.getKey() - request.positionMs) < Math.abs(best.sampleMs - request.positionMs))) best = new Preview(high.getKey(), high.getValue());
        return best;
    }

    private void abandonWarm(Request task) {
        if(task.warming) synchronized(lock) {
            if(task.sourceVersion==sourceVersion && !storyboard.containsKey(task.sampleMs)) attempted[(int)(task.sampleMs/warmStep)] = false;
        }
    }

    private boolean current(Request request) {
        synchronized (lock) {
            return !released && request.sourceVersion == sourceVersion
                    && (request.warming ? preparationEnabled : request.scrubVersion == scrubVersion);
        }
    }
    private void closeDecoder() {
        if (decoder != null) { try { decoder.close(); } catch (Exception ignored) {} decoder = null; }
        decoderVersion = -1;
    }
    private static Bitmap compact(Bitmap frame) {
        Bitmap small = bounded(frame, ScrubStoryboardCache.WIDTH, ScrubStoryboardCache.HEIGHT);
        if (small.getConfig() == Bitmap.Config.RGB_565) return small;
        Bitmap compact = small.copy(Bitmap.Config.RGB_565, false);
        if (compact == null) { small.recycle(); return null; }
        if (compact != small) small.recycle(); return compact;
    }
    private static Bitmap bounded(Bitmap frame, int width, int height) {
        if (frame == null || frame.isRecycled()) return null;
        if (frame.getWidth() <= width && frame.getHeight() <= height) return frame;
        float factor = Math.min((float) width / frame.getWidth(), (float) height / frame.getHeight());
        Bitmap small;
        try { small = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * factor)), Math.max(1, Math.round(frame.getHeight() * factor)), true); }
        catch (RuntimeException | OutOfMemoryError failure) { frame.recycle(); throw failure; }
        if (small != frame) frame.recycle(); return small;
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Preview controls must run on main");
    }

    interface DecoderFactory { FrameDecoder open(Context app, Uri source) throws Exception; }
    interface FrameDecoder { Bitmap frameAt(long positionMs) throws Exception; void close() throws Exception; }

    private static final class NativeDecoder implements FrameDecoder {
        private final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        NativeDecoder(Context app, Uri source) throws Exception {
            try { retriever.setDataSource(app, source); }
            catch (RuntimeException | OutOfMemoryError failure) {
                try { retriever.release(); } catch (Exception ignored) { /* Preserve the source failure. */ }
                throw failure;
            }
        }
        @Override public Bitmap frameAt(long positionMs) {
            // Android's API fits inside these dimensions without changing aspect.
            // API 24-26 needs a temporary full frame, always off the UI thread.
            long timeUs = Math.min(positionMs, Long.MAX_VALUE / 1000) * 1000;
            return Build.VERSION.SDK_INT >= 27
                    ? retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_WIDTH, MAX_HEIGHT)
                    : retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        }
        @Override public void close() throws Exception { retriever.release(); }
    }

    private static final class Preview {
        final long sampleMs; final Bitmap bitmap;
        Preview(long sampleMs, Bitmap bitmap) { this.sampleMs = sampleMs; this.bitmap = bitmap; }
    }
    private static final class Request {
        final Uri uri;
        final long sourceVersion, scrubVersion, requestVersion, positionMs, sampleMs, durationMs;
        final boolean warming;
        Request(Uri uri, long sourceVersion, long scrubVersion, long requestVersion, long positionMs, long sampleMs, boolean warming, long durationMs) {
            this.uri = uri; this.sourceVersion = sourceVersion; this.scrubVersion = scrubVersion; this.requestVersion = requestVersion;
            this.positionMs = positionMs; this.sampleMs = sampleMs; this.warming = warming; this.durationMs = durationMs;
        }
    }
}
