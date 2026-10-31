package dev.streampocket;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/** Actual native View renders of labelled UI fixtures; no download or website is contacted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SpeedProgressUiRenderTest {
    @Test public void speedChoiceIsDisclosedSavedAndPreservesPendingLink() throws Exception {
        Bundle state=initialState();
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        MainActivity activity=null;
        try {
            activity=controller.create(state).start().resume().visible().get();
            activity.getSharedPreferences("config",0).edit().remove("download_connections").commit();
            tab(activity,"settings");
            assertNotNull("Current settings must contain download speed controls; rendered text: "+allText(activity),findText(activity.getWindow().getDecorView(),"下载与速度"));
            assertNull("Advanced choices should not cover the main settings list",findText(activity.getWindow().getDecorView(),"并行 · 8 路连接"));
            invoke(activity,"showDownloadSettings",new Class<?>[0]);drain(activity);
            Dialog panel=ShadowDialog.getLatestDialog();assertTrue(panel.isShowing());
            RadioButton recommended=(RadioButton)findText(panel.getWindow().getDecorView(),"并行 · 4 路连接（推荐）");
            assertNotNull(recommended);assertTrue("Fresh install defaults to four connections",recommended.isChecked());
            capture(panel.getWindow().getDecorView(),"014-speed-settings");
            RadioButton higher=(RadioButton)findText(panel.getWindow().getDecorView(),"并行 · 8 路连接");higher.performClick();
            findButton(panel.getWindow().getDecorView(),"保存下载设置").performClick();drain(activity);
            assertEquals(8,activity.getSharedPreferences("config",0).getInt("download_connections",0));
            invoke(activity,"showDownloadSettings",new Class<?>[0]);drain(activity);
            Dialog reopened=ShadowDialog.getLatestDialog();
            assertTrue(((RadioButton)findText(reopened.getWindow().getDecorView(),"并行 · 8 路连接")).isChecked());reopened.dismiss();
            tab(activity,"new");
            assertEquals(state.getString("draft"),field(activity,"input",EditText.class).getText().toString());
            assertTrue("Changing speed must not start a task",TaskStore.all(activity).isEmpty());
        } finally {
            if(activity!=null)activity.getSharedPreferences("config",0).edit().remove("download_connections").commit();
            controller.pause().stop().destroy();
        }
    }

    @Test public void trackTransitionsKeepOverallMeaningAndProcessingNeverPretendsSaved() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        MainActivity activity=null;
        try {
            activity=controller.create(initialState()).start().resume().visible().get();
            JSONObject task=fixture("video",72,80,"下载视频 · 当前阶段 80% · 第 1 / 2 部分");
            showFixture(activity,task);
            assertNotNull("Video phase must be visible; rendered text: "+allText(activity),findText(activity.getWindow().getDecorView(),"下载视频"));
            assertNotNull(findText(activity.getWindow().getDecorView(),"整体 72%"));
            assertFalse(firstBar(activity).isIndeterminate());assertEquals(72,firstBar(activity).getProgress());
            capture(activity.getWindow().getDecorView(),"014-progress-video-fixture");

            task=fixture("audio",92,20,"下载音频 · 当前阶段 20% · 第 2 / 2 部分");
            showFixture(activity,task);
            assertNotNull(findText(activity.getWindow().getDecorView(),"下载音频"));
            assertNotNull(findText(activity.getWindow().getDecorView(),"整体 92%"));
            assertEquals("Next file may start at 20%, aggregate must remain at 92%",92,firstBar(activity).getProgress());
            assertFalse("Detailed track counters are disclosed inside task details",allText(activity).contains("当前阶段 20%"));
            invoke(activity,"showTask",new Class<?>[]{JSONObject.class},task);assertTrue(allText(ShadowDialog.getLatestDialog().getWindow().getDecorView()).contains("当前阶段 20%"));ShadowDialog.getLatestDialog().dismiss();
            capture(activity.getWindow().getDecorView(),"014-progress-audio-fixture");

            task=fixture("audio",-1,20,"下载音频 · 当前阶段 20% · 第 2 / 2 部分").put("totalBytes",0);
            showFixture(activity,task);assertTrue(firstBar(activity).isIndeterminate());
            assertTrue(allText(activity).contains("整体大小尚未确定"));
            assertFalse("Unknown aggregate must not appear as zero percent",allText(activity).contains("整体 0%"));
            capture(activity.getWindow().getDecorView(),"014-progress-unknown-fixture");

            task=fixture("retrying",92,20,"音频片段请求失败，正在重试（第 1 次）");
            showFixture(activity,task);assertNotNull(findText(activity.getWindow().getDecorView(),"正在重试"));
            assertEquals(92,firstBar(activity).getProgress());
            capture(activity.getWindow().getDecorView(),"014-progress-retrying-fixture");

            task=fixture("merging",-1,100,"视频和音频已下载完成，正在合并。")
                    .put("status","merging").put("transferComplete",true).put("completedStreams",2);
            showFixture(activity,task);assertTrue(firstBar(activity).isIndeterminate());
            assertNotNull(findText(activity.getWindow().getDecorView(),"正在合并音视频"));
            assertFalse("Merged bytes are not yet publicly saved",allText(activity).contains("100%"));
            capture(activity.getWindow().getDecorView(),"014-progress-merging-fixture");

            task.put("status","saving").put("phase","saving").put("detail","正在写入 Download / StreamPocket。");
            showFixture(activity,task);assertNotNull(findText(activity.getWindow().getDecorView(),"正在保存到手机"));
            assertFalse(allText(activity).contains("100%"));
            capture(activity.getWindow().getDecorView(),"014-progress-saving-fixture");

            task.put("status","completed").put("progress",100).put("detail","已保存到 Download / StreamPocket（界面样例）");
            showFixture(activity,task);assertNotNull(findText(activity.getWindow().getDecorView(),"已保存"));
            assertNotNull(findText(activity.getWindow().getDecorView(),"100%"));
            assertNotNull("Completed tasks now expose the internal player",findText(activity.getWindow().getDecorView(),"播放"));
            capture(activity.getWindow().getDecorView(),"014-progress-completed-fixture");
        } finally {
            if(activity!=null)activity.getSharedPreferences("tasks",0).edit().putString("items","[]").commit();
            controller.pause().stop().destroy();
        }
    }

    private static Bundle initialState(){Bundle state=new Bundle();state.putString("tab","new");state.putString("draft","https://x.com/hightower90/status/2115450261879599507");return state;}
    private static JSONObject fixture(String phase,double overall,double stage,String detail) throws Exception {
        return new JSONObject().put("id","ui-fixture-only").put("title","界面样例 · 百秒视频的音视频下载阶段")
                .put("url","https://example.invalid/ui-fixture").put("status","downloading").put("phase",phase)
                .put("progress",overall).put("stageProgress",stage).put("totalStreams",2).put("completedStreams","video".equals(phase)?0:1)
                .put("downloadedBytes",(long)(overall<0?0:overall*1000000)).put("totalBytes",100000000L)
                .put("transferComplete",false).put("detail",detail).put("userError","").put("uri","");
    }
    private static void showFixture(MainActivity activity,JSONObject fixture) throws Exception {
        activity.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(fixture).toString()).commit();tab(activity,"queue");
    }
    private static void tab(MainActivity activity,String value)throws Exception{invoke(activity,"showTab",new Class<?>[]{String.class},value);drain(activity);}
    private static void drain(MainActivity activity)throws Exception{field(activity,"io",ExecutorService.class).submit(()->{}).get(15,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static Object invoke(Object owner,String name,Class<?>[] types,Object... arguments)throws Exception{Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,arguments);}
    private static <T>T field(Object owner,String name,Class<T> type)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(owner));}
    private static TextView findText(View view,String exact){if(view instanceof TextView&&exact.equals(((TextView)view).getText().toString()))return(TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView v=findText(((ViewGroup)view).getChildAt(i),exact);if(v!=null)return v;}return null;}
    private static Button findButton(View view,String exact){return(Button)findText(view,exact);}
    private static ProgressBar firstBar(MainActivity activity){return firstBar(activity.getWindow().getDecorView());}
    private static ProgressBar firstBar(View view){if(view instanceof ProgressBar)return(ProgressBar)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ProgressBar bar=firstBar(((ViewGroup)view).getChildAt(i));if(bar!=null)return bar;}return null;}
    private static String allText(MainActivity activity){return allText(activity.getWindow().getDecorView());}
    private static String allText(View view){StringBuilder s=new StringBuilder();if(view instanceof TextView)s.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)s.append(allText(((ViewGroup)view).getChildAt(i)));return s.toString();}
    private static void capture(View decor,String name)throws Exception{
        float density=decor.getResources().getDisplayMetrics().density;int width=Math.round(412*density),height=Math.round(915*density);ViewGroup.LayoutParams params=decor.getLayoutParams();
        if(params!=null&&params.width>0)width=Math.min(width,params.width);if(params!=null&&params.height>0)height=Math.min(height,params.height);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width,params!=null&&params.width==ViewGroup.LayoutParams.WRAP_CONTENT?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,params!=null&&params.height==ViewGroup.LayoutParams.WRAP_CONTENT?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY));
        width=decor.getMeasuredWidth();height=decor.getMeasuredHeight();decor.layout(0,0,width,height);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.TRANSPARENT);decor.draw(new Canvas(bitmap));
        File output=new File("build/ui-render");if(!output.isDirectory()&&!output.mkdirs())throw new java.io.IOException("Cannot create UI render directory");
        try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{bitmap.recycle();}
    }
}
