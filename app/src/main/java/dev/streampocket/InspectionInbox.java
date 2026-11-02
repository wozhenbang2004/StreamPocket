package dev.streampocket;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Accepted links and completed results have independent lifetimes. All mutations are on main. */
final class InspectionInbox {
    static final class Job {
        final String id, url;
        String state = "queued", message = "", details = "";
        volatile boolean cancelled;
        Job(String id, String url) { this.id = id; this.url = url; }
        JSONObject json() throws Exception { return new JSONObject().put("id", id).put("url", url).put("state", state)
                .put("message", message).put("details", details); }
    }
    final List<Job> jobs = new ArrayList<>();
    final List<Engine.Media> media = new ArrayList<>();

    int add(List<String> urls) {
        Set<String> known = new LinkedHashSet<>();
        for (Job job : jobs) known.add(LinkBatch.identity(job.url));
        for (Engine.Media item : media) known.add(LinkBatch.identity(item.url));
        int added = 0;
        for (String url : urls) if (known.add(LinkBatch.identity(url))) { jobs.add(new Job(UUID.randomUUID().toString(), url)); added++; }
        return added;
    }
    Job next() { for (Job job : jobs) if (job.state.equals("queued")) return job; return null; }
    int count(String state) { int n = 0; for (Job job : jobs) if (job.state.equals(state)) n++; return n; }
    void complete(Job job, List<Engine.Media> found) {
        if (job != null && (!jobs.contains(job) || job.cancelled)) return;
        Set<String> known = new LinkedHashSet<>();for (Engine.Media item : media) known.add(InspectionBatch.key(item));
        for (Engine.Media item : found) if (known.add(InspectionBatch.key(item))) media.add(item);
        jobs.remove(job);
    }
    void fail(Job job, Throwable failure) {
        if (!jobs.contains(job)) return;
        job.state = "failed"; job.cancelled = false;
        job.message = failure instanceof InterruptedException ? "已取消解析，可以重试" : CoreRules.inspectionMessage(failure, job.url);
        String details = CoreRules.cleanLog(failure.toString()); job.details = details.substring(0, Math.min(details.length(), 6000));
    }
    boolean retry(Job job) {
        if (!jobs.contains(job) || !job.state.equals("failed")) return false;
        jobs.remove(job); job.state = "queued"; job.message = ""; job.details = ""; job.cancelled = false; jobs.add(job); return true;
    }
    int retryFailed() { int n = 0; for (Job job : new ArrayList<>(jobs)) if (retry(job)) n++; return n; }
    void remove(Job job) { job.cancelled = true; jobs.remove(job); }
    String json() {
        try {
            JSONArray pending = new JSONArray(), ready = new JSONArray();
            for (Job job : jobs) pending.put(job.json()); for (Engine.Media item : media) ready.put(item.toJson());
            return new JSONObject().put("schema", 1).put("jobs", pending).put("media", ready).toString();
        } catch (Exception invalid) { throw new IllegalStateException("无法保存解析队列", invalid); }
    }
    void restore(String saved) {
        if (saved == null || saved.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(saved); if (root.optInt("schema") != 1) return;
            List<Job> restored = new ArrayList<>(); List<Engine.Media> ready = new ArrayList<>();
            JSONArray pending = root.optJSONArray("jobs");
            if (pending != null) for (int n = 0; n < pending.length(); n++) {
                JSONObject item = pending.getJSONObject(n); String url = CoreRules.normalizeUrl(item.getString("url"));
                Job job = new Job(item.getString("id"), url);
                job.state = item.optString("state").equals("failed") || item.optString("state").equals("cancelling") ? "failed" : "queued";
                job.message = item.optString("message"); job.details = item.optString("details"); restored.add(job);
            }
            JSONArray results = root.optJSONArray("media");
            if (results != null) for (int n = 0; n < results.length(); n++) ready.add(Engine.Media.fromJson(results.getJSONObject(n)));
            jobs.clear(); jobs.addAll(restored); media.clear(); media.addAll(ready);
        } catch (Exception invalid) { /* Keep the existing in-memory inbox if an old stored payload is invalid. */ }
    }
}
