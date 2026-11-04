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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-started foreground queue. Process-death recovery is an explicit retry. */
public final class DownloadService extends Service {
    public static final String ENQUEUE = "dev.streampocket.ENQUEUE";
    public static final String CANCEL = "dev.streampocket.CANCEL";
    public static final String PAUSE = "dev.streampocket.PAUSE";
    public static final String PAUSE_QUEUE = "dev.streampocket.PAUSE_QUEUE";
    public static final String RESUME_QUEUE = "dev.streampocket.RESUME_QUEUE";
    public static final String RESUME = "dev.streampocket.RESUME";
    public static final String RETRY_FAILED = "dev.streampocket.RETRY_FAILED";
    public static final String MOVE_NEXT = "dev.streampocket.MOVE_NEXT";
    public static final String MOVE_LAST = "dev.streampocket.MOVE_LAST";
    private static final String CHANNEL = "downloads";
    private static final int NOTIFICATION = 88;
    private static final DownloadProcessStopper PROCESS_STOPS = new DownloadProcessStopper();
    private final Object lock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<String> cancelled = new HashSet<>();
    private final Set<String> pauseRequested = new HashSet<>();
    private final Set<String> awaitingResumeProgress = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean draining;
    private volatile boolean destroyed;
    private boolean lifecycleStopRequested;
    private String active;
    private DownloadProcessStopper.Attempt activeAttempt;
    private DownloadProcessStopper processStops = PROCESS_STOPS;
    private int lastStartId;
    private long lastProgressWrite;

    @Override public void onCreate() {
        super.onCreate(); TaskStore.init(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示下载状态，传输时可暂停或取消。");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (destroyed) {
            // A new start may arrive while Android is destroying this instance.
            // Do not leave newly created tasks queued on a worker that is stopping.
            if (ENQUEUE.equals(intent.getAction())) {
                java.util.ArrayList<String> rejected = intent.getStringArrayListExtra("ids");
                if (rejected == null) { rejected = new java.util.ArrayList<>(); rejected.add(intent.getStringExtra("id")); }
                for (String item : rejected) {
                    JSONObject task = TaskStore.get(this, item);
                    if (task != null && "queued".equals(task.optString("status")))
                        TaskStore.markStopped(this, item, "interrupted", "下载服务正在停止，点击重试可继续");
                }
            }
            stopSelf(startId); return START_NOT_STICKY;
        }
        String id = intent.getStringExtra("id"), action = intent.getAction();
        java.util.ArrayList<String> ids = intent.getStringArrayListExtra("ids");
        if (ids == null) { ids = new java.util.ArrayList<>(); if (id != null) ids.add(id); }
        ids.removeIf(item -> item == null || TaskStore.get(this, item) == null);
        if (PAUSE.equals(action)) {
            pauseTasks(ids); finishIfIdle(startId); return START_NOT_STICKY;
        }
        if (CANCEL.equals(action)) {
            for (String item : ids) cancel(item);
            finishIfIdle(startId); return START_NOT_STICKY;
        }
        if (PAUSE_QUEUE.equals(action)) {
            pauseQueue(); finishIfIdle(startId); return START_NOT_STICKY;
        }
        if (MOVE_NEXT.equals(action) || MOVE_LAST.equals(action)) {
            moveWaiting(id, MOVE_NEXT.equals(action)); finishIfIdle(startId); return START_NOT_STICKY;
        }
        boolean resumeQueue = RESUME_QUEUE.equals(action);
        DownloadQueuePolicy.Admission admission = RESUME.equals(action) ? DownloadQueuePolicy.Admission.RESUME
                : RETRY_FAILED.equals(action) ? DownloadQueuePolicy.Admission.RETRY_FAILED : DownloadQueuePolicy.Admission.ANY;
        if ((!resumeQueue && !ENQUEUE.equals(action) && !RESUME.equals(action) && !RETRY_FAILED.equals(action))
                || (!resumeQueue && ids.isEmpty())) {
            finishIfIdle(startId); return START_NOT_STICKY;
        }
        // A foreground start acknowledges Android immediately, but the notification
        // must remain attached to the worker's current task, not the newly added one.
        synchronized (lock) {
            String shown = active != null ? active : queue.peekFirst();
            if (shown == null && !ids.isEmpty()) shown = ids.get(0);
            JSONObject current = TaskStore.get(this, shown);
            startForeground(NOTIFICATION, notification(current == null ? "等待下载" : current.optString("title"), shown,
                    current == null ? -1 : current.optDouble("progress", -1)));
            java.util.List<String> candidates = new java.util.ArrayList<>();
            for (String item : ids) if (!item.equals(active) && !queue.contains(item)) candidates.add(item);
            java.util.List<String> accepted = resumeQueue ? TaskStore.resumeQueue(this, active) : TaskStore.markQueuedBatch(this, candidates, admission);
            for (String item : accepted) {
                cancelled.remove(item); pauseRequested.remove(item);
                JSONObject task = TaskStore.get(this, item);
                if (task != null && "queued".equals(task.optString("status")) && !queue.contains(item)) queue.addLast(item);
            }
            if (accepted.isEmpty() && !resumeQueue) for (String item : candidates) {
                JSONObject task = TaskStore.get(this, item);
                if (task != null && DownloadQueuePolicy.accepts(task.optString("status"), task.optString("pauseOrigin"), admission))
                    patch(item, "failed", -1, "无法记录下载队列，请重试", "下载尚未开始。");
            }
            if (!TaskStore.isQueuePaused(this) && !draining && !queue.isEmpty()) { draining = true; worker.execute(this::drain); }
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
            if (!isActive) {
                pauseRequested.remove(id); cancelled.add(id);
                queue.remove(id); TaskStore.markStopped(this, id, "cancelled", "已取消；可重试继续");
            } else {
                if (!TaskStore.markCancelRequested(this, id)) return;
                pauseRequested.remove(id); cancelled.add(id);
            }
        }
        if (isActive) {
            stopActiveProcess(id);
        }
    }

    private void pauseTasks(List<String> ids) {
        String stopping = null;
        synchronized (lock) {
            // The durable request and visible state are installed together, before
            // killing anything. A crash here restores paused, with all bytes kept.
            for (String id : TaskStore.pauseBatch(this, ids, active)) {
                if (!id.equals(active)) queue.remove(id);
                else if ("pausing".equals(TaskStore.get(this, id).optString("status"))) {
                    pauseRequested.add(id); stopping = id;
                }
            }
        }
        if (stopping != null) {
            updateNotification("正在暂停，等待下载进程退出…", stopping, -1);
            stopActiveProcess(stopping);
        }
    }

    private void pauseQueue() {
        String stopping = null;
        synchronized (lock) {
            if (!TaskStore.pauseQueue(this, active, new java.util.ArrayList<>(queue))) return;
            queue.removeIf(id -> TaskStore.heldByQueue(TaskStore.get(this, id)));
            JSONObject task = TaskStore.get(this, active);
            if (task != null && DownloadQueuePolicy.stoppingQueue(task.optString("status"), task.optString("pauseOrigin"))) {
                pauseRequested.add(active); stopping = active;
            }
        }
        if (stopping != null) {
            updateNotification("正在暂停队列，等待当前下载退出…", stopping, -1);
            stopActiveProcess(stopping);
        }
    }

    private void moveWaiting(String id, boolean next) {
        synchronized (lock) {
            if (id == null || id.equals(active)) return;
            List<String> reordered = TaskStore.moveWaiting(this, id, next, new java.util.ArrayList<>(queue));
            Set<String> admitted = new HashSet<>(queue);
            if (reordered.isEmpty()) return;
            queue.clear();
            for (String item : reordered) if (admitted.contains(item)) queue.addLast(item);
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
        DownloadProcessStopper.Attempt attempt;
        synchronized (lock) { attempt = id.equals(active) ? activeAttempt : null; }
        processStops.requestStop(attempt);
    }

    private boolean isCancelled(String id) {
        synchronized (lock) { return destroyed || cancelled.contains(id) || pauseRequested.contains(id); }
    }

    private void drain() {
        while (!destroyed) {
            String id;
            DownloadProcessStopper.Attempt attempt;
            Engine attemptEngine;
            synchronized (lock) {
                if (TaskStore.isQueuePaused(this)) { active = null; draining = false; break; }
                id = queue.poll();
                if (id == null) { active = null; draining = false; break; }
                active = id;
                attemptEngine = new Engine(this);
                attempt = processStops.newAttempt(id, processId -> {
                    // A refresh runs an inspect process. Its Engine belongs only
                    // to this attempt, so an old stop cannot cancel a later one.
                    attemptEngine.cancelInspect();
                    return YoutubeDL.getInstance().destroyProcessById(processId);
                });
                activeAttempt = attempt;
            }
            try { download(id, attempt.processId, attemptEngine); }
            finally {
                processStops.finish(attempt);
                synchronized (lock) {
                    active = null; activeAttempt = null;
                    cancelled.remove(id); pauseRequested.remove(id); awaitingResumeProgress.remove(id);
                }
            }
        }
        main.post(() -> finishIfIdle(lastStartId));
    }

    private void download(String id, String processId, Engine engine) {
        JSONObject task = TaskStore.get(this, id);
        if (task == null) return;
        StringBuilder log = new StringBuilder();
        DownloadProgress tracker = new DownloadProgress();
        String[] lastBoundary = {""};
        PowerManager.WakeLock wakeLock = null;
        MediaExporter.Result committed = null;
        long committedBytes = 0;
        boolean stopped = false;
        String committedLocation = DownloadLocation.DEFAULT_LABEL;
        SourceScheduler.Lease[] admission = {null};
        try {
            DownloadLocation.Target destination = DownloadLocation.forTask(this,task);
            committedLocation = destination.label;
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
                synchronized (lock) {
                    // A late scheduler callback must not overwrite a durable stop request.
                    if (isCancelled(id)) return;
                    try { TaskStore.update(this, id, new JSONObject().put("status", "downloading").put("phase", "preparing")
                            .put("stageLabel", stage).put("detail", stage)); } catch (Exception ignored) {}
                    updateNotification(media.title + " · " + stage, id, -1);
                }
            });
            File root = getExternalFilesDir("downloads");
            if (root == null) root = new File(getFilesDir(), "downloads");
            File directory = new File(root, id);
            YoutubeDLRequest request = engine.request(media.url, processId,!media.publicTwitter);
            boolean preservingNative = DownloadTuning.hasNativeResume(directory);
            String transferMode = engine.configureDownload(request, media.url, preservingNative, processId);
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
            quality.configure(request);
            request.addOption("--output", new File(workingDirectory, "%(title).100B [%(id)s].%(ext)s").getAbsolutePath());
            request.addOption("--print", DownloadProgress.PLAN_TEMPLATE);
            request.addOption("--progress-template", DownloadProgress.DOWNLOAD_TEMPLATE);
            request.addOption("--progress-template", DownloadProgress.POST_TEMPLATE);
            request.addOption("--print", "after_move:filepath");
            if (media.saveCover && !media.album) request.addOption("--write-thumbnail");
            if (media.saveSubtitles && !media.subtitleLanguage.isEmpty() && !media.album) {
                request.addOption("--write-subs"); request.addOption("--write-auto-subs");
                request.addOption("--sub-langs", media.subtitleLanguage); request.addOption("--sub-format", "srt/vtt/best");
            }
            if (media.album && !engine.useResolved(request, media, processId)) {
                admission[0].close();
                List<Engine.Media> refreshed = engine.inspect(media.url, message -> updateNotification(message,id,-1), () -> isCancelled(id));
                Engine.Media selected = null;
                for (Engine.Media item : refreshed) if (item.album && item.id.equals(media.id)) selected = item;
                if (selected == null || !engine.useResolved(request, selected, processId)) throw new IOException("图集信息已失效，请重新解析");
                media.imageCount = selected.imageCount;
                admission[0] = SourceScheduler.SHARED.acquire(media.url, () -> isCancelled(id), message -> updateNotification(message,id,-1));
            }
            boolean resolved = request.hasOption("--load-info-json") || engine.useResolved(request, media, processId);
            String requestScope = engine.requestScope(processId);
            lastProgressWrite = 0;
            String output;
            Engine.RUNTIME_LOCK.readLock().lockInterruptibly();
            boolean runtimeHeld = true;
            try {
                for (int refresh = 0;; refresh++) try {
                    // A refresh may wait for the runtime lock after Stop. Check
                    // again for every execution, not only before the first try.
                    if (isCancelled(id)) throw new IOException("cancelled");
                    // The wrapper only dispatches stdout. Merge download stderr so
                    // retries and real source errors reach the same stage model.
                    output = YoutubeDL.getInstance().execute(request, processId, true, (progress, eta, line) -> {
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
                    if (!media.publicTwitter) new AccountStore(this).noteRequest(media.url, requestScope, null);
                    break;
                } catch (Exception failure) {
                    if (resolved && refresh == 0 && !media.album && !isCancelled(id) && Engine.expiredMedia(log + "\n" + failure)) {
                        admission[0].close();
                        request = Engine.withoutResolved(request);
                        log.setLength(0); updateNotification("媒体地址已失效，正在刷新", id, -1);
                        // A new inspection acquires the source gate before the runtime lock.
                        // Release our read lock so a queued core update cannot form a lock cycle.
                        Engine.RUNTIME_LOCK.readLock().unlock(); runtimeHeld=false;
                        List<Engine.Media> refreshed=engine.inspect(media.url,message->updateNotification(message,id,-1),()->isCancelled(id));
                        Engine.Media selected=null;for(Engine.Media item:refreshed)if(item.id.equals(media.id)&&!item.album)selected=item;
                        if(selected==null||!engine.useResolved(request,selected,processId))throw new IOException("未能刷新所选内容，请重新解析原链接");
                        admission[0]=SourceScheduler.SHARED.acquire(media.url,()->isCancelled(id),message->updateNotification(message,id,-1));
                        Engine.RUNTIME_LOCK.readLock().lockInterruptibly(); runtimeHeld=true;
                        continue;
                    }
                    if (!media.publicTwitter) new AccountStore(this).noteRequest(media.url, requestScope, failure);
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
            } finally { if(runtimeHeld)Engine.RUNTIME_LOCK.readLock().unlock(); }
            if (isCancelled(id)) throw new IOException("cancelled");
            SourceScheduler.SHARED.outcomeForUrl(media.url, null);
            File complete = media.album ? ContentFiles.album(workingDirectory, output, media.imageCount, media.title, media.id, () -> isCancelled(id)) : findFinalFile(workingDirectory, output);
            if (!media.album) MediaValidation.check(complete, media, quality);
            if (!publishProgress(id, tracker.saving())) throw new IOException("stopped");
            updateNotification("正在保存 · " + media.title, id, -1);
            MediaExporter.Result saved = MediaExporter.save(this, complete, () -> isCancelled(id), destination);
            committed = saved; committedBytes = complete.length();
            // Once the public file is committed, report that durable result even if Cancel arrived late.
            JSONObject success = new JSONObject().put("status", "completed").put("progress", 100)
                    .put("phase", "completed").put("stageLabel", "已保存").put("stageProgress", -1)
                    .put("transferComplete", true).put("etaSeconds", -1).put("speedBytesPerSecond", -1)
                    .put("detail", "已保存到 " + destination.label).put("savedLocation",destination.label).put("publicationBusy",true).put("userError", "").put("pauseRequested", false)
                    .put("uri", saved.uri.toString()).put("mime", saved.mime).put("log", "")
                    .put("size", complete.length()).put("completedAt", System.currentTimeMillis());
            TaskStore.update(this, id, success);
            if (!media.album && (media.saveCover || media.saveSubtitles)) {
                try {
                    ContentFiles.Sidecars attachments = ContentFiles.publishSidecars(this, workingDirectory, media, () -> isCancelled(id), savedFiles -> {
                        try {TaskStore.update(this,id,new JSONObject().put("attachments",savedFiles).put("attachmentNote","媒体已保存，正在保存附件"));}
                        catch(Exception error) {throw new IllegalStateException(error);}
                    }, destination);
                    TaskStore.update(this,id,new JSONObject().put("attachments",attachments.saved)
                            .put("attachmentError",attachments.error)
                            .put("attachmentNote", !attachments.warning.isEmpty() ? "媒体已保存；部分附件未完成：" + attachments.warning
                                    : attachments.saved.length() == 0 ? "媒体已保存，源站未返回所选附件" : "已保存 " + attachments.saved.length() + " 个附件"));
                } catch (Exception attachmentFailure) {
                    TaskStore.update(this,id,new JSONObject().put("attachmentNote","媒体已保存；部分附件未完成：" + Engine.userMessage(attachmentFailure)));
                }
            }
            indexSavedDownload(id);
            try { deleteTree(directory); } catch (Exception ignored) { /* Public output is already committed. */ }
        } catch (Throwable failure) {
            if (committed != null) {
                // A late index, bookkeeping or cleanup failure cannot undo a
                // completed public file or make Cancel turn it into a failed task.
                try {
                    TaskStore.update(this, id, new JSONObject().put("status", "completed").put("progress", 100)
                            .put("phase", "completed").put("stageLabel", "已保存").put("stageProgress", -1)
                            .put("detail", "已保存到 " + committedLocation + "，视频库可稍后重新同步").put("savedLocation",committedLocation).put("userError", "")
                            .put("uri", committed.uri.toString()).put("mime", committed.mime).put("size", committedBytes)
                            .put("completedAt", System.currentTimeMillis()).put("transferComplete", true)
                            .put("speedBytesPerSecond", -1).put("etaSeconds", -1).put("pauseRequested", false).put("libraryIndexPending", true));
                } catch (Exception ignored) { /* Never misreport a committed file as failed. */ }
            } else {
                synchronized (lock) {
                    if (isCancelled(id)) stopped = true;
                    else {
                        SourceScheduler.SHARED.outcomeForUrl(task.optString("url"), failure);
                        log.append('\n').append(Engine.cleanLog(failure.toString()));
                        patch(id, "failed", -1, "下载未完成", CoreRules.userMessage(failure, task.optString("url")));
                        try { TaskStore.update(this, id, new JSONObject().put("log", log.substring(Math.max(0, log.length() - 6000)))); }
                        catch (Exception ignored) {}
                    }
                }
            }
        } finally {
            try {TaskStore.update(this,id,new JSONObject().put("publicationBusy",false));} catch(Exception ignored) {}
            if (admission[0] != null) admission[0].close();
            engine.cleanupRequest(processId);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (stopped) finishStopped(id);
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
        JSONObject task = TaskStore.get(this, id);
        if (task != null && !task.optString("mime", "video/mp4").startsWith("video/")) {
            try { TaskStore.update(this, id, new JSONObject().put("libraryIndexPending", false)); } catch (Exception ignored) {}
            return;
        }
        try {
            library = new LibraryStore(this);
            long entry = library.upsertCompletedDownload(TaskStore.get(this, id));
            TaskStore.update(this, id, new JSONObject().put("libraryIndexPending", entry <= 0));
        } catch (Exception unavailable) {
            try { TaskStore.update(this, id, new JSONObject().put("libraryIndexPending", true)
                    .put("detail", "已保存到 " + (task==null?DownloadLocation.DEFAULT_LABEL:task.optString("savedLocation",DownloadLocation.DEFAULT_LABEL)) + "，视频库可稍后重新同步")); }
            catch (Exception ignored) { /* Indexing cannot change the successful download. */ }
        } finally {
            if (library != null) try { library.close(); } catch (Exception ignored) { /* Already committed. */ }
        }
    }

    private Notification notification(String title, String id, double progress) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
        // Acknowledge the system's deadline before persistence. The third-party
        // process stop runs separately because its native cleanup can block.
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        stopWork("已达到系统后台运行时限，打开应用后重试");
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        stopWork("任务已中断，打开应用后重试");
        super.onDestroy();
    }

    private void stopWork(String detail) {
        DownloadProcessStopper.Attempt attempt;
        synchronized (lock) {
            if (!lifecycleStopRequested) {
                lifecycleStopRequested = true;
                TaskStore.markServiceStopping(this, active, new java.util.ArrayList<>(queue), detail);
                queue.clear();
            }
            attempt = activeAttempt;
        }
        processStops.requestStop(attempt);
        // Interrupting wrapper.execute makes it return before its output readers
        // and native children are known to have exited. Keep ownership until unwind.
        worker.shutdown();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
