package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
import static org.junit.Assert.*;

/** Native View, SQLite and explicit invalid-media fixtures; no device decoder or export claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Editor022UiTest {
    private Context app;
    private LibraryStore store;
    private File media;
    private ActivityController<EditorActivity> controller;
    @Before public void before() throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");store=new LibraryStore(app);
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","light").putBoolean("motion_enabled",false).commit();
        media=File.createTempFile("editor022-ui-fixture-",".mp4",app.getCacheDir());
        try(FileOutputStream out=new FileOutputStream(media)){out.write(new byte[]{0,0,0,0});}
    }
    @After public void after() throws Exception {
        closeActivity();store.close();media.delete();app.deleteDatabase("media-library.db");
        app.getSharedPreferences("config",0).edit().remove("appearance_theme").remove("motion_enabled").commit();
    }
    @Test public void handlesDragIndependentlyClampAndReportFinalRangeWithoutCreatingWork() {
        ClipRangeView range=new ClipRangeView(app);measure(range,360,96);range.setRange(100_800,20_000,80_000);
        List<Boolean> tracking=new ArrayList<>();range.setListener((from,to,handle,moving)->{assertTrue(from>=0&&to<=100_800&&from<to);tracking.add(moving);});
        float left=dp(24),width=range.getWidth()-dp(48),y=dp(40);
        touch(range,MotionEvent.ACTION_DOWN,left+width*(20_000f/100_800),y);
        touch(range,MotionEvent.ACTION_MOVE,left+width*.35f,y);touch(range,MotionEvent.ACTION_UP,left+width*.35f,y);
        assertTrue(range.getStartMs()>34_000&&range.getStartMs()<36_000);assertEquals(80_000,range.getEndMs());
        touch(range,MotionEvent.ACTION_DOWN,left+width*(80_000f/100_800),y);
        touch(range,MotionEvent.ACTION_MOVE,left+width*1.2f,y);touch(range,MotionEvent.ACTION_UP,left+width*1.2f,y);
        assertEquals(100_800,range.getEndMs());assertFalse(tracking.get(tracking.size()-1));assertTrue(store.projects().isEmpty());assertFalse(ExportService.isRunning(1));
    }
    @Test public void virtualEndpointsExposeFortyEightDpBoundsAndExactProgressActions() {
        ClipRangeView range=new ClipRangeView(app);measure(range,360,96);range.setRange(100_800,12_789,24_567);
        AccessibilityNodeProvider provider=range.getAccessibilityNodeProvider();
        AccessibilityNodeInfo host=provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);assertEquals(2,host.getChildCount());
        AccessibilityNodeInfo end=provider.createAccessibilityNodeInfo(ClipRangeView.END);Rect bounds=new Rect();end.getBoundsInParent(bounds);assertTrue(bounds.width()>=dp(48));assertTrue(bounds.height()>=dp(48));assertEquals("android.widget.SeekBar",end.getClassName());
        Bundle progress=new Bundle();progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,100.5f);
        assertTrue(provider.performAction(ClipRangeView.END,AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),progress));assertEquals(100_500,range.getEndMs());
        assertEquals(100.5f,provider.createAccessibilityNodeInfo(ClipRangeView.END).getRangeInfo().getCurrent(),.0001f);
        progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,Float.NaN);assertFalse(provider.performAction(ClipRangeView.END,AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),progress));
        provider.performAction(ClipRangeView.START,AccessibilityNodeInfo.ACTION_CLICK,null);long before=range.getStartMs();
        assertTrue(range.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)));assertTrue(range.getStartMs()>before);assertEquals(100_500,range.getEndMs());
    }
    @Test public void originalMillisecondsStaySynchronizedAndExpandedRangeSurvivesRecreation() throws Exception {
        long video=store.addExportedVideo("界面夹具 · 很长的中文原片标题：在同一个时间轴上挑选精彩片段，原视频不会改变",Uri.fromFile(media).toString(),"video/mp4",4,100_000);
        long project=store.createProject("界面夹具 · 精彩片段组合");
        Intent intent=new Intent(app,EditorActivity.class).putExtra("video_id",video).putExtra("project_id",project).putExtra("start_ms",12_789L).putExtra("end_ms",24_567L);
        controller=Robolectric.buildActivity(EditorActivity.class,intent).create().start();EditorActivity activity=controller.get();drain(activity);
        ClipRangeView range=field(activity,"rangeView",ClipRangeView.class);
        assertEquals(12_789,range.getStartMs());assertEquals(24_567,range.getEndMs());assertEquals(View.GONE,field(activity,"preciseFields",View.class).getVisibility());
        assertEquals(UiTheme.get(activity).bg,((ColorDrawable)field(activity,"root",View.class).getBackground()).getColor());capture(activity,"01-trim-light-range-fixture");
        activity.onMeasuredDuration(100_800);assertEquals(100_800,range.getDurationMs());
        Bundle progress=new Bundle();progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,100.5f);range.getAccessibilityNodeProvider().performAction(ClipRangeView.END,AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),progress);
        assertEquals("01:40.500",field(activity,"endInput",EditText.class).getText().toString());
        findButton(activity.getWindow().getDecorView(),"精确时间").performClick();assertEquals(View.VISIBLE,field(activity,"preciseFields",View.class).getVisibility());
        field(activity,"startInput",EditText.class).setText("15.625");assertEquals(15_625,range.getStartMs());assertTrue(store.clips(project).isEmpty());capture(activity,"02-trim-exact-input-fixture");
        Bundle saved=new Bundle();controller.saveInstanceState(saved);closeActivity();
        controller=Robolectric.buildActivity(EditorActivity.class,intent).create(saved).start();activity=controller.get();drain(activity);
        assertEquals("15.625",field(activity,"startInput",EditText.class).getText().toString());assertEquals("01:40.500",field(activity,"endInput",EditText.class).getText().toString());assertEquals(View.VISIBLE,field(activity,"preciseFields",View.class).getVisibility());
        assertTrue(store.clips(project).isEmpty());field(activity,"addButton",Button.class).performClick();drain(activity);
        assertEquals(1,store.clips(project).size());assertEquals(15_625,store.clips(project).get(0).optLong("startMs"));assertEquals(100_500,store.clips(project).get(0).optLong("endMs"));assertFalse(ExportService.isRunning(project));capture(activity,"03-draft-media-list-fixture");
    }
    @Test public void darkTrimUsesSharedPaletteAndKeepsThePreviewLarge() throws Exception {
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","dark").commit();
        long video=store.addExportedVideo("界面夹具 · 深色剪辑视图",Uri.fromFile(media).toString(),"video/mp4",4,100_000);
        controller=Robolectric.buildActivity(EditorActivity.class,new Intent(app,EditorActivity.class).putExtra("video_id",video)).create().start();EditorActivity activity=controller.get();drain(activity);
        assertEquals(UiTheme.get(activity).bg,((ColorDrawable)field(activity,"root",View.class).getBackground()).getColor());
        View preview=field(activity,"playerView",View.class);assertTrue("FIT preview must remain usable without pushing the final actions offscreen",preview.getLayoutParams().height>=dp(180)&&preview.getLayoutParams().height<=dp(320));
        controller.visible();View export=field(activity,"trimExportButton",View.class);assertEquals(dp(48),export.getLayoutParams().height);assertTrue(export.isShown());assertEquals(View.GONE,field(activity,"preciseFields",View.class).getVisibility());capture(activity,"04-trim-dark-range-fixture");
    }
    private int dp(float value){return Math.round(value*app.getResources().getDisplayMetrics().density);}
    private void measure(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(height),View.MeasureSpec.EXACTLY));view.layout(0,0,dp(width),dp(height));}
    private static void touch(View view,int action,float x,float y){MotionEvent event=MotionEvent.obtain(0,1,action,x,y,0);try{assertTrue(view.onTouchEvent(event));}finally{event.recycle();}}
    private static Button findButton(View view,String label){if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=findButton(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private void closeActivity() throws Exception {if(controller==null)return;ExecutorService worker=field(controller.get(),"io",ExecutorService.class);controller.stop().destroy();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));controller=null;}
    private static void drain(EditorActivity activity) throws Exception {ExecutorService worker=field(activity,"io",ExecutorService.class);for(int i=0;i<4;i++){worker.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private static <T>T field(Object owner,String name,Class<T> type)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(owner));}
    private static void capture(EditorActivity activity,String name)throws Exception{
        View decor=activity.getWindow().getDecorView();int width=Math.round(412*decor.getResources().getDisplayMetrics().density),height=Math.round(915*decor.getResources().getDisplayMetrics().density);decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));decor.layout(0,0,width,height);
        File root=new File(System.getProperty("user.dir"));while(root!=null&&!new File(root,"settings.gradle").isFile())root=root.getParentFile();File directory=new File(root==null?new File(System.getProperty("user.dir")):root,"docs/evidence/028/regression/022-editor");if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("Cannot create render directory");
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
}
