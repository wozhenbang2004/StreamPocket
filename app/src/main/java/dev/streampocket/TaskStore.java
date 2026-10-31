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
        init(context);
        String id = UUID.randomUUID().toString();
        JSONObject task = new JSONObject().put("id", id).put("title", media.title).put("url", media.url)
                .put("media", media.toJson()).put("quality", quality.toJson()).put("status", "queued")
                .put("progress", -1).put("detail", "等待下载").put("userError", "").put("uri", "").put("mime", "")
                .put("createdAt", System.currentTimeMillis());
        JSONArray tasks = read(context); tasks.put(task); write(context, tasks); return id;
    }

    public static synchronized List<JSONObject> all(Context context) {
        init(context);
        JSONArray tasks = read(context); List<JSONObject> values = new ArrayList<>();
        for (int index = 0; index < tasks.length(); index++) { JSONObject task = tasks.optJSONObject(index); if (task != null) values.add(task); }
        return DownloadQueueOrder.sorted(values);
    }

    /** Called only when the worker actually accepts an enqueue, including a retry. */
    public static synchronized boolean markQueued(Context context, String id) {
        init(context);
        JSONArray tasks = read(context); List<JSONObject> values = new ArrayList<>(); JSONObject selected = null;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index); if (task == null) continue;
            values.add(task); if (task.optString("id").equals(id)) selected = task;
        }
        if (selected == null) return false;
        String oldStatus = selected.optString("status");
        // A second service / rapid tap must never reopen a still-owned process.
        if (!canEnqueue(selected)) return false;
        try {
            android.content.SharedPreferences preferences = context.getSharedPreferences("tasks", Context.MODE_PRIVATE);
            long accepted = DownloadQueueOrder.nextQueuedAt(values, System.currentTimeMillis(), preferences.getLong("lastQueuedAt", 0));
            boolean resume = oldStatus.equals("paused");
            selected.put("queuedAt", accepted).put("status", "queued").put("phase", "queued").put("stageLabel", "等待下载")
                    .put("stageProgress", -1).put("detail", resume ? "等待继续下载，保留已下载数据" : "等待下载")
                    .put("userError", "").put("log", "").put("pauseRequested", false)
                    .put("speedBytesPerSecond", -1).put("etaSeconds", -1).put("activeConnections", -1).put("transferComplete", false);
            if (!resume) selected.put("progress", -1).put("downloadedBytes", 0).put("totalBytes", -1)
                    .put("completedStreams", 0).put("totalStreams", 0).put("progressEstimated", false)
                    .put("totalBytesEstimated", false).put("downloadedBytesEstimated", false);
            return preferences.edit().putString("items", tasks.toString()).putLong("lastQueuedAt", accepted).commit();
        } catch (Exception invalid) { return false; }
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
