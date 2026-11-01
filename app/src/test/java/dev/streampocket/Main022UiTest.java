package dev.streampocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/** Real native shell and durable preference fixtures; no website or download is contacted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Main022UiTest {
    private Context app;
    @Before public void reset(){app=RuntimeEnvironment.getApplication();app.getSharedPreferences("config",0).edit().clear().putBoolean("motion_enabled",false).commit();app.getSharedPreferences("tasks",0).edit().clear().commit();TaskStore.init(app);}
    @After public void clear(){app.getSharedPreferences("config",0).edit().clear().commit();app.getSharedPreferences("tasks",0).edit().clear().commit();}

    @Test public void inspectionExpandsInlineAndChangedQualityNeverKeepsBestFileEstimate() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class);
        try{
            MainActivity a=c.create(initial()).start().resume().visible().get();
            EditText input=field(a,"input",EditText.class);assertEquals(2,input.getMinLines());assertEquals(4,input.getMaxLines());capture(a.getWindow().getDecorView(),"00-light-home-link-fixture");
            Engine.Media m=fixtureMedia();invoke(a,"showMedia",new Class<?>[]{java.util.List.class},Collections.singletonList(m));drain(a);
            View page=a.getWindow().getDecorView();assertNotNull(find(page,m.title));assertNotNull(find(page,"1920 × 1080 · 预计 128.0 MB"));assertNotNull(find(page,"下载视频"));
            assertTrue("Inline media must not create a task until the user chooses download",TaskStore.all(a).isEmpty());
            assertTrue(ShadowDialog.getLatestDialog()==null||!ShadowDialog.getLatestDialog().isShowing());
            assertTrue("Editor stays available for the next share",input.isShown());assertTrue(field(a,"parse",Button.class).isShown());assertTrue(find(page,"下载视频").isShown());
            capture(page,"01-inline-resolved-metadata-fixture");
            find(page,"画质：最高可用画质  ▾").performClick();
            PocketDialog quality=(PocketDialog)ShadowDialog.getLatestDialog();quality.getListView().performItemClick(quality.getListView().getChildAt(1),1,1);drain(a);
            assertNotNull(find(page,"720p · 大小待下载时确定"));assertNull("Best-format size must not be attributed to another quality",find(page,"1920 × 1080 · 预计 128.0 MB"));
            input.setText("https://x.com/example/status/9999");drain(a);assertNotNull("Typing the next link retains the current unqueued result",find(page,m.title));
            assertTrue(TaskStore.all(a).isEmpty());
        }finally{c.pause().stop().destroy();}
    }

    @Test public void queueShowsRealBytesConnectionStateFiltersAndReversibleRecordRemoval() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class);
        try{
            MainActivity a=c.create(initial()).start().resume().visible().get();
            JSONObject live=task("live","界面样例 · B站视频与音频分开下载","downloading").put("phase","video").put("progress",28).put("stageProgress",28).put("totalBytes",100L*1048576).put("downloadedBytes",28L*1048576).put("speedBytesPerSecond",2.4*1048576).put("detail","当前阶段 28% · 2.4 MB/s · 本阶段约 30 秒").put("transferMode","parallel-http").put("activeConnections",4).put("configuredConnections",4);
            JSONObject paused=task("paused","界面样例 · 暂停后保留原断点","paused").put("queuedAt",4).put("downloadedBytes",6L*1048576).put("totalBytes",100L*1048576).put("detail","已暂停，断点保留");
            JSONObject done=task("done","界面样例 · 已保存成片","completed").put("queuedAt",3).put("size",22L*1048576).put("progress",100);
            JSONObject failed=task("failed","界面样例 · 暂时失败的链接","failed").put("queuedAt",2).put("userError","源站暂时拒绝请求，请稍后重试");
            a.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(live).put(paused).put(done).put(failed).toString()).commit();
            invoke(a,"showTab",new Class<?>[]{String.class},"queue");drain(a);View page=a.getWindow().getDecorView();
            assertNotNull(find(page,"已下载 28.0 MB / 100.0 MB"));assertNull("Transfer diagnostics must not compete with progress in the list",find(page,"分块下载 · 当前 4 个连接 · 上限 4 路"));invoke(a,"showTask",new Class<?>[]{JSONObject.class},live);assertNotNull(find(ShadowDialog.getLatestDialog().getWindow().getDecorView(),"任务详情"));assertTrue(containsText(ShadowDialog.getLatestDialog().getWindow().getDecorView(),"当前 4 个连接"));ShadowDialog.getLatestDialog().dismiss();assertNotNull(find(page,"继续"));assertNotNull(find(page,"文件大小 22.0 MB"));
            assertNull("Secondary record removal belongs to the task menu",find(page,"移除记录"));capture(page,"02-queue-bytes-paused-connection-fixtures");
            find(page,"已完成").performClick();drain(a);assertNotNull(find(page,done.getString("title")));assertNull(find(page,live.getString("title")));
            invoke(a,"removeTaskWithUndo",new Class<?>[]{JSONObject.class},done);drain(a);assertNull(find(page,done.getString("title")));assertNotNull("Removal delay must preserve persisted record until commit",TaskStore.get(a,"done"));
            find(page,"撤销").performClick();drain(a);assertNotNull(find(page,done.getString("title")));assertNotNull(TaskStore.get(a,"done"));
            find(page,"失败").performClick();drain(a);assertNotNull(find(page,failed.getString("title")));assertNull(find(page,done.getString("title")));
        }finally{c.pause().stop().destroy();}
    }

    @Test public void themeRoundTripPreservesPendingLinkAndResolvedVideoWithoutStartingDownloads() throws Exception {
        Bundle saved=new Bundle();ActivityController<MainActivity> first=Robolectric.buildActivity(MainActivity.class);
        try{
            MainActivity a=first.create(initial()).start().resume().visible().get();invoke(a,"showMedia",new Class<?>[]{java.util.List.class},Collections.singletonList(fixtureMedia()));
            invoke(a,"showTab",new Class<?>[]{String.class},"settings");drain(a);View page=a.getWindow().getDecorView();
            for(String group:new String[]{"账户与来源","下载管理","播放与外观","高级与关于"})assertNotNull(find(page,group));
            assertEquals(View.GONE,field(a,"brand",View.class).getVisibility());assertNotNull(find(page,"跟随系统"));capture(page,"03-light-grouped-settings-fixture");
            PocketSwitch gestures=findSwitch(page,"播放手势");assertNotNull(gestures);assertFalse(gestures.isChecked());gestures.setChecked(true);assertTrue(a.getSharedPreferences("config",0).getBoolean("player_gestures",false));
            first.saveInstanceState(saved);a.getSharedPreferences("config",0).edit().putString("appearance_theme","dark").commit();
        }finally{first.pause().stop().destroy();}
        ActivityController<MainActivity> second=Robolectric.buildActivity(MainActivity.class);
        try{
            MainActivity a=second.create(saved).start().resume().visible().get();drain(a);assertTrue(UiTheme.get(a).dark);capture(a.getWindow().getDecorView(),"03-dark-grouped-settings-fixture");
            invoke(a,"showTab",new Class<?>[]{String.class},"new");drain(a);assertEquals(initial().getString("draft"),field(a,"input",EditText.class).getText().toString());
            assertNotNull(find(a.getWindow().getDecorView(),fixtureMedia().title));assertTrue(TaskStore.all(a).isEmpty());capture(a.getWindow().getDecorView(),"04-dark-inline-restored-state-fixture");
        }finally{second.pause().stop().destroy();}
    }

    @Test public void resumedTaskSurvivesDelayedRemovalAndActivityDestruction() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);boolean destroyed=false;
        try{
            MainActivity activity=controller.create(initial()).start().resume().visible().get();
            JSONObject resumed=task("resume-race","界面样例 · 另一窗口已恢复的任务","paused").put("downloadedBytes",6L*1048576).put("totalBytes",100L*1048576);
            JSONObject inactive=task("ordinary-removal","界面样例 · 普通失败记录","failed");
            JSONObject destroyRace=task("destroy-race","界面样例 · 退出前恢复的任务","paused").put("downloadedBytes",9L*1048576).put("totalBytes",100L*1048576);
            activity.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(resumed).put(inactive).put(destroyRace).toString()).commit();
            invoke(activity,"showTab",new Class<?>[]{String.class},"queue");drain(activity);
            invoke(activity,"removeTaskWithUndo",new Class<?>[]{JSONObject.class},resumed);
            invoke(activity,"removeTaskWithUndo",new Class<?>[]{JSONObject.class},inactive);
            assertNull(find(activity.getWindow().getDecorView(),resumed.getString("title")));
            assertTrue("Simulate a different Main instance accepting resume during the undo interval",TaskStore.markQueued(activity,"resume-race"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6));drain(activity);
            JSONObject retained=TaskStore.get(activity,"resume-race");assertNotNull("A resumed task must never become an orphan transfer",retained);assertEquals("queued",retained.getString("status"));assertEquals(6L*1048576,retained.getLong("downloadedBytes"));
            assertNotNull("Rejected removal must stop hiding the task",find(activity.getWindow().getDecorView(),resumed.getString("title")));
            assertNull("Ordinary inactive removal still commits",TaskStore.get(activity,"ordinary-removal"));
            invoke(activity,"removeTaskWithUndo",new Class<?>[]{JSONObject.class},destroyRace);assertTrue(TaskStore.markQueued(activity,"destroy-race"));
            controller.pause().stop().destroy();destroyed=true;
            JSONObject afterDestroy=TaskStore.get(app,"destroy-race");assertNotNull("Activity cleanup must also recheck the current task atomically",afterDestroy);assertEquals("queued",afterDestroy.getString("status"));assertEquals(9L*1048576,afterDestroy.getLong("downloadedBytes"));
        }finally{if(!destroyed)controller.pause().stop().destroy();}
    }

    @Test public void staleSwipeNeverInvertsANewerTaskStateAndNextLinkKeepsPendingResults() throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class);
        try{
            MainActivity a=c.create(initial()).start().resume().visible().get();invoke(a,"showMedia",new Class<?>[]{java.util.List.class},Collections.singletonList(fixtureMedia()));
            field(a,"input",EditText.class).setText("https://x.com/example/status/9999");assertTrue(field(a,"input",EditText.class).isShown());assertTrue(field(a,"parse",Button.class).isShown());assertNotNull(find(a.getWindow().getDecorView(),fixtureMedia().title));assertEquals("https://x.com/example/status/9999",field(a,"input",EditText.class).getText().toString());
            JSONObject live=task("race","状态竞争样例","downloading").put("phase","video");a.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(live).toString()).commit();invoke(a,"showTab",new Class<?>[]{String.class},"queue");
            TaskStore.update(a,"race",new JSONObject().put("status","paused"));invoke(a,"performSwipeAction",new Class<?>[]{String.class,String.class},"race","pauseable");assertEquals("paused",TaskStore.get(a,"race").getString("status"));assertNull(Shadows.shadowOf(a).getNextStartedService());
            TaskStore.update(a,"race",new JSONObject().put("status","merging"));invoke(a,"performSwipeAction",new Class<?>[]{String.class,String.class},"race","paused");assertEquals("merging",TaskStore.get(a,"race").getString("status"));assertNull(Shadows.shadowOf(a).getNextStartedService());
        }finally{c.pause().stop().destroy();}
    }

    private static Bundle initial(){Bundle s=new Bundle();s.putString("draft","https://www.bilibili.com/video/BV1nx411u79K");s.putString("tab","new");return s;}
    private static Engine.Media fixtureMedia(){Engine.Media m=new Engine.Media();m.url="https://www.bilibili.com/video/BV1nx411u79K";m.id="explicit-ui-fixture";m.title="界面样例 · 一百秒的山间行走，保留完整画幅与原片细节";m.uploader="界面测试样例作者";m.platform="哔哩哔哩";m.duration=100;m.width=1920;m.height=1080;m.filesize=128L*1048576;m.filesizeEstimated=true;m.qualities.add(new Engine.Quality("best","最高可用画质","bv*+ba/b"));m.qualities.add(new Engine.Quality("720","720p","b[height=720]"));return m;}
    private static JSONObject task(String id,String title,String status)throws Exception{return new JSONObject().put("id",id).put("title",title).put("status",status).put("url","https://example.invalid/ui-fixture").put("uri","").put("createdAt",1).put("queuedAt",1).put("userError","").put("detail","");}
    private static void drain(MainActivity a)throws Exception{field(a,"io",ExecutorService.class).submit(()->{}).get(15,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static Object invoke(Object o,String name,Class<?>[] types,Object... values)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,values);}
    private static <T>T field(Object o,String name,Class<T> type)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o));}
    private static TextView find(View view,String exact){if(view instanceof TextView&&((TextView)view).getText().toString().equals(exact))return (TextView)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int n=0;n<g.getChildCount();n++){TextView found=find(g.getChildAt(n),exact);if(found!=null)return found;}}return null;}
    private static boolean containsText(View v,String value){if(v instanceof TextView&&((TextView)v).getText().toString().contains(value))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(containsText(((ViewGroup)v).getChildAt(i),value))return true;return false;}
    private static PocketSwitch findSwitch(View v,String description){if(v instanceof PocketSwitch&&description.contentEquals(v.getContentDescription()))return (PocketSwitch)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){PocketSwitch found=findSwitch(g.getChildAt(n),description);if(found!=null)return found;}}return null;}
    private static void capture(View decor,String name)throws Exception{
        float density=decor.getResources().getDisplayMetrics().density;int width=Math.round(412*density),height=Math.round(915*density);decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));decor.layout(0,0,width,height);
        File root=new File(System.getProperty("user.dir"));while(root!=null&&!new File(root,"settings.gradle").isFile())root=root.getParentFile();assertNotNull(root);File output=new File(root,"docs/design/assets/022-main");assertTrue(output.isDirectory()||output.mkdirs());
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(b));try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{b.recycle();}
    }
}
