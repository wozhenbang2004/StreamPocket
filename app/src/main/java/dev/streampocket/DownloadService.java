package dev.streampocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-started foreground queue. Process-death recovery is an explicit retry. */
public final class DownloadService extends Service {
    public static final String ENQUEUE = "dev.streampocket.ENQUEUE";
    public static final String CANCEL = "dev.streampocket.CANCEL";
    public static final String PAUSE = "dev.streampocket.PAUSE";
    private static final String CHANNEL = "downloads";
    private static final int NOTIFICATION = 88;
    private final Object lock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<String> cancelled = new HashSet<>();
    private final Set<String> pauseRequested = new HashSet<>();
    private final Set<String> awaitingResumeProgress = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean draining;
    private volatile boolean destroyed;
    private String active;
    private int lastStartId;
    private Engine engine;
    private long lastProgressWrite;

    @Override public void onCreate() {
        super.onCreate(); TaskStore.init(this); engine = new Engine(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示下载状态，传输时可暂停或取消。");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        String id = intent.getStringExtra("id");
        if (PAUSE.equals(intent.getAction())) {
            pause(id); finishIfIdle(startId); return START_NOT_STICKY;
        }
        if (CANCEL.equals(intent.getAction())) {
            cancel(id); finishIfIdle(startId); return START_NOT_STICKY;
        }
        java.util.ArrayList<String> ids = intent.getStringArrayListExtra("ids");
        if (ids == null) { ids = new java.util.ArrayList<>(); if (id != null) ids.add(id); }
        ids.removeIf(item -> item == null || TaskStore.get(this, item) == null);
        if (!ENQUEUE.equals(intent.getAction()) || ids.isEmpty()) {
            finishIfIdle(startId); return START_NOT_STICKY;
        }
        // A foreground start acknowledges Android immediately, but the notification
        // must remain attached to the worker's current task, not the newly added one.
        synchronized (lock) {
            String shown = active != null ? active : queue.peekFirst();
            if (shown == null) shown = ids.get(0);
            JSONObject current = TaskStore.get(this, shown);
            startForeground(NOTIFICATION, notification(current == null ? "等待下载" : current.optString("title"), shown,
                    current == null ? -1 : current.optDouble("progress", -1)));
            java.util.List<String> candidates = new java.util.ArrayList<>();
            for (String item : ids) if (!item.equals(active) && !queue.contains(item)) candidates.add(item);
            java.util.List<String> accepted = TaskStore.markQueuedBatch(this, candidates);
            for (String item : accepted) { cancelled.remove(item); pauseRequested.remove(item); queue.addLast(item); }
            if (accepted.isEmpty()) for (String item : candidates) if (TaskStore.canEnqueue(TaskStore.get(this, item)))
                patch(item, "failed", -1, "无法记录下载队列，请重试", "下载尚未开始。");
            if (!draining && !queue.isEmpty()) { draining = true; worker.execute(this::drain); }
        }
        finishIfIdle(startId);
        return START_NOT_STICKY;
    }

    private void cancel(String id) {
        if (id == null) return;
        boolean isActive;
        synchronized (lock) {
            JSONObject task = TaskStore.get(this, id);
            if (task == null || "completed".equals(task.optString("status"))) return;
            isActive = id.equals(active);
            if (!isActive && !TaskStore.canEnqueue(task)) return;
            pauseRequested.remove(id);
            cancelled.add(id);
            if (!isActive) {
                queue.remove(id); TaskStore.markStopped(this, id, "cancelled", "已取消；可重试继续");
            } else {
                try { TaskStore.update(this, id, new JSONObject().put("status", "cancelling").put("pauseRequested", false)
                        .put("detail", "正在取消").put("userError", "").put("speedBytesPerSecond", -1)
                        .put("etaSeconds", -1).put("activeConnections", -1)); } catch (Exception ignored) {}
            }
        }
        if (isActive) {
            stopActiveProcess(id);
        }
    }

    private void pause(String id) {
        if (id == null) return;
        boolean isActive;
        synchronized (lock) {
            JSONObject task = TaskStore.get(this, id);
            if (task == null || cancelled.contains(id) || !canPause(task)) return;
            isActive = id.equals(active);
            if (!isActive && !"queued".equals(task.optString("status"))) return;
            // The durable request and visible state are installed together, before
            // killing anything. A crash here restores paused, with all bytes kept.
            if (!TaskStore.markPauseRequested(this, id, isActive)) return;
            pauseRequested.add(id);
            if (!isActive) queue.remove(id);
        }
        if (isActive) {
            updateNotification("正在暂停，等待下载进程退出…", id, -1);
            stopActiveProcess(id);
        }
    }

    static boolean canPause(JSONObject task) {
        if (task == null) return false;
        String status = task.optString("status"), phase = task.optString("phase");
        if ("queued".equals(status)) return true;
        return "downloading".equals(status) && !task.optBoolean("transferComplete")
                && !"merging".equals(phase) && !"processing".equals(phase) && !"saving".equals(phase);
    }

    private void stopActiveProcess(String id) {
        YoutubeDL.getInstance().destroyProcessById("download-" + id);
        // Covers a stop arriving between the final preflight check and process registration.
        main.postDelayed(() -> {
            synchronized (lock) {
                if (id.equals(active) && isCancelled(id)) YoutubeDL.getInstance().destroyProcessById("download-" + id);
            }
        }, 350);
    }

    private boolean isCancelled(String id) {
        synchronized (lock) { return destroyed || cancelled.contains(id) || pauseRequested.contains(id); }
    }

    private void drain() {
        while (!destroyed) {
            String id;
            synchronized (lock) {
                id = queue.poll();
                if (id == null) { active = null; draining = false; break; }
                active = id;
            }
            download(id);
            synchronized (lock) { active = null; cancelled.remove(id); pauseRequested.remove(id); awaitingResumeProgress.remove(id); }
        }
        main.post(() -> finishIfIdle(lastStartId));
    }

    private void download(String id) {
        JSONObject task = TaskStore.get(this, id);
        if (task == null) return;
        StringBuilder log = new StringBuilder();
        DownloadProgress tracker = new DownloadProgress();
        String[] lastBoundary = {""};
        PowerManager.WakeLock wakeLock = null;
        MediaExporter.Result committed = null;
        long committedBytes = 0;
        SourceScheduler.Lease[] admission = {null};
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamPocket:download");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
            if (isCancelled(id)) throw new IOException("cancelled");
            publishPreparing(id, tracker.snapshot(), task);
            updateNotification(task.optString("title"), id, -1);
            engine.init();
            if (isCancelled(id)) throw new IOException("cancelled");
            Engine.Media media = Engine.Media.fromJson(task.getJSONObject("media"));
            Engine.Quality quality = Engine.Quality.fromJson(task.getJSONObject("quality"));
            admission[0] = SourceScheduler.SHARED.acquire(media.url, () -> isCancelled(id), stage -> {
                if (isCancelled(id)) return;
                try { TaskStore.update(this, id, new JSONObject().put("status", "downloading").put("phase", "preparing")
                        .put("stageLabel", stage).put("detail", stage)); } catch (Exception ignored) {}
                updateNotification(media.title + " · " + stage, id, -1);
            });
            File root = getExternalFilesDir("downloads");
            if (root == null) root = new File(getFilesDir(), "downloads");
            File directory = new File(root, id);
            YoutubeDLRequest request = engine.request(media.url, "download-" + id);
            boolean preservingNative = DownloadTuning.hasNativeResume(directory);
            String transferMode = engine.configureDownload(request, media.url, preservingNative, "download-" + id);
            TaskStore.update(this, id, new JSONObject().put("transferReason", preservingNative
                    ? "保留已有断点，使用兼容方式续传" : ""));
            int configuredConnections = DownloadTuning.connections(getSharedPreferences("config", MODE_PRIVATE)
                    .getInt(DownloadTuning.PREFERENCE, DownloadTuning.DEFAULT_CONNECTIONS));
            tracker.setTransferPolicy(transferMode, configuredConnections);
            if (task.optLong("downloadedBytes", 0) <= 0) publishProgress(id, tracker.snapshot());
            // Native transfers preserve pre-0.1.4 partials. Sparse parallel files
            // stay separate so switching modes cannot append to incompatible data.
            File workingDirectory = "parallel-http".equals(transferMode) ? new File(directory, transferMode) : directory;
            if (!workingDirectory.isDirectory() && !workingDirectory.mkdirs()) throw new IOException("无法创建临时下载目录");
            if (!media.twitterApi.isEmpty()) request.addOption("--extractor-args", "twitter:api=" + media.twitterApi);
            if (media.playlistIndex > 0) request.addOption("--playlist-items", String.valueOf(media.playlistIndex));
            request.addOption("--continue");
            request.addOption("--no-overwrites");
            request.addOption("--no-mtime");
            request.addOption("--newline");
            request.addOption("--progress");
            request.addOption("--no-simulate");
            request.addOption("--format", quality.selector);
            request.addOption("--merge-output-format", "mp4/mkv");
            request.addOption("--output", new File(workingDirectory, "%(title).100B [%(id)s].%(ext)s").getAbsolutePath());
            request.addOption("--print", DownloadProgress.PLAN_TEMPLATE);
            request.addOption("--progress-template", DownloadProgress.DOWNLOAD_TEMPLATE);
            request.addOption("--progress-template", DownloadProgress.POST_TEMPLATE);
            request.addOption("--print", "after_move:filepath");
            lastProgressWrite = 0;
            String output;
            Engine.RUNTIME_LOCK.readLock().lockInterruptibly();
            try {
                if (isCancelled(id)) throw new IOException("cancelled");
                try {
                    // The wrapper only dispatches stdout. Merge download stderr so
                    // retries and real source errors reach the same stage model.
                    output = YoutubeDL.getInstance().execute(request, "download-" + id, true, (progress, eta, line) -> {
                        if (line.startsWith(DownloadProgress.PLAN_PREFIX) || line.startsWith(DownloadProgress.DOWNLOAD_PREFIX)) {
                            admission[0].close(); // Metadata is ready; the transfer may continue alongside later inspections.
                        }
                        String safeLine = Engine.cleanLog(line);
                        boolean event = tracker.accept(line);
                        if (!DownloadProgress.isProtocolLine(line)) {
                            if (log.length() > 6000) log.delete(0, log.length() - 4000);
                            log.append(safeLine).append('\n');
                        }
                        DownloadProgress.Snapshot state = tracker.snapshot();
                        long now = System.currentTimeMillis();
                        if (!isCancelled(id) && event && (now - lastProgressWrite > 650 || !state.boundary().equals(lastBoundary[0]))) {
                            lastProgressWrite = now; lastBoundary[0] = state.boundary();
                            publishProgress(id, state);
                            updateNotification(media.title + " · " + state.stageLabel, id, state.progress);
                        }
                        return kotlin.Unit.INSTANCE;
                    }).getOut();
                } catch (Exception failure) {
                    // Upstream constructs exceptions from stderr, which is empty
                    // after redirecting it. Retain the sanitized actual error.
                    if (!isCancelled(id) && log.length() > 0) {
                        String sourceError = "";
                        for (String entry : log.toString().split("\\r?\\n")) if (entry.startsWith("ERROR:")) sourceError = entry;
                        if (!sourceError.isEmpty()) throw new IOException(sourceError, failure);
                        if (failure.getMessage() == null || failure.getMessage().trim().isEmpty())
                            throw new IOException(Engine.cleanLog(log.substring(Math.max(0, log.length() - 2000))), failure);
                    }
                    throw failure;
                }
            } finally { Engine.RUNTIME_LOCK.readLock().unlock(); }
            if (isCancelled(id)) throw new IOException("cancelled");
            SourceScheduler.SHARED.outcomeForUrl(media.url, null);
            File complete = findFinalFile(workingDirectory, output);
            if (!publishProgress(id, tracker.saving())) throw new IOException("stopped");
            updateNotification("正在保存 · " + media.title, id, -1);
            MediaExporter.Result saved = MediaExporter.save(this, complete, () -> isCancelled(id));
            committed = saved; committedBytes = complete.length();
            // Once the public file is committed, report that durable result even if Cancel arrived late.
            JSONObject success = new JSONObject().put("status", "completed").put("progress", 100)
                    .put("phase", "completed").put("stageLabel", "已保存").put("stageProgress", -1)
                    .put("transferComplete", true).put("etaSeconds", -1).put("speedBytesPerSecond", -1)
                    .put("detail", "已保存到 Download/StreamPocket").put("userError", "").put("pauseRequested", false)
                    .put("uri", saved.uri.toString()).put("mime", saved.mime).put("log", "")
                    .put("size", complete.length()).put("completedAt", System.currentTimeMillis());
            TaskStore.update(this, id, success);
            indexSavedDownload(id);
            try { deleteTree(directory); } catch (Exception ignored) { /* Public output is already committed. */ }
        } catch (Throwable failure) {
            if (committed != null) {
                // A late index, bookkeeping or cleanup failure cannot undo a
                // completed public file or make Cancel turn it into a failed task.
                try {
                    TaskStore.update(this, id, new JSONObject().put("status", "completed").put("progress", 100)
                            .put("phase", "completed").put("stageLabel", "已保存").put("stageProgress", -1)
                            .put("detail", "视频已保存，视频库可稍后重新同步").put("userError", "")
                            .put("uri", committed.uri.toString()).put("mime", committed.mime).put("size", committedBytes)
                            .put("completedAt", System.currentTimeMillis()).put("transferComplete", true)
                            .put("speedBytesPerSecond", -1).put("etaSeconds", -1).put("pauseRequested", false).put("libraryIndexPending", true));
                } catch (Exception ignored) { /* Never misreport a committed file as failed. */ }
            } else {
                synchronized (lock) {
                    if (isCancelled(id)) finishStopped(id);
                    else {
                        SourceScheduler.SHARED.outcomeForUrl(task.optString("url"), failure);
                        log.append('\n').append(Engine.cleanLog(failure.toString()));
                        patch(id, "failed", -1, "下载未完成", Engine.userMessage(failure));
                        try { TaskStore.update(this, id, new JSONObject().put("log", log.substring(Math.max(0, log.length() - 6000)))); }
                        catch (Exception ignored) {}
                    }
                }
            }
        } finally {
            if (admission[0] != null) admission[0].close();
            engine.cleanupRequest("download-" + id);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
    }

    private File findFinalFile(File directory, String output) throws Exception {
        String[] lines = output == null ? new String[0] : output.split("\\r?\\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            File candidate = new File(lines[index].trim());
            if (candidate.isFile() && candidate.length() > 0 && directory.getCanonicalFile().equals(candidate.getCanonicalFile().getParentFile()) && !candidate.getName().endsWith(".part")) return candidate;
        }
        throw new IOException("下载内核未返回已完成的视频文件，未将临时片段保存为完整视频");
    }

    private void patch(String id, String status, double progress, String detail, String userError) {
        try { TaskStore.update(this, id, new JSONObject().put("status", status).put("progress", progress).put("detail", detail).put("userError", userError)); }
        catch (Exception ignored) {}
    }

    private boolean publishProgress(String id, DownloadProgress.Snapshot state) {
        synchronized (lock) {
            if (isCancelled(id)) return false;
            JSONObject task = TaskStore.get(this, id);
            if (task == null || "completed".equals(task.optString("status"))) return false;
            boolean resumedBytes = state.downloadedBytes > 0 && state.stageProgress >= 0
                    && ("video".equals(state.phase) || "audio".equals(state.phase) || "media".equals(state.phase));
            boolean processing = state.transferComplete || "merging".equals(state.phase)
                    || "processing".equals(state.phase) || "saving".equals(state.phase);
            if (resumedBytes || processing) awaitingResumeProgress.remove(id);
            try {
                JSONObject patch = state.toJson();
                if (awaitingResumeProgress.contains(id)) {
                    // Format plans, compatibility routing and retry messages do
                    // not confirm a new byte counter. Keep last-attempt totals
                    // until the first actual transfer measurement arrives.
                    for (String key : new String[]{"downloadedBytes", "totalBytes", "progress", "completedStreams", "totalStreams",
                            "totalBytesEstimated", "downloadedBytesEstimated"})
                        if (task.has(key)) patch.put(key, task.get(key));
                    patch.put("progressEstimated", true).put("stageProgress", -1)
                            .put("detail", state.stageLabel + " · 正在确认续传位置，保留上次下载量")
                            .put("speedBytesPerSecond", -1).put("etaSeconds", -1);
                }
                TaskStore.update(this, id, patch);
            }
            catch (Exception ignored) { /* A progress write must not terminate the process-output reader. */ }
            return true;
        }
    }

    private void publishPreparing(String id, DownloadProgress.Snapshot state, JSONObject previous) {
        synchronized (lock) {
            if (isCancelled(id)) return;
            if (previous.optLong("downloadedBytes", 0) <= 0) {
                awaitingResumeProgress.remove(id); publishProgress(id, state); return;
            }
            awaitingResumeProgress.add(id);
            // Keep the last known bytes until real progress confirms the native
            // resume point; metadata preflight is not a second download at zero.
            try { TaskStore.update(this, id, new JSONObject().put("status", "downloading").put("phase", "preparing")
                    .put("stageLabel", "正在检查断点").put("stageProgress", -1).put("progressEstimated", true)
                    .put("detail", "正在检查断点，保留上次记录的下载进度").put("speedBytesPerSecond", -1)
                    .put("etaSeconds", -1).put("activeConnections", -1)); } catch (Exception ignored) {}
        }
    }

    /** Called only after execute/save has unwound; pausing is never retryable early. */
    private void finishStopped(String id) {
        synchronized (lock) {
            JSONObject task = TaskStore.get(this, id);
            if (task == null || "completed".equals(task.optString("status"))) return;
            boolean paused = !cancelled.contains(id) && (pauseRequested.contains(id) || task.optBoolean("pauseRequested"));
            TaskStore.markStopped(this, id, paused ? "paused" : destroyed ? "interrupted" : "cancelled", paused
                    ? "已暂停，点击继续可复用已下载数据" : "已停止；可重试继续");
        }
    }

    private void indexSavedDownload(String id) {
        LibraryStore library = null;
        try {
            library = new LibraryStore(this);
            long entry = library.upsertCompletedDownload(TaskStore.get(this, id));
            TaskStore.update(this, id, new JSONObject().put("libraryIndexPending", entry <= 0));
        } catch (Exception unavailable) {
            try { TaskStore.update(this, id, new JSONObject().put("libraryIndexPending", true)
                    .put("detail", "已保存到 Download/StreamPocket，视频库可稍后重新同步")); }
            catch (Exception ignored) { /* Indexing cannot change the successful download. */ }
        } finally {
            if (library != null) try { library.close(); } catch (Exception ignored) { /* Already committed. */ }
        }
    }

    private Notification notification(String title, String id, double progress) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("拾流 · 下载中")
                .setContentText(title).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, progress < 0 ? 0 : (int) progress, progress < 0);
        if (id != null) {
            JSONObject task = TaskStore.get(this, id);
            if (canPause(task)) {
                Intent pause = new Intent(this, DownloadService.class).setAction(PAUSE).putExtra("id", id);
                PendingIntent pauseAction = PendingIntent.getService(this, id.hashCode() ^ 0x50415553, pause,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.addAction(new Notification.Action.Builder(null, "暂停", pauseAction).build());
            }
            Intent cancel = new Intent(this, DownloadService.class).setAction(CANCEL).putExtra("id", id);
            PendingIntent action = PendingIntent.getService(this, id.hashCode(), cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "取消", action).build());
        }
        return builder.build();
    }

    private void updateNotification(String title, String id, double progress) {
        synchronized (lock) {
            if (destroyed) return;
            if (pauseRequested.contains(id)) { title = "正在暂停，等待下载进程退出…"; progress = -1; }
            else if (cancelled.contains(id)) { title = "正在取消"; progress = -1; }
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(title, id, progress));
        }
    }

    private void finishIfIdle(int startId) {
        synchronized (lock) {
            if (!draining && queue.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId);
            }
        }
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        destroyed = true;
        synchronized (lock) {
            if (active != null) YoutubeDL.getInstance().destroyProcessById("download-" + active);
            for (String id : queue) patch(id, "interrupted", -1, "已达到系统后台运行时限，打开应用后重试", "");
            queue.clear();
        }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        synchronized (lock) {
            if (active != null) YoutubeDL.getInstance().destroyProcessById("download-" + active);
            for (String id : queue) patch(id, "interrupted", -1, "任务已中断，打开应用后重试", "");
            queue.clear();
        }
        worker.shutdownNow(); super.onDestroy();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
