package dev.streampocket;

import java.util.ArrayList;
import java.util.List;

/** Shared queue intent rules; no Android or transfer-engine state. */
final class DownloadQueuePolicy {
    static final String MANUAL = "manual", QUEUE = "queue";
    enum Admission { ANY, RESUME, RETRY_FAILED, RESUME_QUEUE }
    private DownloadQueuePolicy() {}

    static boolean held(String status, String origin) {
        return "paused".equals(status) && QUEUE.equals(origin);
    }

    static boolean waiting(String status, String origin) {
        return "queued".equals(status) || held(status, origin);
    }

    static boolean stoppingQueue(String status, String origin) {
        return "pausing".equals(status) && QUEUE.equals(origin);
    }

    static boolean accepts(String status, String origin, Admission admission) {
        if (admission == Admission.RESUME_QUEUE) return held(status, origin);
        // A repeated enqueue cannot reorder or release an already held task.
        if (held(status, origin)) return false;
        if (admission == Admission.RESUME) return "paused".equals(status);
        if (admission == Admission.RETRY_FAILED)
            return "failed".equals(status) || "interrupted".equals(status);
        return "queued".equals(status) || "paused".equals(status) || "failed".equals(status)
                || "interrupted".equals(status) || "cancelled".equals(status);
    }

    static boolean active(String status) {
        return "queued".equals(status) || "downloading".equals(status) || "merging".equals(status)
                || "saving".equals(status) || "pausing".equals(status) || "cancelling".equals(status);
    }

    static List<String> move(List<String> waiting, String id, boolean next) {
        List<String> result = new ArrayList<>(waiting);
        if (!result.remove(id)) return result;
        if (next) result.add(0, id); else result.add(id);
        return result;
    }
}
