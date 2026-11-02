package dev.streampocket;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Small durable queue; active work is explicitly resumable after process death. */
public final class TaskStore {
    private static boolean initialized;
    private TaskStore() {}

    public static synchronized void init(Context context) {
        if (initialized) return;
        JSONArray tasks = read(context);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            String status = task.optString("status");
            if (status.equals("completed")) try { task.put("publicationBusy", false); } catch (Exception ignored) {}
            if (status.equals("pausing") && task.optBoolean("pauseRequested")) {
                try { stopped(task, "paused", "已暂停，点击继续可复用已下载数据"); } catch (Exception ignored) {}
            } else if (status.equals("queued") || status.equals("downloading") || status.equals("merging")
                    || status.equals("saving") || status.equals("cancelling") || status.equals("pausing")) {
                try { stopped(task, "interrupted", "任务已中断，点击重试可继续。"); } catch (Exception ignored) {}
            }
        }
        write(context, tasks); initialized = true;
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
            tasks.put(new JSONObject().put("id", id).put("title", item.title).put("url", item.url)
                    .put("media", item.toJson()).put("quality", qualities.get(n).toJson()).put("status", "queued")
                    .put("progress", -1).put("detail", "等待下载").put("userError", "").put("uri", "").put("mime", "")
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

    /** Called only when the worker actually accepts an enqueue, including a retry. */
    public static synchronized boolean markQueued(Context context, String id) {
        return markQueuedBatch(context, java.util.Collections.singletonList(id)).contains(id);
    }

    /** A batch gets one disk commit and monotonically increasing worker acceptance positions. */
    static synchronized List<String> markQueuedBatch(Context context, List<String> ids) {
        init(context);
        JSONArray tasks = read(context); List<JSONObject> values = new ArrayList<>();
        java.util.Map<String, JSONObject> byId = new java.util.HashMap<>();
        for (int n = 0; n < tasks.length(); n++) {
            JSONObject task = tasks.optJSONObject(n); if (task == null) continue;
            values.add(task); byId.put(task.optString("id"), task);
        }
        android.content.SharedPreferences preferences = context.getSharedPreferences("tasks", Context.MODE_PRIVATE);
        List<String> acceptedIds = new ArrayList<>();
        try {
            long accepted = DownloadQueueOrder.nextQueuedAt(values, System.currentTimeMillis(), preferences.getLong("lastQueuedAt", 0));
            long last = accepted;
            for (String id : new java.util.LinkedHashSet<>(ids)) {
                JSONObject selected = byId.get(id);
                if (!canEnqueue(selected)) continue;
                boolean resume = selected.optString("status").equals("paused");
                String previousStatus = selected.optString("status");
                if (java.util.Arrays.asList("failed","interrupted","cancelled").contains(previousStatus)
                        && selected.optString("uri").isEmpty()
                        && (selected.optJSONArray("attachments")==null || selected.optJSONArray("attachments").length()==0)) {
                    // An explicit retry starts a new unpublished attempt. Paused work keeps its original target.
                    selected.remove("destinationTree");selected.remove("savedLocation");
                }
                selected.put("queuedAt", accepted).put("status", "queued").put("phase", "queued").put("stageLabel", "等待下载")
                        .put("stageProgress", -1).put("detail", resume ? "等待继续下载，保留已下载数据" : "等待下载")
                        .put("userError", "").put("log", "").put("pauseRequested", false)
                        .put("speedBytesPerSecond", -1).put("etaSeconds", -1).put("activeConnections", -1).put("transferComplete", false);
                if (!resume) selected.put("progress", -1).put("downloadedBytes", 0).put("totalBytes", -1)
                        .put("completedStreams", 0).put("totalStreams", 0).put("progressEstimated", false)
                        .put("totalBytesEstimated", false).put("downloadedBytesEstimated", false);
                acceptedIds.add(id); last = accepted; accepted = Math.addExact(accepted, 1);
            }
            if (!acceptedIds.isEmpty() && !preferences.edit().putString("items", tasks.toString()).putLong("lastQueuedAt", last).commit())
                return java.util.Collections.emptyList();
            return acceptedIds;
        } catch (Exception invalid) { return java.util.Collections.emptyList(); }
    }

    static boolean canEnqueue(JSONObject task) {
        if (task == null) return false;
        String status = task.optString("status");
        return status.equals("queued") || status.equals("paused") || status.equals("failed")
                || status.equals("cancelled") || status.equals("interrupted");
    }

    /** Persist the user's intent before signalling a native process to stop. */
    static synchronized boolean markPauseRequested(Context context, String id, boolean processOwned) {
        init(context);
        JSONArray tasks = read(context);
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optString("id").equals(id)) continue;
            if (!DownloadService.canPause(task)) return false;
            try {
                stopped(task, processOwned ? "pausing" : "paused", processOwned
                        ? "正在暂停，等待下载进程退出…" : "已暂停，点击继续可复用已下载数据");
                task.put("pauseRequested", true);
                return context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().putString("items", tasks.toString()).commit();
            } catch (Exception invalid) { return false; }
        }
        return false;
    }

    static synchronized void markStopped(Context context, String id, String status, String detail) {
        init(context);
        JSONArray tasks = read(context);
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optString("id").equals(id) || task.optString("status").equals("completed")) continue;
            try { stopped(task, status, detail); } catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
            write(context, tasks); return;
        }
    }

    private static void stopped(JSONObject task, String status, String detail) throws Exception {
        task.put("status", status).put("stageLabel", status.equals("pausing") ? "正在暂停"
                        : status.equals("paused") ? "已暂停" : status.equals("cancelled") ? "已取消" : "已中断")
                .put("detail", detail).put("userError", "").put("speedBytesPerSecond", -1).put("etaSeconds", -1)
                .put("activeConnections", -1).put("pauseRequested", status.equals("paused") || status.equals("pausing"));
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
            if (task != null && !task.optString("id").equals(id)) retained.put(task);
        }
        write(context, retained);
    }

    /** Final removal after an undo delay; an accepted resume always wins. */
    public static synchronized boolean removeIfInactive(Context context, String id) {
        init(context);
        JSONArray tasks = read(context), retained = new JSONArray();
        boolean found = false;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            if (task.optString("id").equals(id)) {
                String status = task.optString("status");
                if (status.equals("queued") || status.equals("downloading") || status.equals("merging")
                        || status.equals("saving") || status.equals("cancelling") || status.equals("pausing")) return false;
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
