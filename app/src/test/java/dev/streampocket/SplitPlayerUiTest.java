package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SplitPlayerUiTest {
    @Before public void clear(){RuntimeEnvironment.getApplication().deleteDatabase("media-library.db");}
    @Test public void splitCanChangeArrangementPickEachSourceAndRestoreSession() throws Exception {
        try(ActivityController<SplitPlayerActivity> c=Robolectric.buildActivity(SplitPlayerActivity.class).setup()){
            SplitPlayerActivity a=c.get();layout(a);LinearLayout panes=(LinearLayout)get(a,"panes");assertEquals(2,panes.getChildCount());assertEquals(LinearLayout.VERTICAL,panes.getOrientation());
            ((Button)get(a,"layoutButton")).performClick();layout(a);assertEquals(LinearLayout.HORIZONTAL,panes.getOrientation());
            Object[] videos=(Object[])get(a,"videos");((Button)get(videos[1],"sound")).performClick();assertEquals(1,get(a,"audible"));
            Bundle state=new Bundle();a.onSaveInstanceState(state);assertTrue(state.getBoolean("horizontal"));assertEquals(1,state.getInt("audible"));
            installFrame(panes.getChildAt(0),"静态参考帧 A · 布局夹具","ref20-5000.png");installFrame(panes.getChildAt(1),"静态参考帧 B · 布局夹具","ref20-14285.png");
            ((Button)get(a,"layoutButton")).performClick();layout(a);capture(a,"split-portrait");((Button)get(a,"layoutButton")).performClick();layout(a);capture(a,"split-side-by-side");
            try(ActivityController<SplitPlayerActivity> restored=Robolectric.buildActivity(SplitPlayerActivity.class).create(state)){
                assertTrue((Boolean)get(restored.get(),"horizontal"));assertEquals(1,get(restored.get(),"audible"));
            }
        }
    }
    @Test public void twoPlayersKeepSeparatePositionsOneSoundAndReleaseBothOnBackground() throws Exception {
        Context app=RuntimeEnvironment.getApplication();long[] ids=new long[2];
        try(LibraryStore store=new LibraryStore(app)){for(int n=0;n<2;n++)ids[n]=store.upsertCompletedDownload(new JSONObject().put("id","split-"+n).put("status","completed").put("title","离线播放时钟 "+n).put("uri","file:///nonexistent-clock-"+n+".mp4").put("media",new JSONObject().put("duration",100)));}
        try(ActivityController<SplitPlayerActivity> c=Robolectric.buildActivity(SplitPlayerActivity.class,new Intent(app,SplitPlayerActivity.class)).setup()){
            SplitPlayerActivity a=c.get();Object[] panes=(Object[])get(a,"videos");Player022UiTest.Clock[] clocks={new Player022UiTest.Clock(),new Player022UiTest.Clock()};
            for(int n=0;n<2;n++){clocks[n].position=12000+23000*n;clocks[n].play=true;set(panes[n],"id",ids[n]);set(panes[n],"player",clocks[n].proxy());MediaWorkload.playerStarted();java.lang.reflect.Method update=panes[n].getClass().getDeclaredMethod("updatePlay");update.setAccessible(true);update.invoke(panes[n]);}
            ((Button)get(panes[1],"sound")).performClick();assertEquals(0f,clocks[0].volume,.001f);assertEquals(1f,clocks[1].volume,.001f);assertTrue(clocks[0].play&&clocks[1].play);
            clocks[0].playbackState=androidx.media3.common.Player.STATE_ENDED;clocks[0].position=100000;((Button)get(panes[0],"play")).performClick();assertEquals("Ended video must replay from zero",0,clocks[0].position);clocks[0].playbackState=androidx.media3.common.Player.STATE_READY;clocks[0].position=12000;
            android.media.AudioManager.OnAudioFocusChangeListener focus=(android.media.AudioManager.OnAudioFocusChangeListener)get(a,"audioChanges");set(a,"focusHeld",true);
            focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);((Button)get(panes[0],"play")).performClick();assertFalse("Transient focus loss must keep both paused",clocks[0].play);assertFalse(clocks[1].play);
            focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN);assertTrue(clocks[0].play&&clocks[1].play);
            focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);((Button)get(panes[0],"sound")).performClick();assertEquals(.2f,clocks[0].volume,.001f);assertEquals(0f,clocks[1].volume,.001f);
            a.finish();Intent result=Shadows.shadowOf(a).getResultIntent();assertEquals(12000,result.getLongExtra("position_ms",-1));assertEquals(ids[0],result.getLongExtra("video_id",-1));
            Bundle saved=new Bundle();a.onSaveInstanceState(saved);assertEquals(12000,saved.getLong("position0"));assertEquals(35000,saved.getLong("position1"));
            c.pause();await(a);for(Object pane:panes)assertNull(get(pane,"player"));assertFalse(MediaWorkload.isPlayerActive());
            try(LibraryStore db=new LibraryStore(app)){assertEquals(12000,db.video(ids[0]).optLong("playbackMs"));assertEquals(35000,db.video(ids[1]).optLong("playbackMs"));}
        }
    }
    private static void installFrame(View parent,String title,String file)throws Exception{
        android.widget.FrameLayout frame=(android.widget.FrameLayout)parent;android.widget.ImageView image=new android.widget.ImageView(parent.getContext());File root=new File(System.getProperty("user.dir"));while(!new File(root,"settings.gradle").isFile())root=root.getParentFile();image.setImageBitmap(android.graphics.BitmapFactory.decodeFile(new File(root,"test-media/marker-editor-028/"+file).getPath()));image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);frame.addView(image,1,new android.widget.FrameLayout.LayoutParams(-1,-1));
        android.widget.LinearLayout top=(android.widget.LinearLayout)frame.getChildAt(2);((android.widget.TextView)top.getChildAt(0)).setText(title);frame.getChildAt(3).setVisibility(View.GONE);
    }
    private static Object get(Object o,String key)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String key,Object value)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);f.set(o,value);}
    private static void await(Object a)throws Exception{((ExecutorService)get(a,"io")).submit(()->{}).get(8,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void layout(SplitPlayerActivity a){View v=a.getWindow().getDecorView();float d=a.getResources().getDisplayMetrics().density;v.measure(View.MeasureSpec.makeMeasureSpec((int)(412*d),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((int)(915*d),View.MeasureSpec.EXACTLY));v.layout(0,0,v.getMeasuredWidth(),v.getMeasuredHeight());}
    private static void capture(SplitPlayerActivity a,String name)throws Exception{View v=a.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));File output=new File("build/reports/view-fixtures/player033");assertTrue(output.isDirectory()||output.mkdirs());try(FileOutputStream f=new FileOutputStream(new File(output,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();}
}
