package dev.streampocket;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
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

/** Current production native Views with explicitly injected licensed reference stills.
 * Not a running Android phone, not a MediaCodec / export acceptance result.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class MarkerEditor028RenderTest {
    @Test public void scrollingFortyMarkersCancelsOffscreenWorkInsteadOfPermanentlyRejectingCurrentRows()throws Exception{
        Player022UiTest fixture=new Player022UiTest();fixture.setup();Object session=call(fixture,"open",new Class<?>[0]);PlayerActivity a=get(session,"a",PlayerActivity.class);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);java.util.List<Long> decoded=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try{
            long video=get(a,"videoId",Long.class);try(LibraryStore store=new LibraryStore(a)){for(org.json.JSONObject marker:store.markers(video))store.deleteMarker(marker.optLong("id"));for(int i=0;i<40;i++)store.addMarker(video,i*2000,"第 "+i+" 个标记");set(a,"markers",store.markers(video));}
            get(a,"markerThumbnails",TimestampThumbnails.class).release();TimestampThumbnails frames=new TimestampThumbnails(a,(context,uri,at)->{decoded.add(at);entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Bitmap.createBitmap(16,9,Bitmap.Config.ARGB_8888);});set(a,"markerThumbnails",frames);
            get(a,"markerListButton",TextView.class).performClick();layout(a.getWindow().getDecorView(),412,915);idle();call(a,"bindVisibleMarkerFrames",new Class<?>[0]);assertTrue(entered.await(5,TimeUnit.SECONDS));
            android.widget.ScrollView scroll=get(a,"details",android.widget.ScrollView.class);scroll.scrollTo(0,100000);layout(a.getWindow().getDecorView(),412,915);idle();call(a,"bindVisibleMarkerFrames",new Class<?>[0]);
            ThreadPoolExecutor worker=get(frames,"worker",ThreadPoolExecutor.class);assertTrue("Queue tracks the viewport, not every passed row",worker.getQueue().size()<12);
            release.countDown();drain(frames);assertTrue(decoded.stream().anyMatch(at->at>=70000));assertTrue(decoded.size()<16);
        }finally{release.countDown();call(session,"close",new Class<?>[0]);}
    }
    @Test public void portraitMarkerSheetAndLandscapeDrawerUseRealTimeBoundReferenceFrames()throws Exception{
        Player022UiTest fixture=new Player022UiTest();fixture.setup();Object session=call(fixture,"open",new Class<?>[0]);
        PlayerActivity a=get(session,"a",PlayerActivity.class);
        try{
            long video=get(a,"videoId",Long.class);try(LibraryStore store=new LibraryStore(a)){
                for(org.json.JSONObject marker:store.markers(video))store.deleteMarker(marker.optLong("id"));
                store.addMarker(video,5000,"清晨的草地");store.addRangeMarker(video,12500,17200,"溪流、花丛与小鸟");store.addMarker(video,17200,"停在这一声鸟鸣");
                set(a,"markers",store.markers(video));
            }
            get(a,"markerThumbnails",TimestampThumbnails.class).release();TimestampThumbnails frames=new TimestampThumbnails(a,(app,uri,at)->reference(at));set(a,"markerThumbnails",frames);
            get(a,"title",TextView.class).setText("Big Buck Bunny · 参考视频");set(a,"durationMs",20000L);set(a,"mediaUri",Uri.fromFile(new File(project(),"test-media/bbb-licensed-20s.mp4")));
            FrameLayout root=get(a,"root",FrameLayout.class);for(int i=root.getChildCount()-1;i>=0;i--){View v=root.getChildAt(i);if(v instanceof TextView&&((TextView)v).getText().toString().startsWith("界面时钟夹具"))root.removeView(v);}
            PlayerView player=get(a,"playerView",PlayerView.class);referenceSurface(player,12500);TextView note=note(a,"原生 View 布局 · 参考视频静态帧 · 非真机");FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,48,Gravity.TOP);np.topMargin=140;root.addView(note,np);
            get(a,"markerListButton",TextView.class).performClick();layout(a.getWindow().getDecorView(),412,915);idle();call(a,"bindVisibleMarkerFrames",new Class<?>[0]);drain(frames);layout(a.getWindow().getDecorView(),412,915);idle();drain(frames);
            assertEquals("markers",get(a,"sheetMode",String.class));assertEquals("标记 3",get(a,"markerListButton",TextView.class).getText().toString());
            assertEquals(3,get(a,"markerList",ViewGroup.class).getChildCount()/2);
            assertTrue("Visible rows have timestamp-bound reference thumbnails",countImages(get(a,"markerList",View.class))>=3);
            capture(a.getWindow().getDecorView(),412,915,"01-marker-sheet-portrait-native-view-reference-stills");
            RuntimeEnvironment.setQualifiers("w915dp-h412dp-land-xhdpi");Configuration config=new Configuration(a.getResources().getConfiguration());config.orientation=Configuration.ORIENTATION_LANDSCAPE;a.onConfigurationChanged(config);layout(a.getWindow().getDecorView(),915,412);idle();call(a,"bindVisibleMarkerFrames",new Class<?>[0]);drain(frames);
            FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)get(a,"sheetBody",View.class).getLayoutParams();assertTrue((params.gravity&Gravity.RIGHT)==Gravity.RIGHT);assertTrue(params.width<=dp(a,360));
            capture(a.getWindow().getDecorView(),915,412,"02-marker-drawer-landscape-native-view-reference-stills");
        }finally{call(session,"close",new Class<?>[0]);RuntimeEnvironment.setQualifiers("w412dp-h915dp-xhdpi");}
    }
    @Test public void editorPinsFinalActionsRendersFilmstripAndProvidesReviewBeforeExport()throws Exception{
        Context app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");app.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).putString("appearance_theme","light").commit();
        File media=new File(project(),"test-media/bbb-licensed-20s.mp4");assertTrue(media.isFile());
        long video;try(LibraryStore store=new LibraryStore(app)){video=store.addExportedVideo("Big Buck Bunny · 在画面里选择起止点",Uri.fromFile(media).toString(),"video/mp4",media.length(),20000);}
        ActivityController<EditorActivity> c=Robolectric.buildActivity(EditorActivity.class,new Intent(app,EditorActivity.class).putExtra("video_id",video).putExtra("start_ms",5000L).putExtra("end_ms",17200L)).create().start();EditorActivity a=c.get();
        try{
            await(a);get(a,"frames",TimestampThumbnails.class).release();TimestampThumbnails frames=new TimestampThumbnails(a,(context,uri,at)->reference(at));set(a,"frames",frames);
            set(a,"resumed",true);set(a,"firstPreviewFrame",true);ClipRangeView range=get(a,"rangeView",ClipRangeView.class);range.zoomToSelection();drain(frames);referenceSurface(get(a,"playerView",PlayerView.class),12500);
            View decor=a.getWindow().getDecorView();layout(decor,412,915);idle();
            Rect bounds=new Rect();View action=get(a,"trimExportButton",View.class);assertTrue(action.getGlobalVisibleRect(bounds));assertEquals(action.getHeight(),bounds.height());assertTrue(bounds.bottom<=decor.getHeight());
            capture(decor,412,915,"03-editor-range-native-view-reference-stills");
            get(a,"trimExportButton",Button.class).performClick();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();assertNotNull(dialog);
            View custom=dialog.findViewById(android.R.id.custom);if(custom instanceof ViewGroup){ViewGroup group=(ViewGroup)custom;for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i) instanceof android.widget.LinearLayout){((android.widget.LinearLayout)group.getChildAt(i)).addView(note(app,"原生 View 布局 · 非真机"));break;}}
            capture(dialog.getWindow().getDecorView(),360,312,"04-export-review-native-view-reference-stills");dialog.dismiss();
            RuntimeEnvironment.setQualifiers("w320dp-h640dp-xhdpi");layout(decor,320,640);idle();assertTrue(action.getGlobalVisibleRect(bounds));assertEquals(action.getHeight(),bounds.height());
            capture(decor,320,640,"05-editor-compact-native-view-reference-stills");
        }finally{ExecutorService io=get(a,"io",ExecutorService.class);c.stop().destroy();assertTrue(io.awaitTermination(5,TimeUnit.SECONDS));app.deleteDatabase("media-library.db");RuntimeEnvironment.setQualifiers("w412dp-h915dp-xhdpi");}
    }
    private static Bitmap reference(long at)throws Exception{File file=new File(project(),"test-media/marker-editor-028/ref20-"+at+".png");if(!file.isFile())throw new java.io.IOException("No declared reference frame at "+at);Bitmap bitmap=BitmapFactory.decodeFile(file.getPath());if(bitmap==null)throw new java.io.IOException("Unreadable reference still");return bitmap;}
    private static void referenceSurface(PlayerView view,long at)throws Exception{AspectRatioFrameLayout content=view.findViewById(androidx.media3.ui.R.id.exo_content_frame);content.setAspectRatio(16f/9f);ImageView still=new ImageView(view.getContext());still.setScaleType(ImageView.ScaleType.FIT_CENTER);still.setImageBitmap(reference(at));still.setContentDescription("许可参考视频的静态帧，非 Android 解码");content.addView(still,new FrameLayout.LayoutParams(-1,-1));TextView note=note(view.getContext(),"布局参考 · 静态视频帧 · 非真机");content.addView(note,new FrameLayout.LayoutParams(-1,dp(view.getContext(),28),Gravity.BOTTOM));}
    private static TextView note(Context app,String text){TextView note=new TextView(app);note.setText(text);note.setTextColor(0xFFCFD7D5);note.setTextSize(11);note.setGravity(Gravity.CENTER);note.setBackgroundColor(0x990A0C0F);return note;}
    private static int countImages(View v){if(v instanceof ImageView)return ((ImageView)v).getDrawable()!=null?1:0;int count=0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)count+=countImages(((ViewGroup)v).getChildAt(i));return count;}
    private static void await(EditorActivity a)throws Exception{for(int i=0;i<3;i++){get(a,"io",ExecutorService.class).submit(()->{}).get(5,TimeUnit.SECONDS);idle();}}
    private static void drain(TimestampThumbnails frames)throws Exception{get(frames,"worker",ThreadPoolExecutor.class).submit(()->{}).get(5,TimeUnit.SECONDS);idle();}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static int dp(Context app,int size){return Math.round(size*app.getResources().getDisplayMetrics().density);}
    private static void layout(View view,int width,int height){float d=view.getResources().getDisplayMetrics().density;view.measure(View.MeasureSpec.makeMeasureSpec(Math.round(width*d),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.round(height*d),View.MeasureSpec.EXACTLY));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static void capture(View view,int width,int height,String name)throws Exception{layout(view,width,height);Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File directory=new File(project(),"app/build/reports/view-fixtures/028");assertTrue(directory.isDirectory()||directory.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();}
    private static File project(){File root=new File(System.getProperty("user.dir"));while(root!=null&&!new File(root,"settings.gradle").isFile())root=root.getParentFile();return root;}
    private static <T>T get(Object owner,String name,Class<T> type)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(owner));}
    private static void set(Object owner,String name,Object value)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
    private static Object call(Object owner,String name,Class<?>[] types,Object...args)throws Exception{Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);}
}
