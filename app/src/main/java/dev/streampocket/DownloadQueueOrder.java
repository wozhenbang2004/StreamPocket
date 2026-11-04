package dev.streampocket;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Mirrors the serial worker: active first, accepted enqueue order next, recent history last. */
public final class DownloadQueueOrder {
    private DownloadQueueOrder() {}

    public static List<JSONObject> sorted(List<JSONObject> tasks) {
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject task : tasks) if (task != null) result.add(task);
        result.sort((left, right) -> {
            int group = Integer.compare(group(left), group(right));
            if (group != 0) return group;
            boolean history = group(left) >= 2;
            int time = history ? Long.compare(historyTime(right), historyTime(left))
                    : Long.compare(queueTime(left), queueTime(right));
            if (time != 0) return time;
            int created = history ? Long.compare(right.optLong("createdAt"), left.optLong("createdAt"))
                    : Long.compare(left.optLong("createdAt"), right.optLong("createdAt"));
            return created != 0 ? created : left.optString("id").compareTo(right.optString("id"));
        });
        return result;
    }

    static long nextQueuedAt(List<JSONObject> tasks, long now, long lastAccepted) {
        long latest = Math.max(0, lastAccepted);
        for (JSONObject task : tasks) if (task != null) latest = Math.max(latest, task.optLong("queuedAt", 0));
        return Math.max(now, Math.addExact(latest, 1));
    }

    private static int group(JSONObject task) {
        String status = task.optString("status");
        if (status.equals("downloading") || status.equals("merging") || status.equals("saving")
                || status.equals("cancelling") || status.equals("pausing")) return 0;
        if (DownloadQueuePolicy.waiting(status, task.optString("pauseOrigin"))) return 1;
        return status.equals("paused") ? 2 : 3;
    }
    static long queueTime(JSONObject task) {
        long queued = task.optLong("queuedAt", 0);
        return queued > 0 ? queued : task.optLong("createdAt", 0);
    }
    private static long historyTime(JSONObject task) {
        return Math.max(task.optLong("completedAt", 0), Math.max(task.optLong("queuedAt", 0), task.optLong("createdAt", 0)));
    }
}
