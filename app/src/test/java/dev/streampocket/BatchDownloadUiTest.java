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
    @Test public void liveLinkRecognitionKeepsTheDraftAndDoesNotStartParsing()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity a=controller.get();String draft="分享 https://x.com/u/status/1 与 https://www.bilibili.com/video/BVtest";field(a,"input",EditText.class).setText(draft);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertTrue(field(a,"linkHint",TextView.class).getText().toString().contains("识别到 2 个链接"));assertEquals(draft,field(a,"input",EditText.class).getText().toString());assertTrue(inbox(a).jobs.isEmpty());assertNull(field(a,"activeInspection",Object.class));
        }
    }
    @Test public void manualPasteAppendsAllClipboardItemsWithoutReplacingTheDraftOrRepeatingLinks()throws Exception{
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData data=android.content.ClipData.newPlainText("fixture","分享 https://x.com/u/status/123");data.addItem(new android.content.ClipData.Item("https://b23.tv/second"));clipboard.setPrimaryClip(data);
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create(state("保留我的备注\nhttps://twitter.com/u/status/123")).start().resume().visible()){
            MainActivity a=c.get();invoke(a,"pasteLink",new Class<?>[0]);
            String expected="保留我的备注\nhttps://twitter.com/u/status/123\nhttps://b23.tv/second";
            assertEquals(expected,field(a,"input",EditText.class).getText().toString());
            invoke(a,"pasteLink",new Class<?>[0]);assertEquals(expected,field(a,"input",EditText.class).getText().toString());
            assertTrue(inbox(a).jobs.isEmpty());assertTrue(TaskStore.all(a).isEmpty());
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture","普通文字，没有链接"));invoke(a,"pasteLink",new Class<?>[0]);
            assertEquals(expected,field(a,"input",EditText.class).getText().toString());assertTrue(field(a,"linkHint",TextView.class).getText().toString().contains("未找到"));
        }finally{clipboard.clearPrimaryClip();}
    }
    @Test public void invalidInputCannotOfferParsingAndValidInputShowsRecognizedSources()throws Exception{
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity a=c.get();field(a,"input",EditText.class).setText("这一段没有链接");Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertFalse(field(a,"parse",android.widget.Button.class).isEnabled());assertTrue(field(a,"linkHint",TextView.class).getText().toString().contains("未找到"));
            field(a,"input",EditText.class).setText("看看这个视频 https://www.bilibili.com/video/BV1nx411u79K 和 https://x.com/creator/status/12345");Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertTrue(field(a,"parse",android.widget.Button.class).isEnabled());assertNotNull(find(a.getWindow().getDecorView(),"解析链接 · 2 个"));
            assertNotNull(find(a.getWindow().getDecorView(),"哔哩哔哩 · www.bilibili.com/video/BV1nx411u79K"));assertTrue(inbox(a).jobs.isEmpty());capture(a,"intake-recognized-fixture.png");
        }
    }
    @Test public void clipboardReviewCanSelectOneLinkAndCancelWithoutTouchingTheDraft()throws Exception{
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture","https://x.com/u/status/123 https://b23.tv/second"));
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create(state("还没提交的备注和链接 https://v.douyin.com/kept/")).start().resume().visible()){
            MainActivity a=c.get();List<String> calls=new ArrayList<>();set(a,"inspector",(InspectionBatch.Inspector)(url,p,k)->{calls.add(url);return Collections.singletonList(media(url,"离线界面样例 · 所选视频"));});
            c.windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idle();find(a.getWindow().getDecorView(),"查看链接").performClick();
            PocketDialog first=(PocketDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertTrue(calls.isEmpty());assertEquals(2,first.getListView().getCount());captureDialog(first,"clipboard-review-fixture.png");first.cancel();
            assertTrue(calls.isEmpty());assertNotNull(find(a.getWindow().getDecorView(),"查看链接"));assertEquals("",a.getPreferences(0).getString("clipboard_seen",""));
            find(a.getWindow().getDecorView(),"查看链接").performClick();PocketDialog review=(PocketDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            review.getListView().performItemClick(review.getListView().getChildAt(0),0,0);assertEquals("解析 1 个",review.getButton(PocketDialog.BUTTON_POSITIVE).getText().toString());
            review.getListView().performItemClick(review.getListView().getChildAt(1),1,1);assertFalse(review.getButton(PocketDialog.BUTTON_POSITIVE).isEnabled());
            review.getListView().performItemClick(review.getListView().getChildAt(1),1,1);review.getButton(PocketDialog.BUTTON_POSITIVE).performClick();drain(a);
            assertEquals(Collections.singletonList("https://b23.tv/second"),calls);assertEquals("还没提交的备注和链接 https://v.douyin.com/kept/",field(a,"input",EditText.class).getText().toString());assertTrue(TaskStore.all(a).isEmpty());
            assertEquals(ClipboardOffer.fingerprint(LinkBatch.parse("https://x.com/u/status/123 https://b23.tv/second").urls),a.getPreferences(0).getString("clipboard_seen",""));
            c.pause().resume().windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(View.GONE,field(a,"clipboardNotice",View.class).getVisibility());
        }finally{clipboard.clearPrimaryClip();}
    }
    @Test public void resultsTakeFocusWithoutLosingAnEditableNextLink()throws Exception{
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity a=c.get();Engine.Media ready=media("https://b23.tv/ready","离线界面样例 · 山间行走的视频，确认画质后保存到手机");ready.platform="哔哩哔哩";ready.uploader="示例作者";ready.duration=100;
            invoke(a,"showMedia",new Class<?>[]{List.class},Collections.singletonList(ready));
            assertFalse("An empty editor must yield space to the result",field(a,"input",EditText.class).isShown());assertNotNull(find(a.getWindow().getDecorView(),"＋ 继续添加链接"));capture(a,"intake-ready-fixture.png");
            find(a.getWindow().getDecorView(),"＋ 继续添加链接").performClick();assertTrue(field(a,"input",EditText.class).isShown());
            field(a,"input",EditText.class).setText("https://x.com/u/status/next");invoke(a,"showTab",new Class<?>[]{String.class},"queue");invoke(a,"showTab",new Class<?>[]{String.class},"new");
            assertTrue(field(a,"input",EditText.class).isShown());assertEquals("https://x.com/u/status/next",field(a,"input",EditText.class).getText().toString());assertEquals(1,inbox(a).media.size());assertTrue(TaskStore.all(a).isEmpty());
        }
    }
    @Test public void anUnhandledClipboardSurvivesLeavingTheAppAndAnIgnoredOneDoesNotReturn()throws Exception{
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture","https://b23.tv/unhandled"));
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity a=c.get();c.windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotNull(find(a.getWindow().getDecorView(),"查看链接"));
            c.pause().resume().windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("",a.getPreferences(0).getString("clipboard_seen",""));assertEquals(View.VISIBLE,field(a,"clipboardNotice",View.class).getVisibility());
            find(a.getWindow().getDecorView(),"×").performClick();c.pause().resume().windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(View.GONE,field(a,"clipboardNotice",View.class).getVisibility());assertTrue(inbox(a).jobs.isEmpty());
        }finally{clipboard.clearPrimaryClip();}
    }
    @Test @Config(qualifiers="w320dp-h640dp-xhdpi") public void longClipboardBatchHasScrollableChoicesAndReachableConfirmation()throws Exception{
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","dark").commit();
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity a=c.get();List<String> urls=new ArrayList<>();for(int n=0;n<100;n++)urls.add("https://x.com/long_creator_name/status/"+(1234567890123456000L+n));set(a,"clipboardUrls",urls);invoke(a,"renderClipboardOffer",new Class<?>[0]);invoke(a,"showClipboardReview",new Class<?>[0]);
            PocketDialog review=(PocketDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertEquals(100,review.getListView().getCount());captureDialog(review,"clipboard-small-dark-fixture.png");
            android.graphics.Rect visible=new android.graphics.Rect();assertTrue("Confirmation must be visible",review.getButton(PocketDialog.BUTTON_POSITIVE).getGlobalVisibleRect(visible));assertTrue("Confirmation touch target must not be clipped: "+visible,visible.height()>=Math.round(44*a.getResources().getDisplayMetrics().density));assertTrue(inbox(a).jobs.isEmpty());
            find(review.getWindow().getDecorView(),"清空").performClick();assertFalse(review.getButton(PocketDialog.BUTTON_POSITIVE).isEnabled());
            find(review.getWindow().getDecorView(),"全选").performClick();assertTrue(review.getButton(PocketDialog.BUTTON_POSITIVE).isEnabled());assertEquals("解析 100 个",review.getButton(PocketDialog.BUTTON_POSITIVE).getText().toString());review.cancel();
        }
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
            assertNotNull(find(a.getWindow().getDecorView(),"全部下载"));assertEquals("Only the current page may occupy the content area",1,field(a,"body",ViewGroup.class).getChildCount());capture(a,"batch-partial-fixture.png");
            field(a,"input",EditText.class).setText("https://x.com/u/status/next");
            find(a.getWindow().getDecorView(),"全部下载").performClick();
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
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertNotNull("Cancel belongs beside the current link",find(a.getWindow().getDecorView(),"取消"));invoke(a,"cancelAnalysis",new Class<?>[0]);drain(a);
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
            find(a.getWindow().getDecorView(),"查看链接").performClick();
            assertTrue("Reviewing links must not start a request",list(a,"inspectedMedia").isEmpty());
            PocketDialog review=(PocketDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            review.getButton(PocketDialog.BUTTON_POSITIVE).performClick();drain(a);
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
    private static void captureDialog(PocketDialog dialog,String name)throws Exception{
        Shadows.shadowOf(Looper.getMainLooper()).idle();View view=dialog.getWindow().getDecorView();int width=dialog.getWindow().getAttributes().width,height=dialog.getContext().getResources().getDisplayMetrics().heightPixels;if(width<=0)width=dialog.getContext().getResources().getDisplayMetrics().widthPixels;
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST));view.layout(0,0,width,view.getMeasuredHeight());
        File dir=new File("build/reports/view-fixtures/031-batch");assertTrue(dir.isDirectory()||dir.mkdirs());Bitmap bitmap=Bitmap.createBitmap(width,view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(dir,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
}
