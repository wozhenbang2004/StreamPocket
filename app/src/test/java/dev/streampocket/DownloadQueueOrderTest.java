package dev.streampocket;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DownloadQueueOrderTest {
    @Test public void retryGoesBehindTasksAlreadyWaitingEvenWhenCreatedEarlier() throws Exception {
        JSONObject retry = task("old-retry", "queued", 1, 300);
        JSONObject first = task("first", "queued", 100, 100);
        JSONObject second = task("second", "queued", 200, 200);
        assertEquals(Arrays.asList("first", "second", "old-retry"), ids(DownloadQueueOrder.sorted(Arrays.asList(retry, second, first))));
    }

    @Test public void activeTaskPrecedesFifoQueueAndRecentHistoryFollows() throws Exception {
        JSONObject active = task("active", "downloading", 1, 1);
        JSONObject queued = task("queued", "queued", 20, 20);
        JSONObject old = task("old", "completed", 10, 10).put("completedAt", 30);
        JSONObject recent = task("recent", "completed", 2, 2).put("completedAt", 40);
        assertEquals(Arrays.asList("active", "queued", "recent", "old"), ids(DownloadQueueOrder.sorted(Arrays.asList(old, queued, recent, active))));
        for (String phase : Arrays.asList("merging", "saving", "cancelling", "pausing")) {
            active.put("status", phase);
            assertEquals("active", DownloadQueueOrder.sorted(Arrays.asList(queued, active)).get(0).getString("id"));
        }
    }

    @Test public void pausedIsNotAnAutomaticWaiterAndResumedTaskUsesNewQueuePosition() throws Exception {
        JSONObject paused = task("paused", "paused", 1, 1);
        JSONObject waiting = task("waiting", "queued", 2, 2);
        assertEquals(Arrays.asList("waiting", "paused"), ids(DownloadQueueOrder.sorted(Arrays.asList(paused, waiting))));
        paused.put("status", "queued").put("queuedAt", DownloadQueueOrder.nextQueuedAt(Arrays.asList(paused, waiting), 2, 2));
        assertEquals(Arrays.asList("waiting", "paused"), ids(DownloadQueueOrder.sorted(Arrays.asList(paused, waiting))));
    }

    @Test public void oldRecordsUseCreationTimeAndTiesHaveStableIds() throws Exception {
        JSONObject early = new JSONObject().put("id", "early").put("status", "queued").put("createdAt", 1);
        JSONObject a = task("a", "queued", 10, 10), b = task("b", "queued", 10, 10);
        List<JSONObject> original = new ArrayList<>(Arrays.asList(b, early, a));
        assertEquals(Arrays.asList("early", "a", "b"), ids(DownloadQueueOrder.sorted(original)));
        assertEquals("Sorting must not mutate callers", "b", original.get(0).getString("id"));
        Collections.reverse(original);
        assertEquals(Arrays.asList("early", "a", "b"), ids(DownloadQueueOrder.sorted(original)));
    }

    @Test public void enqueueClockIsStrictAcrossSameMillisecondRetryAndClockRollback() throws Exception {
        JSONObject first = task("first", "queued", 100, 100);
        long second = DownloadQueueOrder.nextQueuedAt(Arrays.asList(first), 100, 100);
        assertEquals(101, second);
        JSONObject retry = task("retry", "queued", 1, second);
        assertEquals(102, DownloadQueueOrder.nextQueuedAt(Arrays.asList(first, retry), 100, second));
        assertEquals("Persistent sequence survives cleared history and clock rollback", 103,
                DownloadQueueOrder.nextQueuedAt(Collections.emptyList(), 90, 102));
    }

    private static JSONObject task(String id, String status, long created, long queued) throws Exception {
        return new JSONObject().put("id", id).put("status", status).put("createdAt", created).put("queuedAt", queued);
    }
    private static List<String> ids(List<JSONObject> tasks) { List<String> ids = new ArrayList<>(); for (JSONObject task : tasks) ids.add(task.optString("id")); return ids; }
}
