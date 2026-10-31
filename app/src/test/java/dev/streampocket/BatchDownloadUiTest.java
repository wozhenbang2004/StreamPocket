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
        app=RuntimeEnvironment.getApplication();app.getSharedPreferences("tasks",0).edit().clear().commit();
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
            assertEquals(Collections.singletonList("https://b23.tv/failure"),list(a,"failedInspectionUrls"));
            assertNotNull(find(a.getWindow().getDecorView(),"全部加入队列（2）"));assertEquals("Only the current page may occupy the content area",1,field(a,"body",ViewGroup.class).getChildCount());capture(a,"batch-partial-fixture.png");
            field(a,"input",EditText.class).setText("https://x.com/u/status/next");
            find(a.getWindow().getDecorView(),"全部加入队列（2）").performClick();
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
            list(a,"failedInspectionUrls").add("https://b23.tv/failed");
            set(a,"inspector",(InspectionBatch.Inspector)(url,progress,cancelled)->Collections.singletonList(media(url,"retried")));
            invoke(a,"analyzeUrls",new Class<?>[]{List.class,boolean.class},Collections.singletonList("https://b23.tv/failed"),true);drain(a);
            assertEquals("https://x.com/u/status/next",field(a,"input",EditText.class).getText().toString());
            assertEquals(1,list(a,"inspectedMedia").size());assertTrue(list(a,"failedInspectionUrls").isEmpty());
        } finally {controller.pause().stop().destroy();}
    }
    @Test public void cancellationDuringRetryRetainsTheFailedUrlsAndButton() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity a=controller.create(state("")).start().resume().visible().get();
            list(a,"failedInspectionUrls").add("https://b23.tv/failed");
            java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
            set(a,"inspector",(InspectionBatch.Inspector)(url,progress,cancelled)->{
                entered.countDown();while(!cancelled.getAsBoolean())Thread.sleep(10);throw new InterruptedException();
            });
            invoke(a,"analyzeUrls",new Class<?>[]{List.class,boolean.class},Collections.singletonList("https://b23.tv/failed"),true);
            assertTrue(entered.await(3,TimeUnit.SECONDS));invoke(a,"cancelAnalysis",new Class<?>[0]);drain(a);
            assertEquals(Collections.singletonList("https://b23.tv/failed"),list(a,"failedInspectionUrls"));
            assertNotNull(find(a.getWindow().getDecorView(),"重试失败的 1 个链接"));
        } finally {controller.pause().stop().destroy();}
    }
    @Test public void batchMetadataDeduplicatesActualMediaWithoutDroppingSuccesses() throws Exception {
        Engine.Media shared=media("https://x.com/u/status/1","shared");
        InspectionBatch.Result result=InspectionBatch.run(Arrays.asList("one","bad","three"),(url,progress,cancelled)->{
            if(url.equals("bad"))throw new java.io.IOException("ERROR: HTTP Error 429");return Arrays.asList(shared,shared);
        },()->false,stage->{});
        assertEquals(2,result.succeeded);assertEquals(1,result.media.size());assertEquals(Collections.singletonList("bad"),result.failed);
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
            assertNotNull(find(a.getWindow().getDecorView(),"剪贴板中发现 2 个视频链接"));capture(a,"clipboard-offer-fixture.png");
            find(a.getWindow().getDecorView(),"查看并下载").performClick();drain(a);
            assertEquals(2,list(a,"inspectedMedia").size());assertEquals("",field(a,"input",EditText.class).getText().toString());
            assertTrue(TaskStore.all(a).isEmpty());assertTrue(list(a,"clipboardUrls").isEmpty());
            assertEquals(ClipboardOffer.fingerprint(LinkBatch.parse("https://x.com/u/status/123 https://b23.tv/fixture").urls),a.getPreferences(0).getString("clipboard_seen",""));
        } finally {controller.pause().stop().destroy();clipboard.clearPrimaryClip();}
    }

    @Test public void aNewClipboardCannotSilentlyReplaceFailedLinksWaitingForRetry() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity a=controller.create(state("")).start().resume().visible().get();
            list(a,"failedInspectionUrls").add("https://b23.tv/failed");
            set(a,"clipboardUrls",Collections.singletonList("https://x.com/u/status/new"));
            invoke(a,"acceptClipboardOffer",new Class<?>[0]);
            android.app.AlertDialog dialog=(android.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(dialog);assertTrue(dialog.isShowing());assertEquals(Collections.singletonList("https://b23.tv/failed"),list(a,"failedInspectionUrls"));
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();assertEquals(Collections.singletonList("https://b23.tv/failed"),list(a,"failedInspectionUrls"));
        } finally {controller.pause().stop().destroy();}
    }

    static Engine.Media media(String url,String title){Engine.Media m=new Engine.Media();m.url=url;m.id=title;m.title=title;m.platform="样例";m.qualities.add(new Engine.Quality("best","最高可用画质","bv*+ba/b"));return m;}
    private static Bundle state(String input){Bundle b=new Bundle();b.putString("draft",input);b.putString("tab","new");return b;}
    private static List list(Object o,String name)throws Exception{return field(o,name,List.class);}
    private static <T>T field(Object o,String name,Class<T> type)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o));}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object invoke(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static void drain(MainActivity a)throws Exception{field(a,"io",ExecutorService.class).submit(()->{}).get(8,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
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
