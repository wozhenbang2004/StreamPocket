package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
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

/** Production queue/stop transitions with an explicitly blocked worker, no native transfer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DownloadPauseTest {
    private Context app;
    private DownloadService service;
    private ServiceController<DownloadService> controller;
    private ExecutorService worker;
    private final CountDownLatch releaseWorker=new CountDownLatch(1);

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        resetInitialization(); TaskStore.init(app);
        controller=Robolectric.buildService(DownloadService.class).create(); service=controller.get();
        worker=(ExecutorService)field("worker").get(service);
        CountDownLatch workerHeld=new CountDownLatch(1);
        worker.execute(()->{
            workerHeld.countDown();
            try { releaseWorker.await(); } catch(InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        assertTrue(workerHeld.await(5,TimeUnit.SECONDS));
    }

    @After public void cleanup() throws Exception {
        // Never release the fixture queue into the real download engine.
        controller.destroy(); releaseWorker.countDown();
        assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
        app.getSharedPreferences("tasks",0).edit().clear().commit(); resetInitialization();
    }

    @Test public void queuedPauseDoesNotRunAndResumeKeepsIdentityBytesAndGoesToTail() throws Exception {
        seed(task("a","queued","queued"),task("b","queued","queued"));
        command(DownloadService.ENQUEUE,"a"); command(DownloadService.ENQUEUE,"b");
        TaskStore.update(app,"a",new JSONObject().put("downloadedBytes",300).put("totalBytes",1000).put("progress",30));
        command(DownloadService.PAUSE,"a");
        JSONObject paused=TaskStore.get(app,"a");
        assertEquals("paused",paused.getString("status")); assertTrue(paused.getBoolean("pauseRequested"));
        assertEquals(Arrays.asList("b"),Arrays.asList(queue().toArray(new String[0])));
        assertEquals(300,paused.getLong("downloadedBytes"));
        command(DownloadService.ENQUEUE,"a");
        JSONObject resumed=TaskStore.get(app,"a");
        assertEquals("a",resumed.getString("id")); assertEquals("queued",resumed.getString("status"));
        assertEquals(Arrays.asList("b","a"),Arrays.asList(queue().toArray(new String[0])));
        assertTrue(resumed.getLong("queuedAt")>TaskStore.get(app,"b").getLong("queuedAt"));
        assertEquals(300,resumed.getLong("downloadedBytes")); assertEquals(1000,resumed.getLong("totalBytes"));
        assertEquals(30,resumed.getDouble("progress"),0); assertEquals(-1,resumed.getDouble("speedBytesPerSecond"),0);
        assertFalse(resumed.getBoolean("pauseRequested"));
    }

    @Test public void activePauseWaitsForUnwindAndLateProgressCannotReopenIt() throws Exception {
        seed(task("a","downloading","video").put("downloadedBytes",300).put("totalBytes",1000).put("progress",30));
        own("a"); command(DownloadService.PAUSE,"a");
        assertEquals("pausing",TaskStore.get(app,"a").getString("status"));
        assertFalse("Native process ownership blocks an early retry",TaskStore.markQueued(app,"a"));
        command(DownloadService.ENQUEUE,"a"); assertTrue(queue().isEmpty());
        DownloadProgress model=new DownloadProgress();
        assertFalse((Boolean)invoke("publishProgress",new Class<?>[]{String.class,DownloadProgress.Snapshot.class},"a",model.snapshot()));
        assertEquals("pausing",TaskStore.get(app,"a").getString("status"));
        assertEquals(300,TaskStore.get(app,"a").getLong("downloadedBytes"));
        // This is the actual method called after execute/save returns, not a timer.
        invoke("finishStopped",new Class<?>[]{String.class},"a");
        assertEquals("paused",TaskStore.get(app,"a").getString("status"));
        assertEquals(-1,TaskStore.get(app,"a").getInt("activeConnections"));
    }

    @Test public void mergingProcessingAndSavingNeverPretendToPause() throws Exception {
        for(String stage:new String[]{"merging","processing","saving"}) {
            String status=stage.equals("saving")?"saving":"merging";
            seed(task("a",status,stage).put("transferComplete",true)); own("a");
            assertFalse(DownloadService.canPause(TaskStore.get(app,"a")));
            command(DownloadService.PAUSE,"a");
            assertEquals(status,TaskStore.get(app,"a").getString("status"));
            assertFalse(TaskStore.get(app,"a").optBoolean("pauseRequested"));
        }
        assertFalse(DownloadService.canPause(task("a","downloading","video").put("transferComplete",true)));
    }

    @Test public void resumePreflightShowsPreparationWithoutInventingZeroBytes() throws Exception {
        JSONObject previous=task("a","queued","queued").put("downloadedBytes",300).put("totalBytes",1000).put("progress",30);
        seed(previous); own("a");
        invoke("publishPreparing",new Class<?>[]{String.class,DownloadProgress.Snapshot.class,JSONObject.class},"a",new DownloadProgress().snapshot(),previous);
        JSONObject current=TaskStore.get(app,"a");
        assertEquals("downloading",current.getString("status")); assertEquals("preparing",current.getString("phase"));
        assertEquals(300,current.getLong("downloadedBytes")); assertEquals(1000,current.getLong("totalBytes"));
        assertEquals(30,current.getDouble("progress"),0); assertTrue(current.getString("detail").contains("上次记录"));
        assertEquals(-1,current.getDouble("speedBytesPerSecond"),0);
        DownloadProgress plan=new DownloadProgress();
        plan.accept(DownloadProgress.PLAN_PREFIX+new JSONObject().put("single",new JSONObject().put("format_id","v")
                .put("vcodec","h264").put("acodec","none").put("filesize",1000)));
        invoke("publishProgress",new Class<?>[]{String.class,DownloadProgress.Snapshot.class},"a",plan.snapshot());
        assertEquals("A format plan is not a zero-byte transfer",300,TaskStore.get(app,"a").getLong("downloadedBytes"));
    }

    @Test public void cancelOverridesPendingPauseButNeitherCanUndoACommittedFile() throws Exception {
        seed(task("a","downloading","video")); own("a");
        command(DownloadService.PAUSE,"a"); command(DownloadService.CANCEL,"a");
        assertEquals("cancelling",TaskStore.get(app,"a").getString("status"));
        assertFalse(TaskStore.get(app,"a").getBoolean("pauseRequested"));
        invoke("finishStopped",new Class<?>[]{String.class},"a");
        assertEquals("cancelled",TaskStore.get(app,"a").getString("status"));
        TaskStore.update(app,"a",new JSONObject().put("status","completed").put("progress",100)
                .put("uri","content://fixture/committed").put("size",1000));
        command(DownloadService.PAUSE,"a"); command(DownloadService.CANCEL,"a");
        invoke("finishStopped",new Class<?>[]{String.class},"a");
        JSONObject saved=TaskStore.get(app,"a");
        assertEquals("completed",saved.getString("status")); assertEquals(100,saved.getInt("progress"));
        assertEquals("content://fixture/committed",saved.getString("uri")); assertEquals(1000,saved.getLong("size"));
    }

    @Test public void fortyMbResumePlanRoutingAndRetryKeepCountersUntilMeasuredTransfer() throws Exception {
        long mb=1048576L;
        seed(task("a","paused","video").put("downloadedBytes",40*mb).put("totalBytes",100*mb).put("progress",40)
                .put("speedBytesPerSecond",8*mb).put("etaSeconds",7).put("pauseRequested",true));
        command(DownloadService.ENQUEUE,"a"); queue().remove("a"); own("a");
        JSONObject accepted=TaskStore.get(app,"a"); assertEquals(40*mb,accepted.getLong("downloadedBytes"));
        DownloadProgress model=new DownloadProgress(); model.setTransferPolicy("parallel-http",4);
        invoke("publishPreparing",new Class<?>[]{String.class,DownloadProgress.Snapshot.class,JSONObject.class},"a",model.snapshot(),accepted);
        JSONObject info=new JSONObject().put("format_id","v").put("vcodec","h264").put("acodec","none")
                .put("protocol","https").put("filesize",100*mb);
        replay(model,DownloadProgress.PLAN_PREFIX+new JSONObject().put("single",info));
        assertHeldResume(40*mb,100*mb);
        replay(model,DownloadProgress.TRANSFER_MODE_PREFIX+"native-http");
        assertHeldResume(40*mb,100*mb);
        assertEquals("native-http",TaskStore.get(app,"a").getString("transferMode"));
        replay(model,"WARNING: retrying download (attempt 1)");
        assertHeldResume(40*mb,100*mb);
        assertEquals("retrying",TaskStore.get(app,"a").getString("phase"));
        replay(model,DownloadProgress.DOWNLOAD_PREFIX+new JSONObject().put("info",info).put("progress",new JSONObject()
                .put("status","downloading").put("downloaded_bytes",42*mb).put("total_bytes",100*mb)
                .put("speed",2*mb).put("eta",29)));
        JSONObject measured=TaskStore.get(app,"a");
        assertEquals(42*mb,measured.getLong("downloadedBytes")); assertEquals(100*mb,measured.getLong("totalBytes"));
        assertEquals(42,measured.getDouble("progress"),0); assertFalse(measured.getBoolean("progressEstimated"));
        assertEquals(2*mb,measured.getDouble("speedBytesPerSecond"),0); assertEquals(29,measured.getLong("etaSeconds"));
    }

    @Test public void delayedRemovalCannotDeleteAnAcceptedResumeOrAnyOwnedState() throws Exception {
        for(String status:new String[]{"queued","downloading","merging","saving","cancelling","pausing"}) {
            seed(task("a",status,"video"));
            assertFalse("An accepted or owned task must survive removal: "+status,TaskStore.removeIfInactive(app,"a"));
            assertEquals(status,TaskStore.get(app,"a").getString("status"));
        }
        for(String status:new String[]{"paused","failed","interrupted","cancelled","completed"}) {
            seed(task("a",status,"video"));
            assertTrue("Inactive records remain removable: "+status,TaskStore.removeIfInactive(app,"a"));
            assertNull(TaskStore.get(app,"a"));
        }
        seed(task("a","paused","video").put("downloadedBytes",40));
        assertTrue(TaskStore.markQueued(app,"a"));
        assertFalse("Resume accepted during undo delay takes priority",TaskStore.removeIfInactive(app,"a"));
        assertEquals("queued",TaskStore.get(app,"a").getString("status"));
        assertEquals(40,TaskStore.get(app,"a").getLong("downloadedBytes"));
    }

    @Test public void startupDistinguishesDurablePauseIntentAndKeepsMediaBytes() throws Exception {
        File directory=new File(app.getFilesDir(),"downloads/pause-recovery-fixture"); assertTrue(directory.isDirectory()||directory.mkdirs());
        File partial=new File(directory,"source.mp4.part"); Files.write(partial.toPath(),new byte[]{1,2,3,4});
        try {
            seed(task("a","pausing","audio").put("pauseRequested",true).put("downloadedBytes",300).put("totalBytes",1000).put("progress",30),
                    task("b","pausing","video"),task("c","downloading","video"),task("d","completed","completed").put("uri","content://fixture/saved"));
            resetInitialization(); TaskStore.init(app);
            JSONObject paused=TaskStore.get(app,"a");
            assertEquals("paused",paused.getString("status")); assertEquals(300,paused.getLong("downloadedBytes"));
            assertEquals(30,paused.getDouble("progress"),0); assertEquals(-1,paused.getDouble("speedBytesPerSecond"),0);
            assertEquals("interrupted",TaskStore.get(app,"b").getString("status"));
            assertEquals("interrupted",TaskStore.get(app,"c").getString("status"));
            assertEquals("completed",TaskStore.get(app,"d").getString("status"));
            assertEquals("content://fixture/saved",TaskStore.get(app,"d").getString("uri"));
            assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(partial.toPath()));
        } finally { partial.delete(); directory.delete(); }
    }

    @Test public void aDifferentServiceCannotLabelAnotherOwnedProcessPausedOrRetryable() throws Exception {
        seed(task("old","pausing","video").put("pauseRequested",true),task("other","downloading","audio"));
        command(DownloadService.ENQUEUE,"old"); command(DownloadService.PAUSE,"other"); command(DownloadService.CANCEL,"other");
        assertEquals("pausing",TaskStore.get(app,"old").getString("status"));
        assertEquals("downloading",TaskStore.get(app,"other").getString("status"));
        assertTrue(queue().isEmpty());
    }

    @Test public void aNewBatchNeverPreemptsActiveWorkOrItsNotification() throws Exception {
        seed(task("active","downloading","video").put("title","Current active fixture").put("progress",42),
                task("waiting","queued","queued"),task("new-first","queued","queued"),task("new-last","queued","queued"));
        own("active");queue().addLast("waiting");
        service.onStartCommand(new Intent(app,DownloadService.class).setAction(DownloadService.ENQUEUE)
                .putStringArrayListExtra("ids",new java.util.ArrayList<>(Arrays.asList("new-first","new-last"))),0,2);
        assertEquals("active",field("active").get(service));
        assertEquals(Arrays.asList("waiting","new-first","new-last"),Arrays.asList(queue().toArray(new String[0])));
        assertEquals(42,TaskStore.get(app,"active").getDouble("progress"),0);
        android.app.Notification notification=org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification();
        assertNotNull(notification);assertEquals("Current active fixture",notification.extras.getString(android.app.Notification.EXTRA_TEXT));
        long first=TaskStore.get(app,"new-first").getLong("queuedAt"),last=TaskStore.get(app,"new-last").getLong("queuedAt");
        assertTrue(last>first);
        service.onStartCommand(new Intent(app,DownloadService.class).setAction(DownloadService.ENQUEUE)
                .putStringArrayListExtra("ids",new java.util.ArrayList<>(Arrays.asList("new-first","new-last"))),0,3);
        assertEquals(Arrays.asList("waiting","new-first","new-last"),Arrays.asList(queue().toArray(new String[0])));
        assertEquals(first,TaskStore.get(app,"new-first").getLong("queuedAt"));
    }

    private void command(String action,String id) { service.onStartCommand(new Intent(app,DownloadService.class).setAction(action).putExtra("id",id),0,1); }
    private void replay(DownloadProgress model,String line) throws Exception {
        assertTrue(model.accept(line));
        assertTrue((Boolean)invoke("publishProgress",new Class<?>[]{String.class,DownloadProgress.Snapshot.class},"a",model.snapshot()));
    }
    private void assertHeldResume(long downloaded,long total) throws Exception {
        JSONObject task=TaskStore.get(app,"a");
        assertEquals(downloaded,task.getLong("downloadedBytes")); assertEquals(total,task.getLong("totalBytes"));
        assertEquals(40,task.getDouble("progress"),0); assertTrue(task.getBoolean("progressEstimated"));
        assertEquals(-1,task.getDouble("speedBytesPerSecond"),0); assertEquals(-1,task.getLong("etaSeconds"));
    }
    private void own(String id) throws Exception { field("active").set(service,id);field("draining").setBoolean(service,true); }
    private static JSONObject task(String id,String status,String phase) throws Exception {
        return new JSONObject().put("id",id).put("title","Explicit state fixture").put("status",status).put("phase",phase)
                .put("createdAt",1).put("queuedAt",1).put("progress",-1).put("downloadedBytes",0)
                .put("speedBytesPerSecond",1048576).put("etaSeconds",10).put("activeConnections",4);
    }
    private void seed(JSONObject...tasks) { JSONArray values=new JSONArray();for(JSONObject t:tasks)values.put(t);app.getSharedPreferences("tasks",0).edit().putString("items",values.toString()).commit(); }
    @SuppressWarnings("unchecked") private ArrayDeque<String> queue() throws Exception { return (ArrayDeque<String>)field("queue").get(service); }
    private Field field(String name) throws Exception { Field f=DownloadService.class.getDeclaredField(name);f.setAccessible(true);return f; }
    private Object invoke(String name,Class<?>[] types,Object...args) throws Exception { Method m=DownloadService.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(service,args); }
    private static void resetInitialization() throws Exception { Field f=TaskStore.class.getDeclaredField("initialized");f.setAccessible(true);f.setBoolean(null,false); }
}
