package dev.streampocket;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Small durable queue; foreground recovery honors the last durable user intent. */
public final class TaskStore {
    private static boolean initialized;
    private TaskStore() {}

    public static synchronized void init(Context context) {
        if (initialized) return;
        JSONArray tasks = read(context);
        boolean held = context.getSharedPreferences("tasks", Context.MODE_PRIVATE).getBoolean("queuePaused", false);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            String status = task.optString("status");
            if (status.equals("completed")) try { task.put("publicationBusy", false); } catch (Exception ignored) {}
            String recovered = DownloadRecovery.restartStatus(status, task.optString(DownloadRecovery.INTENT),
                    task.optBoolean("pauseRequested"), held);
            if (!recovered.equals(status)) try {
                // A legacy cancelling record is ambiguous. It has no RUN intent,
                // so it remains available only for an explicit retry.
                if ("interrupted".equals(recovered)) task.put("recoveryPhase", task.optString("phase"));
                stopped(task, recovered, "cancelled".equals(recovered) ? "已取消；可重试继续"
                        : "paused".equals(recovered) ? "已暂停，点击继续可复用已下载数据" : "任务已中断，点击重试可继续。");
                if (held && status.equals("queued")) task.put("pauseOrigin", DownloadQueuePolicy.QUEUE);
            } catch (Exception ignored) {}
            task.remove("recoveryQueued");
        }
        // Recovery must not start from a transition that only exists in memory.
        initialized = context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit();
    }

    public static synchronized String create(Context context, Engine.Media media, Engine.Quality quality) throws Exception {
        return createBatch(context, java.util.Collections.singletonList(media), java.util.Collections.singletonList(quality)).get(0);
    }

    /** Accept a pasted batch durably as one operation, in the same order as its results. */
    public static synchronized ArrayList<String> createBatch(Context context, List<Engine.Media> media, List<Engine.Quality> qualities) throws Exception {
        init(context);
        if (media.isEmpty() || media.size() != qualities.size()) throw new IllegalArgumentException("无效下载批次");
        JSONArray tasks = read(context);
        ArrayList<String> ids = new ArrayList<>();
        List<JSONObject> existing = new ArrayList<>();
        for (int n = 0; n < tasks.length(); n++) { JSONObject task = tasks.optJSONObject(n); if (task != null) existing.add(task); }
        android.content.SharedPreferences preferences = context.getSharedPreferences("tasks", Context.MODE_PRIVATE);
        long queuedAt = DownloadQueueOrder.nextQueuedAt(existing, System.currentTimeMillis(), preferences.getLong("lastQueuedAt", 0));
        long last = queuedAt;
        for (int n = 0; n < media.size(); n++) {
            Engine.Media item = media.get(n);
            String id = UUID.randomUUID().toString(); ids.add(id);
            String outputPattern = "%(title).100B [%(id)s].%(ext)s";
            if (!item.directFile() && !item.namingTemplate.isEmpty())
                outputPattern = NamingTemplate.outputPattern(NamingTemplate.fields(item.title, item.id,
                        item.uploader, item.platform, item.playlistIndex), item.namingTemplate);
            tasks.put(new JSONObject().put("id", id).put("title", item.title).put("url", item.url)
                    .put("media", item.toJson()).put("quality", qualities.get(n).toJson()).put("status", "queued")
                    .put("kind", item.kind).put("presetId", item.presetId).put("presetName", item.presetName)
                    .put("namingTemplate", item.namingTemplate).put("outputPattern", outputPattern)
                    .put("progress", -1).put("detail", "等待下载").put("userError", "").put("uri", "").put("mime", "")
                    .put("phase", "queued").put(DownloadRecovery.INTENT, DownloadRecovery.RUN)
                    .put("createdAt", System.currentTimeMillis()).put("queuedAt", queuedAt));
            last = queuedAt; queuedAt = Math.addExact(queuedAt, 1);
        }
        if (!preferences.edit().putString("items", tasks.toString()).putLong("lastQueuedAt", last).commit())
            throw new java.io.IOException("无法保存下载批次，请重试。");
        return ids;
    }

    public static synchronized List<JSONObject> all(Context context) {
        init(context);
        JSONArray tasks = read(context); List<JSONObject> values = new ArrayList<>();
        for (int index = 0; index < tasks.length(); index++) { JSONObject task = tasks.optJSONObject(index); if (task != null) values.add(task); }
        return DownloadQueueOrder.sorted(values);
    }

    /** Requeue only a known interrupted transfer, retaining its original target, bytes and FIFO position. */
    static synchronized List<String> recoverPending(Context context, java.util.Set<String> owned) {
        init(context);
        if (!initialized || isQueuePaused(context)) return java.util.Collections.emptyList();
        JSONArray tasks = read(context);
        String previous = tasks.toString();
        List<JSONObject> eligible = new ArrayList<>();
        try {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null || !recoverable(task, owned.contains(task.optString("id")))) continue;
                task.put("status", "queued").put("phase", "queued").put("stageLabel", "等待恢复下载")
                        .put("detail", "等待恢复下载，保留原保存位置和已下载数据").put("userError", "")
                        .put("stageProgress", -1).put("speedBytesPerSecond", -1).put("etaSeconds", -1)
                        .put("activeConnections", -1).put("pauseRequested", false).put("recoveryQueued", true);
                eligible.add(task);
            }
            if (eligible.isEmpty()) return java.util.Collections.emptyList();
            if (!context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit()) {
                context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", previous).commit();
                return java.util.Collections.emptyList();
            }
            List<String> ids = new ArrayList<>();
            for (JSONObject task : DownloadQueueOrder.sorted(eligible)) ids.add(task.optString("id"));
            return ids;
        } catch (Exception invalid) { return java.util.Collections.emptyList(); }
    }

    private static boolean recoverable(JSONObject task, boolean owned) {
        return DownloadRecovery.canRecover(task.optString("status"), task.optString(DownloadRecovery.INTENT),
                task.optString("recoveryPhase", task.optString("phase")), task.optBoolean("transferComplete"),
                task.optBoolean("publicationStarted"), !task.optString("uri").isEmpty()
                        || (task.optJSONArray("attachments") != null && task.optJSONArray("attachments").length() > 0), false, owned);
    }

    /** Service acceptance is separate from recovery selection and cannot release a later pause/cancel. */
    static synchronized List<String> acceptRecovered(Context context, List<String> ids) {
        init(context);
        if (!initialized || isQueuePaused(context)) return java.util.Collections.emptyList();
        JSONArray tasks = read(context); String previous = tasks.toString(); List<String> accepted = new ArrayList<>();
        try {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null || !ids.contains(task.optString("id")) || !task.optBoolean("recoveryQueued")
                        || !"queued".equals(task.optString("status")) || !DownloadRecovery.RUN.equals(task.optString(DownloadRecovery.INTENT))) continue;
                task.remove("recoveryQueued"); task.remove("recoveryPhase"); accepted.add(task.optString("id"));
            }
            if (!accepted.isEmpty() && !context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit()
                    .putString("items", tasks.toString()).commit()) {
                context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", previous).commit();
                return java.util.Collections.emptyList();
            }
            List<String> ordered = new ArrayList<>();
            for (String id : ids) if (accepted.contains(id) && !ordered.contains(id)) ordered.add(id);
            return ordered;
        } catch (Exception invalid) { return java.util.Collections.emptyList(); }
    }

    static synchronized void recoveryStartFailed(Context context, List<String> ids) {
        JSONArray tasks = read(context);
        try {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task != null && ids.contains(task.optString("id")) && task.optBoolean("recoveryQueued")
                        && "queued".equals(task.optString("status"))) {
                    stopped(task, "interrupted", "暂时无法恢复下载，回到应用后重试"); task.remove("recoveryQueued");
                }
            }
            context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit();
        } catch (Exception ignored) { /* The next cold start will retain this RUN intent. */ }
    }

    /** Commit a replay barrier before a public file can be created. */
    static synchronized boolean beginPublication(Context context, String id) {
        JSONArray tasks = read(context);
        try {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null || !id.equals(task.optString("id")) || "completed".equals(task.optString("status"))) continue;
                if (!DownloadRecovery.RUN.equals(task.optString(DownloadRecovery.INTENT))
                        || task.optBoolean("pauseRequested") || "cancelling".equals(task.optString("status"))) return false;
                task.put("publicationStarted", true);
                return context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit();
            }
        } catch (Exception ignored) { /* Never publish without the durable replay barrier. */ }
        return false;
    }

    /** Called only when the worker actually accepts an enqueue, including a retry. */
    public static synchronized boolean markQueued(Context context, String id) {
        return markQueuedBatch(context, java.util.Collections.singletonList(id)).contains(id);
    }

    /** A batch gets one disk commit and monotonically increasing worker acceptance positions. */
    static synchronized List<String> markQueuedBatch(Context context, List<String> ids) {
        return markQueuedBatch(context, ids, DownloadQueuePolicy.Admission.ANY, false);
    }

    static synchronized List<String> markQueuedBatch(Context context, List<String> ids, DownloadQueuePolicy.Admission admission) {
        return markQueuedBatch(context, ids, admission, false);
    }

    public static synchronized boolean isQueuePaused(Context context) {
        init(context);
        return context.getSharedPreferences("tasks", Context.MODE_PRIVATE).getBoolean("queuePaused", false);
    }

    static boolean heldByQueue(JSONObject task) {
        return task != null && DownloadQueuePolicy.held(task.optString("status"), task.optString("pauseOrigin"));
    }

    /** The gate and all resumed records are committed together. A live stop is never a resume. */
    static synchronized List<String> resumeQueue(Context context, String activeId) {
        init(context);
        if (!isQueuePaused(context)) return java.util.Collections.emptyList();
        List<JSONObject> tasks = all(context);
        for (JSONObject task : tasks) {
            if (DownloadQueuePolicy.stoppingQueue(task.optString("status"), task.optString("pauseOrigin"))
                    || (task.optString("id").equals(activeId) && heldByQueue(task)))
                return java.util.Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        tasks.sort((left, right) -> Long.compare(DownloadQueueOrder.queueTime(left), DownloadQueueOrder.queueTime(right)));
        for (JSONObject task : tasks) if (heldByQueue(task)) ids.add(task.optString("id"));
        return markQueuedBatch(context, ids, DownloadQueuePolicy.Admission.RESUME_QUEUE, true);
    }

    private static List<String> markQueuedBatch(Context context, List<String> ids,
            DownloadQueuePolicy.Admission admission, boolean releaseQueue) {
        init(context);
        JSONArray tasks = read(context); List<JSONObject> values = new ArrayList<>();
        java.util.Map<String, JSONObject> byId = new java.util.HashMap<>();
        for (int n = 0; n < tasks.length(); n++) {
            JSONObject task = tasks.optJSONObject(n); if (task == null) continue;
            values.add(task); byId.put(task.optString("id"), task);
        }
        android.content.SharedPreferences preferences = context.getSharedPreferences("tasks", Context.MODE_PRIVATE);
        boolean held = !releaseQueue && preferences.getBoolean("queuePaused", false);
        List<String> acceptedIds = new ArrayList<>();
        try {
            long accepted = DownloadQueueOrder.nextQueuedAt(values, System.currentTimeMillis(), preferences.getLong("lastQueuedAt", 0));
            long last = accepted;
            for (String id : new java.util.LinkedHashSet<>(ids)) {
                JSONObject selected = byId.get(id);
                if (selected == null || !DownloadQueuePolicy.accepts(selected.optString("status"), selected.optString("pauseOrigin"), admission)) continue;
                boolean resume = selected.optString("status").equals("paused");
                String previousStatus = selected.optString("status");
                if (java.util.Arrays.asList("failed","interrupted","cancelled").contains(previousStatus)
                        && selected.optString("uri").isEmpty()
                        && (selected.optJSONArray("attachments")==null || selected.optJSONArray("attachments").length()==0)) {
                    // An explicit retry starts a new unpublished attempt. Paused work keeps its original target.
                    selected.remove("destinationTree");selected.remove("savedLocation");
                }
                selected.put("queuedAt", accepted).put("status", held ? "paused" : "queued").put("phase", "queued")
                        .put("stageLabel", held ? "等待继续队列" : "等待下载")
                        .put("stageProgress", -1).put("detail", held ? "队列已暂停，继续队列后开始" : resume ? "等待继续下载，保留已下载数据" : "等待下载")
                        .put("userError", "").put("log", "").put("pauseRequested", held)
                        .put("speedBytesPerSecond", -1).put("etaSeconds", -1).put("activeConnections", -1).put("transferComplete", false)
                        .put(DownloadRecovery.INTENT, held ? DownloadRecovery.PAUSE : DownloadRecovery.RUN);
                selected.remove("recoveryQueued"); selected.remove("recoveryPhase"); selected.remove("publicationStarted");
                if (held) selected.put("pauseOrigin", DownloadQueuePolicy.QUEUE); else selected.remove("pauseOrigin");
                if (!resume) selected.put("progress", -1).put("downloadedBytes", 0).put("totalBytes", -1)
                        .put("completedStreams", 0).put("totalStreams", 0).put("progressEstimated", false)
                        .put("totalBytesEstimated", false).put("downloadedBytesEstimated", false);
                acceptedIds.add(id); last = accepted; accepted = Math.addExact(accepted, 1);
            }
            if (!acceptedIds.isEmpty() || releaseQueue) {
                android.content.SharedPreferences.Editor edit = preferences.edit().putString("items", tasks.toString());
                if (!acceptedIds.isEmpty()) edit.putLong("lastQueuedAt", last);
                if (releaseQueue) edit.putBoolean("queuePaused", false);
                if (!edit.commit()) return java.util.Collections.emptyList();
            }
            return acceptedIds;
        } catch (Exception invalid) { return java.util.Collections.emptyList(); }
    }

    static boolean canEnqueue(JSONObject task) {
        if (task == null) return false;
        String status = task.optString("status");
        return status.equals("queued") || status.equals("paused") || status.equals("failed")
                || status.equals("cancelled") || status.equals("interrupted");
    }

    /** Persist before signalling. A manual pause also overrides a previous queue hold. */
    static synchronized List<String> pauseBatch(Context context, List<String> ids, String activeId) {
        List<String> accepted = new ArrayList<>();
        return pause(context, ids, activeId, false, accepted) ? accepted : java.util.Collections.emptyList();
    }

    static synchronized boolean pauseQueue(Context context, String activeId, List<String> waitingIds) {
        List<String> ids = new ArrayList<>(waitingIds);
        if (activeId != null) ids.add(activeId);
        return pause(context, ids, activeId, true, new ArrayList<>());
    }

    private static boolean pause(Context context, List<String> ids, String activeId, boolean wholeQueue, List<String> accepted) {
        init(context);
        JSONArray tasks = read(context);
        java.util.Set<String> selected = new java.util.HashSet<>(ids);
        if (wholeQueue) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            // A foreground recovery may have committed before its start Intent
            // reaches the service. The global pause also owns these waiters.
            if (task != null && task.optBoolean("recoveryQueued") && "queued".equals(task.optString("status")))
                selected.add(task.optString("id"));
        }
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !selected.contains(task.optString("id"))) continue;
            boolean owned = task.optString("id").equals(activeId);
            boolean manualHold = !wholeQueue && heldByQueue(task);
            if (!manualHold && (!DownloadService.canPause(task) || (!owned && !"queued".equals(task.optString("status"))))) continue;
            try {
                boolean running = owned && !manualHold;
                stopped(task, running ? "pausing" : "paused", running
                        ? "正在暂停，等待下载进程退出…" : wholeQueue ? "队列已暂停，继续队列后开始" : "已暂停，点击继续可复用已下载数据");
                task.put("pauseRequested", true).put("pauseOrigin", wholeQueue ? DownloadQueuePolicy.QUEUE : DownloadQueuePolicy.MANUAL);
                accepted.add(task.optString("id"));
            } catch (Exception invalid) { return false; }
        }
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString());
        if (wholeQueue) edit.putBoolean("queuePaused", true);
        return edit.commit();
    }

    /** Reorder only waiting work, in one commit. The service applies the returned order under its lock. */
    static synchronized List<String> moveWaiting(Context context, String id, boolean next, List<String> waitingIds) {
        init(context);
        JSONArray tasks = read(context); List<JSONObject> waiting = new ArrayList<>(), all = new ArrayList<>();
        for (int n = 0; n < tasks.length(); n++) {
            JSONObject task = tasks.optJSONObject(n); if (task == null) continue;
            all.add(task);
            if (heldByQueue(task) || ("queued".equals(task.optString("status")) && waitingIds.contains(task.optString("id")))) waiting.add(task);
        }
        waiting.sort((left, right) -> Long.compare(DownloadQueueOrder.queueTime(left), DownloadQueueOrder.queueTime(right)));
        List<String> order = new ArrayList<>(); java.util.Map<String, JSONObject> byId = new java.util.HashMap<>();
        for (JSONObject task : waiting) { order.add(task.optString("id")); byId.put(task.optString("id"), task); }
        if (!order.contains(id)) return java.util.Collections.emptyList();
        List<String> reordered = DownloadQueuePolicy.move(order, id, next);
        if (reordered.equals(order)) return order;
        android.content.SharedPreferences preferences = context.getSharedPreferences("tasks", Context.MODE_PRIVATE);
        try {
            long position = DownloadQueueOrder.nextQueuedAt(all, System.currentTimeMillis(), preferences.getLong("lastQueuedAt", 0));
            long last = position;
            for (String item : reordered) { byId.get(item).put("queuedAt", position); last = position; position = Math.addExact(position, 1); }
            return preferences.edit().putString("items", tasks.toString()).putLong("lastQueuedAt", last).commit()
                    ? reordered : java.util.Collections.emptyList();
        } catch (Exception invalid) { return java.util.Collections.emptyList(); }
    }

    /** Cancel is durable before the service signals the process, but is not yet retryable. */
    static synchronized boolean markCancelRequested(Context context, String id) {
        init(context);
        JSONArray tasks = read(context);
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optString("id").equals(id) || !canRequestStop(task.optString("status"))) continue;
            try {
                stopped(task, "cancelling", "正在取消，等待下载进程退出…");
                task.put("stageLabel", "正在取消").put(DownloadRecovery.INTENT, DownloadRecovery.CANCEL);
                return context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit();
            } catch (Exception invalid) { return false; }
        }
        return false;
    }

    /** A stopping service releases waiting work, while its active process retains ownership. */
    static synchronized boolean markServiceStopping(Context context, String activeId, List<String> waitingIds, String detail) {
        init(context);
        JSONArray tasks = read(context);
        try {
            for (int index = 0; index < tasks.length(); index++) {
                JSONObject task = tasks.optJSONObject(index);
                if (task == null || "completed".equals(task.optString("status"))) continue;
                String id = task.optString("id");
                String status = task.optString("status");
                if (id.equals(activeId) && canRequestStop(status)) {
                    // Execution may have finished while the service still holds its bookkeeping ID.
                    // Never reopen a terminal task after its final cleanup has already returned.
                    boolean paused = task.optBoolean("pauseRequested");
                    stopped(task, paused ? "pausing" : "cancelling", detail + "；正在等待下载进程退出");
                    task.put("stageLabel", paused ? "正在暂停" : "正在停止");
                } else if (waitingIds.contains(id) && "queued".equals(task.optString("status"))) {
                    stopped(task, "interrupted", detail);
                }
            }
            return context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit();
        } catch (Exception invalid) { return false; }
    }

    private static boolean canRequestStop(String status) {
        return "queued".equals(status) || "downloading".equals(status) || "merging".equals(status)
                || "saving".equals(status) || "pausing".equals(status) || "cancelling".equals(status);
    }

    static synchronized void markStopped(Context context, String id, String status, String detail) {
        markStoppedCommitted(context, id, status, detail);
    }

    static synchronized boolean markStoppedCommitted(Context context, String id, String status, String detail) {
        init(context);
        JSONArray tasks = read(context);
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optString("id").equals(id) || task.optString("status").equals("completed")) continue;
            try { stopped(task, status, detail); } catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
            return context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit();
        }
        return false;
    }

    private static void stopped(JSONObject task, String status, String detail) throws Exception {
        if ("paused".equals(status) || "pausing".equals(status)) task.put(DownloadRecovery.INTENT, DownloadRecovery.PAUSE);
        else if ("cancelled".equals(status)) task.put(DownloadRecovery.INTENT, DownloadRecovery.CANCEL);
        task.remove("recoveryQueued");
        task.put("status", status).put("stageLabel", status.equals("pausing") ? "正在暂停"
                        : status.equals("paused") ? "已暂停" : status.equals("cancelled") ? "已取消" : "已中断")
                .put("detail", detail).put("userError", "").put("speedBytesPerSecond", -1).put("etaSeconds", -1)
                .put("activeConnections", -1).put("pauseRequested", status.equals("paused") || status.equals("pausing"));
        if (!status.equals("paused") && !status.equals("pausing")) task.remove("pauseOrigin");
    }

    public static synchronized JSONObject get(Context context, String id) {
        init(context);
        JSONArray tasks = read(context);
        for (int index = 0; index < tasks.length(); index++) { JSONObject task = tasks.optJSONObject(index); if (task != null && task.optString("id").equals(id)) return task; }
        return null;
    }

    public static synchronized void update(Context context, String id, JSONObject patch) {
        init(context);
        JSONArray tasks = read(context);
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optString("id").equals(id)) continue;
            Iterator<String> keys = patch.keys();
            try { while (keys.hasNext()) { String key = keys.next(); task.put(key, patch.get(key)); } } catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
            write(context, tasks); return;
        }
    }

    static synchronized void pinDestination(Context context, String id, DownloadLocation.Target target) throws Exception {
        JSONArray tasks = strictRead(context);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null || !id.equals(task.optString("id"))) continue;
            task.put("destinationTree", target.tree == null ? "" : target.tree.toString()).put("savedLocation", target.label);
            commit(context, tasks); return;
        }
        throw new java.io.IOException("下载任务已不存在，未保存到其他位置");
    }

    /** URI-only rewrite. Task identities, playback metadata and attachment order stay intact. */
    static synchronized void relocateUri(Context context, String from, String to) throws Exception {
        relocateUri(context,from,to,null,null);
    }

    static synchronized void relocateUri(Context context, String from, String to, String location, String target) throws Exception {
        JSONArray tasks = strictRead(context);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            if (from.equals(task.optString("uri"))) {
                task.put("uri", to);
                if(location!=null)task.put("savedLocation",location).put("destinationTree","default".equals(target)?"":target);
            }
            JSONArray attachments = task.optJSONArray("attachments");
            if (attachments != null) for (int n = 0; n < attachments.length(); n++) {
                JSONObject file = attachments.optJSONObject(n);
                if (file != null && from.equals(file.optString("uri"))) file.put("uri", to);
            }
        }
        commit(context, tasks);
    }

    static synchronized boolean referencesUri(Context context, String uri) throws Exception {
        JSONArray tasks = strictRead(context);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            if (uri.equals(task.optString("uri"))) return true;
            JSONArray files = task.optJSONArray("attachments");
            if (files != null) for (int n = 0; n < files.length(); n++) {
                JSONObject file = files.optJSONObject(n); if (file != null && uri.equals(file.optString("uri"))) return true;
            }
        }
        return false;
    }

    private static JSONArray strictRead(Context context) throws Exception {
        return new JSONArray(context.getSharedPreferences("tasks", Context.MODE_PRIVATE).getString("items", "[]"));
    }

    private static void commit(Context context, JSONArray tasks) throws java.io.IOException {
        if (!context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit())
            throw new java.io.IOException("下载记录未能持久保存，原文件已保留");
    }

    public static synchronized void remove(Context context, String id) {
        init(context);
        JSONArray tasks = read(context), retained = new JSONArray();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            if (task.optString("id").equals(id)) {
                if (!preserveSubtitlesBeforeRemoval(context, task)) return;
            } else retained.put(task);
        }
        write(context, retained);
    }

    /** Preserve old-version attachment metadata before its last queue copy is removed. */
    private static boolean preserveSubtitlesBeforeRemoval(Context context, JSONObject task) {
        JSONArray attachments = task.optJSONArray("attachments");
        if (!"completed".equals(task.optString("status")) || attachments == null || attachments.length() == 0) return true;
        try (LibraryStore library = new LibraryStore(context)) {
            library.preserveCompletedSubtitles(task);
            return true;
        } catch (RuntimeException unavailable) {
            // A failed library migration/write must leave the original attachments recoverable.
            return false;
        }
    }

    /** Final removal after an undo delay; an accepted resume always wins. */
    public static synchronized boolean removeIfInactive(Context context, String id) {
        return removeIfInactive(context, id, -1);
    }

    /** An explicit resume into a held queue also supersedes an older removal request. */
    static synchronized boolean removeIfInactive(Context context, String id, long expectedQueuedAt) {
        // Serialize metadata with mover commits without waiting on its whole-file copy lock.
        init(context);
        JSONArray tasks = read(context), retained = new JSONArray();
        boolean found = false;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            if (task.optString("id").equals(id)) {
                String status = task.optString("status");
                if (task.optBoolean("publicationBusy") || (expectedQueuedAt >= 0 && task.optLong("queuedAt", 0) != expectedQueuedAt)) return false;
                if (status.equals("queued") || status.equals("downloading") || status.equals("merging")
                        || status.equals("saving") || status.equals("cancelling") || status.equals("pausing")) return false;
                if (!preserveSubtitlesBeforeRemoval(context, task)) return false;
                found = true;
            } else retained.put(task);
        }
        return found && context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", retained.toString()).commit();
    }

    private static JSONArray read(Context context) {
        try { return new JSONArray(context.getSharedPreferences("tasks", Context.MODE_PRIVATE).getString("items", "[]")); }
        catch (Exception invalid) { return new JSONArray(); }
    }
    private static void write(Context context, JSONArray tasks) {
        context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).apply();
    }
}
