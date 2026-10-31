package dev.streampocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.media.metrics.LogSessionId;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Format;
import androidx.media3.transformer.Codec;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONObject;

/** User-started, serial local exports. Originals and clip drafts are never rewritten. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class ExportService extends Service {
    public static final String ENQUEUE = "dev.streampocket.EXPORT";
    public static final String CANCEL = "dev.streampocket.CANCEL_EXPORT";
    public static final String PROJECT_ID = "project_id";
    public static final String PRESET_LABEL = "兼容 1080p · MP4 / H.264 / AAC";
    private static final int MAX_LONG_EDGE = 1920;
    private static final String CHANNEL = "video-exports";
    private static final int NOTIFICATION = 189;
    private static final Set<Long> RUNNING = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    // Set immediately after the public-file transaction commits. Status writes
    // already queued by Cancel/onDestroy must check it when they execute.
    private final Set<Long> committed = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Transformer transformer;
    private PowerManager.WakeLock wakeLock;
    private long activeId = -1;
    private long waitingId = -1;
    private File activeFile;
    private boolean preparing, saving;
    private volatile boolean destroyed;
    private int lastStartId;
    private final Runnable playbackWait = this::pump;
    private final Runnable progressPoll = new Runnable() {
        @Override public void run() {
            if (destroyed || transformer == null || activeId < 1) return;
            ProgressHolder holder = new ProgressHolder();
            int state = transformer.getProgress(holder);
            int progress = state == Transformer.PROGRESS_STATE_AVAILABLE ? Math.min(99, holder.progress) : -1;
            record(activeId, "exporting", progress, progress < 0 ? "正在导出片段，完成时间暂无法估计" : "正在导出片段 · " + progress + "%", "");
            updateNotification("正在导出剪辑", activeId, progress);
            main.postDelayed(this, 700);
        }
    };

    public static boolean isRunning(long projectId) { return RUNNING.contains(projectId); }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "视频导出", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示本地剪辑的真实导出状态，可取消。");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent == null) { finishIfIdle(); return START_NOT_STICKY; }
        long id = intent.getLongExtra(PROJECT_ID, -1);
        if (CANCEL.equals(intent.getAction())) { cancel(id); finishIfIdle(); return START_NOT_STICKY; }
        if (!ENQUEUE.equals(intent.getAction()) || id < 1) { finishIfIdle(); return START_NOT_STICKY; }
        Notification notification = notification(activeId > 0 ? "正在导出剪辑" : "准备导出剪辑", activeId > 0 ? activeId : id, -1);
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION, notification);
        if (RUNNING.add(id)) {
            cancelled.remove(id); committed.remove(id); queue.addLast(id);
            execute(() -> {
                try (LibraryStore store = new LibraryStore(this)) {
                    store.recoverInterruptedExports();
                    store.updateProjectExport(id, "queued", -1, "等待导出", "");
                } catch (Exception ignored) { /* pump reports a recoverable project error. */ }
            });
        }
        pump();
        return START_NOT_STICKY;
    }

    private void pump() {
        if (destroyed || activeId > 0) return;
        main.removeCallbacks(playbackWait);
        Long next = queue.peekFirst();
        if (next != null && MediaWorkload.isPlayerActive()) {
            if (waitingId != next) {
                waitingId = next;
                record(next, "queued", -1, "等待播放结束后导出", "");
                updateNotification("等待播放结束后导出", next, -1);
            }
            // No active job or codec is held while the user watches a video.
            main.postDelayed(playbackWait, 750);
            return;
        }
        waitingId = -1;
        Long id = queue.pollFirst();
        if (id == null) { finishIfIdle(); return; }
        activeId = id; preparing = true; saving = false; activeFile = null;
        MediaWorkload.setExporting(true);
        acquireWakeLock();
        record(id, "preparing", -1, "正在检查片段与本地文件", "");
        updateNotification("正在准备剪辑", id, -1);
        execute(() -> {
            try {
                Prepared job = prepare(id);
                main.post(() -> {
                    if (destroyed || activeId != id || isCancelled(id)) {
                        if (!destroyed && activeId == id) finish(id, "cancelled", "已取消导出，剪辑草稿已保留");
                        return;
                    }
                    preparing = false;
                    // Playback may have started while metadata was inspected.
                    // Put the untouched draft back before allocating a codec.
                    if (MediaWorkload.isPlayerActive()) {
                        activeId = -1; queue.addFirst(id);
                        MediaWorkload.setExporting(false); releaseWakeLock(); pump();
                        return;
                    }
                    startExport(job);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!destroyed && activeId == id)
                        finish(id, isCancelled(id) ? "cancelled" : "failed", isCancelled(id) ? "已取消导出，剪辑草稿已保留" : friendly(error));
                });
            }
        });
    }

    private Prepared prepare(long id) throws Exception {
        JSONObject project;
        List<JSONObject> clips;
        try (LibraryStore store = new LibraryStore(this)) {
            project = store.project(id);
            clips = store.clips(id);
        }
        if (project == null) throw new IOException("剪辑草稿不存在，请重新选择。");
        if (clips.isEmpty()) throw new IOException("先添加至少一个视频片段，再导出。");
        List<Clip> inputs = new ArrayList<>(); long duration = 0;
        int width = 0, height = 0;
        for (JSONObject clip : clips) {
            if (isCancelled(id)) throw new InterruptedIOException("已取消");
            long start = clip.optLong("startMs", -1), end = clip.optLong("endMs", -1);
            if (start < 0 || end <= start) throw new IOException("片段的起止时间无效，请回到草稿调整。");
            Uri uri = Uri.parse(clip.optString("uri"));
            if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme()))
                throw new IOException("片段必须来自本机视频库。");
            try (android.content.res.AssetFileDescriptor ignored = getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (ignored == null) throw new IOException("片段原文件无法读取");
            } catch (Exception unavailable) { throw new IOException("原文件不可用：" + clip.optString("title", "视频") + "。草稿仍保留。", unavailable); }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri);
                long actualDuration=longNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                try { ClipContract.validate(start,end,actualDuration); }
                catch(IllegalArgumentException invalid){throw new IOException("片段超出当前原文件时长："+clip.optString("title","视频")+"。请重新调整范围。",invalid);}
                int sourceWidth=integer(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int sourceHeight=integer(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                if(sourceWidth<=0||sourceHeight<=0)throw new IOException("无法读取视频画面："+clip.optString("title","视频"));
                if(inputs.isEmpty()){
                    width=sourceWidth;height=sourceHeight;
                    int rotation=integer(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                    if(rotation==90||rotation==270){int temporary=width;width=height;height=temporary;}
                }
            } finally { retriever.release(); }
            inputs.add(new Clip(uri, start, end)); duration = Math.addExact(duration, end - start);
        }
        int[] size = compatibilitySize(width, height);
        return new Prepared(id, project.optString("title", "拾流剪辑"), inputs, size[0], size[1], duration);
    }

    private void startExport(Prepared job) {
        try {
            File parent = getExternalFilesDir("exports");
            if (parent == null) parent = new File(getCacheDir(), "exports");
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建剪辑临时目录");
            activeFile = new File(parent, outputFileName(job.title, System.currentTimeMillis()));
            Presentation fit = Presentation.createForWidthAndHeight(job.width, job.height, Presentation.LAYOUT_SCALE_TO_FIT);
            List<EditedMediaItem> edited = new ArrayList<>();
            for (Clip clip : job.clips) {
                MediaItem source = ClipContract.mediaItem(clip.uri,clip.startMs,clip.endMs);
                edited.add(new EditedMediaItem.Builder(source)
                        .setEffects(new Effects(Collections.emptyList(), Collections.singletonList(fit))).build());
            }
            // In 1.9.4 this sequence requests an audio output even when its
            // first item has no audio. Media3 generates silence for such items;
            // the deprecated forceAudioTrack setter is not needed.
            Composition composition = composition(edited);
            transformer = new Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(new PreciseEncoderFactory(this))
                    .experimentalSetMp4EditListTrimEnabled(false).experimentalSetTrimOptimizationEnabled(false)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition completed, ExportResult result) {
                            if (destroyed || activeId != job.id || isCancelled(job.id)) return;
                            main.removeCallbacks(progressPoll); transformer = null;
                            publish(job, activeFile);
                        }
                        @Override public void onError(Composition failed, ExportResult result, ExportException exception) {
                            if (destroyed || activeId != job.id || isCancelled(job.id)) return;
                            finish(job.id, "failed", friendly(exception));
                        }
                    }).build();
            record(job.id, "exporting", -1, PRESET_LABEL + " · " + job.width + "×" + job.height + "，保持完整画面", "");
            transformer.start(composition, activeFile.getAbsolutePath());
            main.post(progressPoll);
        } catch (Exception failure) { finish(job.id, "failed", friendly(failure)); }
    }

    private void publish(Prepared job, File output) {
        saving = true;
        record(job.id, "saving", -1, "导出已完成，正在保存新视频", "");
        updateNotification("正在保存剪辑", job.id, -1);
        execute(() -> {
            MediaExporter.Result saved = null;
            try {
                if (isCancelled(job.id)) throw new InterruptedIOException("已取消");
                long outputDuration=inspectOutput(output,job.durationMs,job.clips.size());
                if(isCancelled(job.id))throw new InterruptedIOException("已取消");
                saved = MediaExporter.save(this, output, () -> isCancelled(job.id));
                committed.add(job.id);
                // Cancellation after save() returns must not undo the durable
                // result, even if indexing or a later DB write fails.
                try (LibraryStore store = new LibraryStore(this)) {
                    String detail = "成片已保存到视频库";
                    try { store.addExportedVideo(job.title, saved.uri.toString(), saved.mime, output.length(), outputDuration); }
                    catch (Exception indexFailure) { detail = "成片已保存到下载目录，视频库登记未完成"; }
                    store.updateProjectExport(job.id, "completed", 100, detail, saved.uri.toString());
                }
            } catch (Exception failure) {
                try (LibraryStore store = new LibraryStore(this)) {
                    if (saved != null) {
                        store.updateProjectExport(job.id, "completed", 100,
                                "成片已保存到下载目录，视频库登记未完成", saved.uri.toString());
                    } else {
                        String state = isCancelled(job.id) ? (destroyed ? "interrupted" : "cancelled") : "failed";
                        store.updateProjectExport(job.id, state, -1,
                                isCancelled(job.id) ? "导出已停止，剪辑草稿已保留" : friendly(failure), "");
                    }
                } catch (Exception ignored) { /* File commit is independent of catalogue availability. */ }
            } finally {
                if (output != null) output.delete();
                main.post(() -> finishPublished(job.id));
            }
        });
    }

    private void cancel(long id) {
        if (id < 1) return;
        cancelled.add(id);
        if (queue.remove(id)) {
            RUNNING.remove(id); record(id, "cancelled", -1, "已取消排队，剪辑草稿已保留", "");
            if (waitingId == id) waitingId = -1;
            pump();
        } else if (activeId == id) {
            record(id, "cancelling", -1, "正在取消导出", "");
            if (preparing || saving) return;
            stopTransformer(); finish(id, "cancelled", "已取消导出，剪辑草稿已保留");
        }
    }

    private void finish(long id, String state, String detail) {
        if (activeId != id) return;
        stopTransformer();
        if (activeFile != null) activeFile.delete();
        record(id, state, -1, detail, "");
        finishPublished(id);
    }

    private void finishPublished(long id) {
        // A destroyed service releases its token only after its pending status
        // writes drain on IO, before a new instance can retry this project.
        if (!destroyed) RUNNING.remove(id);
        cancelled.remove(id);
        if (activeId != id) return;
        main.removeCallbacks(progressPoll); activeId = -1; activeFile = null; preparing = false; saving = false;
        if (!destroyed) { MediaWorkload.setExporting(false); releaseWakeLock(); pump(); }
    }

    private boolean isCancelled(long id) { return destroyed || cancelled.contains(id); }
    private void stopTransformer() {
        main.removeCallbacks(progressPoll);
        if (transformer != null) { try { transformer.cancel(); } catch (Exception ignored) {} transformer = null; }
    }
    private void record(long id, String state, int progress, String detail, String output) {
        execute(() -> {
            if (committed.contains(id)) return;
            try (LibraryStore store = new LibraryStore(this)) {
                store.updateProjectExport(id, state, progress, detail, output);
            } catch (Exception ignored) {}
        });
    }
    private void execute(Runnable work) {
        try { io.execute(work); } catch (RejectedExecutionException stopped) { /* The destruction recovery record is already queued. */ }
    }

    private Notification notification(String title, long id, int progress) {
        Intent open = new Intent(this, EditorActivity.class).putExtra(PROJECT_ID, id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, (int) id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ExportService.class).setAction(CANCEL).putExtra(PROJECT_ID, id);
        PendingIntent cancel = PendingIntent.getService(this, (int) id, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_launcher).setContentTitle("拾流 · 视频导出").setContentText(title)
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, progress), progress < 0).addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancel).build();
    }
    private void updateNotification(String title, long id, int progress) {
        if (!destroyed) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(title, id, progress));
    }
    private void finishIfIdle() {
        if (activeId > 0 || !queue.isEmpty()) return;
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(lastStartId);
    }
    @Override public void onTimeout(int startId, int foregroundServiceType) { stopForTimeout(); }
    @Override public void onTimeout(int startId) { stopForTimeout(); }
    private void stopForTimeout() {
        cancelled.addAll(queue); if (activeId > 0) cancelled.add(activeId);
        stopTransformer(); releaseWakeLock(); stopSelf();
    }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); stopTransformer();
        MediaWorkload.setExporting(false); releaseWakeLock();
        for (Long id : queue) {
            record(id, "interrupted", -1, "导出服务已停止，草稿保留，可重新导出", "");
            execute(() -> RUNNING.remove(id));
        }
        queue.clear();
        if (activeId > 0) {
            long id = activeId;
            cancelled.add(id);
            record(activeId, "interrupted", -1, "导出服务已停止，草稿保留，可重新导出", "");
            execute(() -> RUNNING.remove(id));
            if (!saving && activeFile != null) activeFile.delete();
        }
        io.shutdown(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void acquireWakeLock() {
        if (wakeLock==null) {
            PowerManager power=getSystemService(PowerManager.class);
            wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"StreamPocket:clip-export");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(6L*60*60*1000);
    }
    private void releaseWakeLock() { if (wakeLock!=null && wakeLock.isHeld()) wakeLock.release(); }
    static int[] compatibilitySize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("视频尺寸无效");
        double scale = Math.min(1d, (double) MAX_LONG_EDGE / Math.max(width, height));
        return new int[]{Math.max(2, ((int) Math.floor(width * scale)) & ~1),
                Math.max(2, ((int) Math.floor(height * scale)) & ~1)};
    }
    static Composition composition(List<EditedMediaItem> clips) {
        return new Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(clips))
                .setTransmuxAudio(false).setTransmuxVideo(false).build();
    }
    private static int integer(String value) { try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; } }
    private static String friendly(Exception failure) {
        String text = Engine.cleanLog(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("codec") || lower.contains("encoder") || lower.contains("decoder") || lower.contains("hdr"))
            return "设备暂时无法按这些素材的规格导出。请确认原片可播放，或改用其他素材后重试。\n" + text;
        if (lower.contains("space") || lower.contains("enospc")) return "手机空间不足，腾出空间后可从草稿重新导出。";
        return text.isEmpty() ? "导出未完成，草稿已保留。" : text;
    }
    /** MIME equality alone does not request re-encoding for a single item in Media3 1.9.4.
     * Delegate device codec selection, but explicitly require decoded audio/video so a
     * non-keyframe trim cannot silently become a keyframe-aligned stream copy.
     */
    static final class PreciseEncoderFactory implements Codec.EncoderFactory {
        private final DefaultEncoderFactory delegate;
        PreciseEncoderFactory(Context context){delegate=new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build();}
        @Override public boolean videoNeedsEncoding(){return true;}
        @Override public boolean audioNeedsEncoding(){return true;}
        @Override public Codec createForVideoEncoding(Format format,@androidx.annotation.Nullable LogSessionId session)throws ExportException{return delegate.createForVideoEncoding(format,session);}
        @Override public Codec createForAudioEncoding(Format format,@androidx.annotation.Nullable LogSessionId session)throws ExportException{return delegate.createForAudioEncoding(format,session);}
    }
    static String outputFileName(String title,long timestamp){
        String safe=title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
        if(safe.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"))safe=safe.substring(0,safe.length()-4);
        if(safe.isEmpty()||safe.matches("\\.+"))safe="拾流剪辑";
        if(safe.length()>60)safe=safe.substring(0,60);
        return safe+"-"+timestamp+".mp4";
    }
    private long inspectOutput(File output,long expected,int clips)throws Exception{
        if(output==null||!output.isFile()||output.length()<=0)throw new IOException("导出结果为空，草稿已保留。");
        MediaMetadataRetriever probe=new MediaMetadataRetriever();
        try{
            probe.setDataSource(output.getAbsolutePath());
            long duration=longNumber(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width=integer(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height=integer(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if(duration<=0||width<=0||height<=0)throw new IOException("导出文件校验失败，未保存为成片；请从草稿重试。");
            // Container durations include frame / AAC packet rounding; never label
            // the arithmetic sum as a measured output duration.
            if(Math.abs(duration-expected)>Math.max(1000L,clips*100L))throw new IOException("成片时长与所选片段不符，未保存为成片；请重新检查范围。");
            return duration;
        }finally{probe.release();}
    }
    private static long longNumber(String value){try{return Long.parseLong(value);}catch(Exception invalid){return 0;}}
    private static final class Clip {
        final Uri uri; final long startMs, endMs;
        Clip(Uri uri, long startMs, long endMs) { this.uri = uri; this.startMs = startMs; this.endMs = endMs; }
    }
    private static final class Prepared {
        final long id, durationMs; final String title; final List<Clip> clips; final int width, height;
        Prepared(long id, String title, List<Clip> clips, int width, int height, long durationMs) {
            this.id = id; this.title = title; this.clips = clips; this.width = width; this.height = height; this.durationMs = durationMs;
        }
    }
}
