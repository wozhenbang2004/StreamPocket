package dev.streampocket;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Offline native UI regression: the real archive query runs behind an independently blocked I/O queue. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ArchiveIntakeUiTest {
    private Context app;
    @Before public void clean() throws Exception {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences(MainActivity.class.getName(),0).edit().clear().commit();
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("config",0).edit().clear().putBoolean("app_update_auto_check",false)
                .putBoolean("motion_enabled",false).commit();
        app.deleteDatabase(ContentArchiveStore.DATABASE);
        Field initialized=TaskStore.class.getDeclaredField("initialized");initialized.setAccessible(true);initialized.setBoolean(null,false);
    }

    @Test public void queryReturnsImmediatelyAndUsesTheClickedQualityAndMetadataSnapshot() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        try(ActivityController<MainActivity> controller=activity()){
            MainActivity activity=controller.get();ExecutorService io=blockIo(activity,release);
            Engine.Media media=ready(activity,"first");
            enqueue(activity,media);
            assertTrue(field(activity,"archiveChecking",Boolean.class));
            assertTrue(TaskStore.all(activity).isEmpty());
            media.title="修改后的标题";
            choices(activity).put(InspectionBatch.key(media),2); // MP3 after best and M4A.
            enqueue(activity,media); // Repeated tap during the same query must not submit twice.
            release.countDown();drain(activity,io);
            List<JSONObject> tasks=TaskStore.all(activity);assertEquals(1,tasks.size());
            assertEquals("first",tasks.get(0).getString("title"));
            assertEquals("best",tasks.get(0).getJSONObject("quality").getString("id"));
            assertFalse(field(activity,"archiveChecking",Boolean.class));
            assertNull(field(activity,"archiveDialog",PocketDialog.class));
            enqueue(activity,media);drain(activity,io);
            assertEquals("A stale row tap cannot submit a removed inspection result",1,TaskStore.all(activity).size());
        }finally{release.countDown();}
    }

    @Test public void cancelledOldQueryCannotEnqueueOrReplaceTheNextBatch() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        try(ActivityController<MainActivity> controller=activity()){
            MainActivity activity=controller.get();ExecutorService io=blockIo(activity,release);
            Engine.Media old=ready(activity,"old"),next=ready(activity,"next");
            enqueue(activity,old);
            field(activity,"archiveDialog",PocketDialog.class).getButton(PocketDialog.BUTTON_NEGATIVE).performClick();
            assertFalse(field(activity,"archiveChecking",Boolean.class));
            enqueue(activity,next);release.countDown();drain(activity,io);
            List<JSONObject> tasks=TaskStore.all(activity);assertEquals(1,tasks.size());
            assertEquals("next",tasks.get(0).getString("title"));
        }finally{release.countDown();}
    }

    @Test public void anArchivedItemStillRequiresTheExistingExplicitRedownloadConfirmation() throws Exception {
        try(ActivityController<MainActivity> controller=activity()){
            MainActivity activity=controller.get();Engine.Media media=ready(activity,"saved");
            JSONObject archived=new JSONObject().put("id","past").put("status","completed")
                    .put("media",media.toJson()).put("quality",media.qualities.get(0).toJson())
                    .put("title",media.title).put("uri","content://archive/past").put("mime","video/mp4")
                    .put("size",100).put("completedAt",100);
            assertTrue(ContentArchiveStore.recordCompleted(activity,archived));
            enqueue(activity,media);drain(activity,field(activity,"io",ExecutorService.class));
            assertTrue(TaskStore.all(activity).isEmpty());
            PocketDialog confirmation=field(activity,"archiveDialog",PocketDialog.class);
            assertNotNull(confirmation);assertEquals("仍然下载",confirmation.getButton(PocketDialog.BUTTON_POSITIVE).getText().toString());
            confirmation.getButton(PocketDialog.BUTTON_POSITIVE).performClick();
            assertEquals(1,TaskStore.all(activity).size());
        }
    }

    @Test @Config(sdk=28) public void storagePermissionRoundTripKeepsTheClickedQualitySnapshot() throws Exception {
        try(ActivityController<MainActivity> controller=activity()){
            MainActivity activity=controller.get();Engine.Media media=ready(activity,"permission");
            Shadows.shadowOf((Application)app).denyPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            enqueue(activity,media);drain(activity,field(activity,"io",ExecutorService.class));
            assertEquals(1,field(activity,"pendingBatch",List.class).size());assertTrue(TaskStore.all(activity).isEmpty());
            choices(activity).put(InspectionBatch.key(media),2);media.title="权限等待期间修改";
            Shadows.shadowOf((Application)app).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            activity.onRequestPermissionsResult(53,new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});
            List<JSONObject> tasks=TaskStore.all(activity);assertEquals(1,tasks.size());
            assertEquals("permission",tasks.get(0).getString("title"));
            assertEquals("best",tasks.get(0).getJSONObject("quality").getString("id"));
            assertTrue(field(activity,"pendingBatchQualities",List.class).isEmpty());
        }
    }

    @Test public void destroyedActivityDiscardsThePendingQueryAndClosesItsDialog() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        ActivityController<MainActivity> controller=activity();MainActivity activity=controller.get();
        ExecutorService io=blockIo(activity,release);
        try{
            enqueue(activity,ready(activity,"gone"));
            controller.pause().stop().destroy();release.countDown();
            assertTrue(io.awaitTermination(5,TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(TaskStore.all(app).isEmpty());assertNull(field(activity,"archiveDialog",PocketDialog.class));
        }finally{release.countDown();if(!activity.isDestroyed())controller.close();}
    }

    private ActivityController<MainActivity> activity(){return Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();}
    private Engine.Media ready(MainActivity activity,String id)throws Exception{
        Engine.Media media=DownloadPresetsTest.video();media.id=id;media.title=id;media.extractorKey="Youtube";
        media.qualities.remove(1);
        media.url="https://www.youtube.com/watch?v="+(id+"abcdefghijk").substring(0,11);
        field(activity,"inspectionInbox",InspectionInbox.class).media.add(media);return media;
    }
    private static ExecutorService blockIo(MainActivity activity,CountDownLatch release)throws Exception{
        ExecutorService io=field(activity,"io",ExecutorService.class);CountDownLatch started=new CountDownLatch(1);
        io.execute(()->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        assertTrue(started.await(5,TimeUnit.SECONDS));return io;
    }
    private static void drain(MainActivity activity,ExecutorService io)throws Exception{
        io.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static void enqueue(MainActivity activity,Engine.Media media)throws Exception{
        Method method=MainActivity.class.getDeclaredMethod("enqueueBatch",List.class);method.setAccessible(true);
        method.invoke(activity,Collections.singletonList(media));
    }
    @SuppressWarnings("unchecked") private static Map<String,Integer> choices(MainActivity activity)throws Exception{return field(activity,"qualitySelections",Map.class);}
    private static <T>T field(Object object,String name,Class<T> type)throws Exception{
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(object));
    }
}
