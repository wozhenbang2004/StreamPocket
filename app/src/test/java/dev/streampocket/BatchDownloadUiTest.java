package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Native View + durable queue journey; all media/source responses below are explicit offline fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BatchDownloadUiTest {
    private Context app;
    @Before public void clean() throws Exception {
        app=RuntimeEnvironment.getApplication();app.getSharedPreferences(MainActivity.class.getName(),0).edit().clear().commit();app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("config",0).edit().clear().putBoolean("app_update_auto_check",false).putBoolean("motion_enabled",false).commit();
        Field initialized=TaskStore.class.getDeclaredField("initialized");initialized.setAccessible(true);initialized.setBoolean(null,false);
    }
    @Test public void batchContinuesAfterFailureClearsConsumedInputAndEnqueuesAllInOrder() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity a=controller.create(state("https://x.com/u/status/1 https://b23.tv/failure https://v.douyin.com/last/")).start().resume().visible().get();
            List<String> called=new ArrayList<>();
            set(a,"inspector",(InspectionBatch.Inspector)(url,progress,cancelled)->{
                called.add(url);if(url.contains("failure"))throw new java.io.IOException("ERROR: timed out");
                return Collections.singletonList(media(url,url.contains("status")?"first":"last"));
            });
            invoke(a,"analyze",new Class<?>[0]);drain(a);
            assertEquals(Arrays.asList("https://x.com/u/status/1","https://b23.tv/failure","https://v.douyin.com/last/"),called);
            assertEquals("",field(a,"input",EditText.class).getText().toString());assertEquals(2,list(a,"inspectedMedia").size());
            assertEquals(1,inbox(a).count("failed"));assertEquals("https://b23.tv/failure",inbox(a).jobs.get(0).url);
            assertNotNull(find(a.getWindow().getDecorView(),"全部加入队列"));assertEquals("Only the current page may occupy the content area",1,field(a,"body",ViewGroup.class).getChildCount());capture(a,"batch-partial-fixture.png");
            field(a,"input",EditText.class).setText("https://x.com/u/status/next");
            find(a.getWindow().getDecorView(),"全部加入队列").performClick();
            List<JSONObject> tasks=TaskStore.all(a);assertEquals(2,tasks.size());assertEquals("first",tasks.get(0).getString("title"));assertEquals("last",tasks.get(1).getString("title"));
            Intent request=Shadows.shadowOf(a).getNextStartedService();assertNotNull(request);assertEquals(2,request.getStringArrayListExtra("ids").size());
            invoke(a,"showTab",new Class<?>[]{String.class},"new");
            assertEquals("https://x.com/u/status/next",field(a,"input",EditText.class).getText().toString());
            assertTrue(list(a,"inspectedMedia").isEmpty());
        } finally {controller.pause().stop().destroy();}
    }
    @Test public void retryUsesOnlyFailedLinksAndKeepsAnUnrelatedDraft() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity a=controller.create(state("https://x.com/u/status/next")).start().resume().visible().get();
            inbox(a).add(Collections.singletonList("https://b23.tv/failed"));inbox(a).fail(inbox(a).jobs.get(0),new java.io.IOException("ERROR: timed out"));
            set(a,"inspector",(InspectionBatch.Inspector)(url,progress,cancelled)->Collections.singletonList(media(url,"retried")));
            inbox(a).retryFailed();invoke(a,"startNextInspection",new Class<?>[0]);drain(a);
            assertEquals("https://x.com/u/status/next",field(a,"input",EditText.class).getText().toString());
            assertEquals(1,list(a,"inspectedMedia").size());assertEquals(0,inbox(a).count("failed"));
        } finally {controller.pause().stop().destroy();}
    }
    @Test public void cancellationDuringRetryRetainsTheFailedUrlsAndButton() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity a=controller.create(state("")).start().resume().visible().get();
            inbox(a).add(Collections.singletonList("https://b23.tv/failed"));inbox(a).fail(inbox(a).jobs.get(0),new java.io.IOException("ERROR: timed out"));
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
            set(a,"inspector",(InspectionBatch.Inspector)(url,progress,cancelled)->{
                entered.countDown();while(!cancelled.getAsBoolean())Thread.sleep(10);throw new InterruptedException();
            });
            inbox(a).retryFailed();invoke(a,"startNextInspection",new Class<?>[0]);
            assertTrue(entered.await(3,TimeUnit.SECONDS));invoke(a,"cancelAnalysis",new Class<?>[0]);drain(a);
            assertEquals(1,inbox(a).count("failed"));assertEquals("https://b23.tv/failed",inbox(a).jobs.get(0).url);
            assertNotNull(find(a.getWindow().getDecorView(),"重试失败"));
        } finally {controller.pause().stop().destroy();}
    }
    @Test public void inboxPersistsResultsAndInterruptedJobsWithoutLosingTheirIdentity() throws Exception {
        InspectionInbox source=new InspectionInbox();source.add(Arrays.asList("https://x.com/u/status/1","https://b23.tv/two"));
        String activeId=source.jobs.get(0).id;source.jobs.get(0).state="parsing";
        source.media.add(media("https://x.com/u/status/kept","kept"));
        InspectionInbox restored=new InspectionInbox();restored.restore(source.json());
        assertEquals(activeId,restored.next().id);assertEquals(2,restored.count("queued"));assertEquals(1,restored.media.size());
        restored.add(Collections.singletonList("https://v.douyin.com/third/"));assertEquals(3,restored.count("queued"));assertEquals(1,restored.media.size());
        restored.complete(restored.next(),Arrays.asList(media("https://x.com/u/status/1","one"),media("https://x.com/u/status/1","one")));
        assertEquals(2,restored.media.size());
        restored.jobs.get(0).state="cancelling";
        InspectionInbox cancelled=new InspectionInbox();cancelled.restore(restored.json());
        assertEquals("Cancellation must remain stopped after process recreation","failed",cancelled.jobs.get(0).state);
        assertEquals(restored.jobs.get(0).id,cancelled.jobs.get(0).id);
    }

    @Test public void coldStartRestoresReadyResultsAndTheirSelectedQuality() throws Exception {
        Engine.Media ready=media("https://x.com/u/status/kept","kept");
        ready.qualities.add(new Engine.Quality("720","720p","b[height=720]"));
        InspectionInbox saved=new InspectionInbox();saved.media.add(ready);
        app.getSharedPreferences(MainActivity.class.getName(),0).edit().putString("inspection_inbox",saved.json())
                .putString("inspection_quality",new JSONObject().put(InspectionBatch.key(ready),1).toString()).commit();
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity a=controller.create().start().resume().visible().get();
            assertEquals(1,inbox(a).media.size());
            assertNotNull(find(a.getWindow().getDecorView(),"画质：720p  ▾"));
            assertTrue(TaskStore.all(a).isEmpty());
            android.content.pm.ActivityInfo info=app.getPackageManager().getActivityInfo(new android.content.ComponentName(app,MainActivity.class),0);
            assertEquals("Notification and share must return to the existing queue owner",android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK,info.launchMode);
        } finally {controller.pause().stop().destroy();}
    }

    @Test public void newManualAndClipboardLinksQueueBehindActiveWorkAndKeepPriorResults() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1),entered=new java.util.concurrent.CountDownLatch(1);
        try {
            MainActivity a=controller.create(state("https://x.com/u/status/one https://b23.tv/two")).start().resume().visible().get();
            invoke(a,"showMedia",new Class<?>[]{List.class},Collections.singletonList(media("https://x.com/u/status/kept","kept")));
            List<String> calls=Collections.synchronizedList(new ArrayList<>());
            set(a,"inspector",(InspectionBatch.Inspector)(url,progress,cancelled)->{calls.add(url);if(url.endsWith("one")){entered.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));}return Collections.singletonList(media(url,url));});
            invoke(a,"analyze",new Class<?>[0]);assertTrue(entered.await(2,TimeUnit.SECONDS));
            String activeId=field(a,"activeInspection",InspectionInbox.Job.class).id;
            field(a,"input",EditText.class).setText("https://v.douyin.com/three/");invoke(a,"analyze",new Class<?>[0]);
            field(a,"input",EditText.class).setText("我还没提交的草稿");set(a,"clipboardUrls",Collections.singletonList("https://x.com/u/status/four"));invoke(a,"acceptClipboardOffer",new Class<?>[0]);
            assertEquals(activeId,field(a,"activeInspection",InspectionInbox.Job.class).id);assertEquals(3,inbox(a).count("queued"));assertEquals(1,list(a,"inspectedMedia").size());
            assertEquals("我还没提交的草稿",field(a,"input",EditText.class).getText().toString());assertTrue(field(a,"input",EditText.class).isEnabled());assertEquals(1,calls.size());
            release.countDown();drain(a);
            assertEquals(Arrays.asList("https://x.com/u/status/one","https://b23.tv/two","https://v.douyin.com/three/","https://x.com/u/status/four"),calls);
            assertEquals(5,list(a,"inspectedMedia").size());assertTrue(TaskStore.all(a).isEmpty());capture(a,"queued-results-fixture.png");
        } finally {release.countDown();controller.pause().stop().destroy();}
    }

    @Test public void clipboardOfferWaitsForAnExplicitTapAndIsNotRepeated() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture","https://x.com/u/status/123 https://b23.tv/fixture"));
        try {
            MainActivity a=controller.create(state("")).start().resume().visible().get();
            set(a,"inspector",(InspectionBatch.Inspector)(url,progress,cancelled)->Collections.singletonList(media(url,url)));
            controller.windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(TaskStore.all(a).isEmpty());assertTrue(list(a,"inspectedMedia").isEmpty());
            assertNotNull(find(a.getWindow().getDecorView(),"发现 2 个新链接"));capture(a,"clipboard-offer-fixture.png");
            find(a.getWindow().getDecorView(),"加入解析").performClick();drain(a);
            assertEquals(2,list(a,"inspectedMedia").size());assertEquals("",field(a,"input",EditText.class).getText().toString());
            assertTrue(TaskStore.all(a).isEmpty());assertTrue(list(a,"clipboardUrls").isEmpty());
            assertEquals(ClipboardOffer.fingerprint(LinkBatch.parse("https://x.com/u/status/123 https://b23.tv/fixture").urls),a.getPreferences(0).getString("clipboard_seen",""));
        } finally {controller.pause().stop().destroy();clipboard.clearPrimaryClip();}
    }

    @Test public void aNewClipboardAppendsWithoutReplacingFailedLinksOrAskingToDiscardThem() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity a=controller.create(state("草稿保留")).start().resume().visible().get();
            inbox(a).add(Collections.singletonList("https://b23.tv/failed"));inbox(a).fail(inbox(a).jobs.get(0),new java.io.IOException("ERROR: timed out"));
            String failedId=inbox(a).jobs.get(0).id;
            set(a,"inspector",(InspectionBatch.Inspector)(url,progress,cancelled)->Collections.singletonList(media(url,"new clipboard result")));
            set(a,"clipboardUrls",Collections.singletonList("https://x.com/u/status/new"));invoke(a,"acceptClipboardOffer",new Class<?>[0]);drain(a);
            assertEquals(1,inbox(a).count("failed"));assertEquals(failedId,inbox(a).jobs.get(0).id);assertEquals(1,inbox(a).media.size());
            assertEquals("草稿保留",field(a,"input",EditText.class).getText().toString());
            android.app.Dialog dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();assertTrue(dialog==null||!dialog.isShowing());
        } finally {controller.pause().stop().destroy();}
    }

    static Engine.Media media(String url,String title){Engine.Media m=new Engine.Media();m.url=url;m.id=title;m.title=title;m.platform="样例";m.qualities.add(new Engine.Quality("best","最高可用画质","bv*+ba/b"));return m;}
    private static Bundle state(String input){Bundle b=new Bundle();b.putString("draft",input);b.putString("tab","new");return b;}
    private static InspectionInbox inbox(MainActivity a)throws Exception{return field(a,"inspectionInbox",InspectionInbox.class);}
    private static List list(Object o,String name)throws Exception{return field(o,name,List.class);}
    private static <T>T field(Object o,String name,Class<T> type)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o));}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object invoke(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static void drain(MainActivity a)throws Exception{for(int n=0;n<25;n++){field(a,"io",ExecutorService.class).submit(()->{}).get(8,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();if(!field(a,"parsing",Boolean.class)&&inbox(a).count("queued")==0)return;}fail("Parser queue did not drain");}
    private static TextView find(View v,String exact){if(v instanceof TextView&&exact.contentEquals(((TextView)v).getText()))return(TextView)v;if(v instanceof ViewGroup)for(int n=0;n<((ViewGroup)v).getChildCount();n++){TextView f=find(((ViewGroup)v).getChildAt(n),exact);if(f!=null)return f;}return null;}
    private static void capture(MainActivity a,String name)throws Exception{
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));
        View decor=a.getWindow().getDecorView();float d=decor.getResources().getDisplayMetrics().density;int w=Math.round(412*d),h=Math.round(915*d);
        decor.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));decor.layout(0,0,w,h);
        File root=new File(System.getProperty("user.dir"));while(root!=null&&!new File(root,"settings.gradle").exists())root=root.getParentFile();assertNotNull(root);
        File out=new File(root,"app/build/reports/view-fixtures/031-batch");assertTrue(out.isDirectory()||out.mkdirs());
        Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(b));try(FileOutputStream f=new FileOutputStream(new File(out,name))){b.compress(Bitmap.CompressFormat.PNG,100,f);}finally{b.recycle();}
    }
}
