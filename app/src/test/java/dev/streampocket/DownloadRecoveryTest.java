package dev.streampocket;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Cold-start persistence and actual recovery admission; the native worker is deliberately blocked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DownloadRecoveryTest {
    private Context app;
    private DownloadService service;
    private ServiceController<DownloadService> controller;
    private ExecutorService worker;
    private final CountDownLatch release = new CountDownLatch(1);

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("tasks", 0).edit().clear().commit();
        app.getSharedPreferences("config", 0).edit().remove("download_auto_recover").commit();
        reset(); TaskStore.init(app);
        controller = Robolectric.buildService(DownloadService.class).create(); service = controller.get();
        Field field = DownloadService.class.getDeclaredField("worker"); field.setAccessible(true);
        worker = (ExecutorService) field.get(service);
        CountDownLatch held = new CountDownLatch(1);
        worker.execute(() -> { held.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertTrue(held.await(5, TimeUnit.SECONDS));
    }

    @After public void cleanup() throws Exception {
        controller.destroy(); release.countDown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        app.getSharedPreferences("tasks", 0).edit().clear().commit();
        app.getSharedPreferences("config", 0).edit().remove("download_auto_recover").commit(); reset();
    }

    @Test public void recoveryPolicyRunsWithPlainJava() { DownloadRecoverySelfTest.main(new String[0]); }

    @Test public void committedCancelSurvivesDeathBeforeTheProcessUnwinds() throws Exception {
        seed(task("cancel", "downloading", "video", 1));
        assertTrue(TaskStore.markCancelRequested(app, "cancel"));
        reset(); TaskStore.init(app);
        assertEquals("cancelled", TaskStore.get(app, "cancel").getString("status"));
        assertTrue(TaskStore.recoverPending(app, Collections.emptySet()).isEmpty());
    }

    @Test public void cancellingAWaitingTaskAlsoCommitsTheUserIntent() throws Exception {
        seed(task("waiting", "queued", "queued", 1));
        service.onStartCommand(new Intent(app, DownloadService.class).setAction(DownloadService.CANCEL).putExtra("id", "waiting"), 0, 1);
        reset(); TaskStore.init(app);
        assertEquals("cancelled", TaskStore.get(app, "waiting").getString("status"));
        assertEquals(DownloadRecovery.CANCEL, TaskStore.get(app, "waiting").getString(DownloadRecovery.INTENT));
        assertTrue(TaskStore.recoverPending(app, Collections.emptySet()).isEmpty());
    }

    @Test public void coldRecoveryKeepsBytesTargetIdentityAndFifoAndAcceptsOnlyOnce() throws Exception {
        JSONObject later = task("later", "downloading", "audio", 2).put("downloadedBytes", 40_000_000)
                .put("totalBytes", 100_000_000).put("progress", 40).put("destinationTree", "content://fixture/chosen")
                .put("savedLocation", "用户选择的位置");
        seed(later, task("first", "queued", "queued", 1));
        reset(); TaskStore.init(app);
        List<String> ids = TaskStore.recoverPending(app, Collections.emptySet());
        assertEquals(Arrays.asList("first", "later"), ids);
        assertTrue(TaskStore.recoverPending(app, Collections.emptySet()).isEmpty());
        service.onStartCommand(new Intent(app, DownloadService.class).setAction("dev.streampocket.RECOVER")
                .putStringArrayListExtra("ids", new java.util.ArrayList<>(ids)), 0, 1);
        JSONObject recovered = TaskStore.get(app, "later");
        assertEquals("later", recovered.getString("id")); assertEquals(2, recovered.getLong("queuedAt"));
        assertEquals(40_000_000, recovered.getLong("downloadedBytes")); assertEquals(100_000_000, recovered.getLong("totalBytes"));
        assertEquals(40, recovered.getDouble("progress"), 0);
        assertEquals("content://fixture/chosen", recovered.getString("destinationTree"));
        assertEquals("用户选择的位置", recovered.getString("savedLocation"));
        assertEquals(Arrays.asList("first", "later"), queue());
        service.onStartCommand(new Intent(app, DownloadService.class).setAction("dev.streampocket.RECOVER")
                .putStringArrayListExtra("ids", new java.util.ArrayList<>(ids)), 0, 2);
        assertEquals(Arrays.asList("first", "later"), queue());
    }

    @Test public void manualPauseAndGlobalHoldSurviveColdStart() throws Exception {
        seed(task("manual", "downloading", "video", 1), task("held", "queued", "queued", 2));
        TaskStore.pauseBatch(app, Collections.singletonList("manual"), "manual");
        TaskStore.pauseQueue(app, null, Collections.singletonList("held"));
        reset(); TaskStore.init(app);
        assertEquals("paused", TaskStore.get(app, "manual").getString("status"));
        assertTrue(TaskStore.heldByQueue(TaskStore.get(app, "held")));
        assertTrue(TaskStore.recoverPending(app, Collections.emptySet()).isEmpty());
    }

    @Test public void recoveredFifoPrecedesLaterAcceptedWaiters() throws Exception {
        seed(task("old", "interrupted", "video", 1));
        List<String> ids = TaskStore.recoverPending(app, Collections.emptySet());
        JSONArray rows = new JSONArray(app.getSharedPreferences("tasks", 0).getString("items", "[]"));
        rows.put(task("new", "queued", "queued", 2));
        app.getSharedPreferences("tasks", 0).edit().putString("items", rows.toString()).commit();
        service.onStartCommand(new Intent(app, DownloadService.class).setAction(DownloadService.ENQUEUE).putExtra("id", "new"), 0, 1);
        service.onStartCommand(new Intent(app, DownloadService.class).setAction("dev.streampocket.RECOVER")
                .putStringArrayListExtra("ids", new java.util.ArrayList<>(ids)), 0, 2);
        assertEquals(Arrays.asList("old", "new"), queue());
        assertEquals(1, TaskStore.get(app, "old").getLong("queuedAt"));
    }

    @Test public void unknownIntentAndPublicationAreNeverAutomaticallyReplayed() throws Exception {
        JSONObject legacy = task("legacy", "cancelling", "video", 1); legacy.remove(DownloadRecovery.INTENT);
        JSONObject ambiguous = task("old", "downloading", "video", 2); ambiguous.remove(DownloadRecovery.INTENT);
        seed(legacy, ambiguous, task("saving", "saving", "saving", 3), task("merging", "merging", "merging", 4),
                task("published", "downloading", "video", 5).put("uri", "content://fixture/committed"),
                task("complete", "downloading", "video", 6).put("transferComplete", true));
        reset(); TaskStore.init(app);
        assertTrue(TaskStore.recoverPending(app, Collections.emptySet()).isEmpty());
        assertEquals("interrupted", TaskStore.get(app, "legacy").getString("status"));
        assertEquals("content://fixture/committed", TaskStore.get(app, "published").getString("uri"));
    }

    @Test public void publicationBarrierProtectsAgainstAStaleLastProgressPhase() throws Exception {
        seed(task("saving", "downloading", "video", 1));
        assertTrue(TaskStore.beginPublication(app, "saving"));
        reset(); TaskStore.init(app);
        assertTrue(TaskStore.get(app, "saving").getBoolean("publicationStarted"));
        assertTrue(TaskStore.recoverPending(app, Collections.emptySet()).isEmpty());
    }

    @Test public void aStillOwnedTaskCannotBeClaimedByForegroundRecovery() throws Exception {
        seed(task("owned", "interrupted", "video", 1));
        assertTrue(TaskStore.recoverPending(app, Collections.singleton("owned")).isEmpty());
        assertEquals(Collections.singletonList("owned"), TaskStore.recoverPending(app, Collections.emptySet()));
    }

    @Test public void aPauseBetweenRecoverySelectionAndServiceAcceptanceWins() throws Exception {
        seed(task("manual", "interrupted", "video", 1), task("global", "interrupted", "media", 2));
        List<String> ids = TaskStore.recoverPending(app, Collections.emptySet());
        TaskStore.pauseBatch(app, Collections.singletonList("manual"), null);
        TaskStore.pauseQueue(app, null, Collections.emptyList());
        assertTrue(TaskStore.acceptRecovered(app, ids).isEmpty());
        assertEquals(DownloadQueuePolicy.MANUAL, TaskStore.get(app, "manual").getString("pauseOrigin"));
        assertTrue(TaskStore.heldByQueue(TaskStore.get(app, "global")));
    }

    @Test public void disablingRecoveryLeavesExplicitRetryAvailable() throws Exception {
        seed(task("disabled", "interrupted", "video", 1));
        app.getSharedPreferences("config", 0).edit().putBoolean("download_auto_recover", false).commit();
        assertEquals(0, DownloadService.recover(app));
        assertEquals("interrupted", TaskStore.get(app, "disabled").getString("status"));
        assertTrue(TaskStore.markQueued(app, "disabled"));
    }

    @Test public void aRejectedForegroundStartKeepsRecoveryAndTheOriginalTarget() throws Exception {
        seed(task("rejected", "interrupted", "video", 1).put("downloadedBytes", 40).put("destinationTree", "content://fixture/target"));
        Context rejecting = new ContextWrapper(app) {
            @Override public ComponentName startForegroundService(Intent intent) { throw new IllegalStateException("background start denied"); }
        };
        assertEquals(0, DownloadService.recover(rejecting));
        JSONObject task = TaskStore.get(app, "rejected");
        assertEquals("interrupted", task.getString("status")); assertEquals(40, task.getLong("downloadedBytes"));
        assertEquals("content://fixture/target", task.getString("destinationTree"));
        assertEquals(Collections.singletonList("rejected"), TaskStore.recoverPending(app, Collections.emptySet()));
    }

    @SuppressWarnings("unchecked") private List<String> queue() throws Exception {
        Field field = DownloadService.class.getDeclaredField("queue"); field.setAccessible(true);
        return new java.util.ArrayList<>((ArrayDeque<String>) field.get(service));
    }
    private JSONObject task(String id, String status, String phase, long queuedAt) throws Exception {
        return new JSONObject().put("id", id).put("status", status).put("phase", phase).put("queuedAt", queuedAt)
                .put("createdAt", queuedAt).put(DownloadRecovery.INTENT, DownloadRecovery.RUN).put("uri", "");
    }
    private void seed(JSONObject... tasks) { app.getSharedPreferences("tasks", 0).edit().putString("items", new JSONArray(Arrays.asList(tasks)).toString()).commit(); }
    private void reset() throws Exception { Field field = TaskStore.class.getDeclaredField("initialized"); field.setAccessible(true); field.setBoolean(null, false); }
}
