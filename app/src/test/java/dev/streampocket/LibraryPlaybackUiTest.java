package dev.streampocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Duration;
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

/** Dispatches real DOWN/UP through the native catalogue, not AdapterView.performItemClick.
 * Reference stills prove layout only; this is not device decoding or playback evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LibraryPlaybackUiTest {
    private Context app;
    private ActivityController<Activity> controller;
    private Activity activity;
    private VideoLibraryView library;
    private File media;
    private long videoId;

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("config",0).edit().clear().putString("appearance_theme","light").putBoolean("motion_enabled",false).commit();
        controller=Robolectric.buildActivity(Activity.class);activity=controller.get();UiTheme.apply(activity);controller.create().start().resume().visible();
        media=new File(app.getCacheDir(),"library-playback-reference.mp4");
        Files.copy(new File(project(),"test-media/bbb-licensed-20s.mp4").toPath(),media.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JSONObject task=new JSONObject().put("id","playback-touch").put("title","界面样例 · 大雄兔与森林，点击画面或标题立即播放")
                .put("status","completed").put("uri",Uri.fromFile(media).toString()).put("mime","video/mp4").put("size",media.length()).put("url","https://example.org/reference")
                .put("media",new JSONObject().put("duration",20).put("width",320).put("height",180));
        app.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(task).toString()).commit();
        try(LibraryStore store=new LibraryStore(app)){
            videoId=store.upsertCompletedDownload(task);store.savePlayback(videoId,5000);
            JSONObject video=store.video(videoId);File cover=new File(app.getCacheDir(),"video-covers/"+video.optString("thumbnailKey")+".jpg");cover.getParentFile().mkdirs();
            Bitmap image=BitmapFactory.decodeFile(new File(project(),"test-media/bbb-frame-5s.png").getPath());assertNotNull(image);
            try(FileOutputStream out=new FileOutputStream(cover)){assertTrue(image.compress(Bitmap.CompressFormat.JPEG,90,out));}image.recycle();
        }
    }
    @After public void teardown() throws Exception {
        if(library!=null){ExecutorService io=field(library,"io",ExecutorService.class);library.release();assertTrue(io.awaitTermination(5,TimeUnit.SECONDS));}
        controller.pause().stop().destroy();if(media!=null)media.delete();app.deleteDatabase("media-library.db");
    }

    @Test public void thumbnailAndTitleTouchesOpenPlayerInBothLayouts() throws Exception {
        open();GridView grid=field(library,"grid",GridView.class);
        for(boolean list:new boolean[]{false,true}){
            if(list){described(library,"切换详细列表").performClick();layout();}
            View cell=grid.getChildAt(0);ImageView menu=field(cell.getTag(),"menu",ImageView.class);
            assertTrue("More remains a separate keyboard-focusable action",menu.isFocusable());
            tap(field(cell.getTag(),"cover",ImageView.class));assertPlaybackIntent();
            tap(field(cell.getTag(),"title",TextView.class));assertPlaybackIntent();
            capture(list?"02-list-native-view-reference-still":"01-grid-native-view-reference-still");
        }
    }

    @Test public void overflowAndLongPressSelectionDoNotAccidentallyPlay() throws Exception {
        open();GridView grid=field(library,"grid",GridView.class);
        for(boolean list:new boolean[]{false,true}){
            if(list){described(library,"切换详细列表").performClick();layout();}
            View cell=grid.getChildAt(0);tap(field(cell.getTag(),"menu",ImageView.class));
            AlertDialog menu=(AlertDialog)ShadowDialog.getLatestDialog();assertNotNull(menu);assertTrue(menu.isShowing());assertNull(Shadows.shadowOf(activity).getNextStartedActivity());menu.dismiss();
            View cover=field(cell.getTag(),"cover",ImageView.class);Rect rect=bounds(library,cover);long time=SystemClock.uptimeMillis();
            dispatch(MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY(),time);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout()+100));
            dispatch(MotionEvent.ACTION_UP,rect.centerX(),rect.centerY(),time);Shadows.shadowOf(Looper.getMainLooper()).idle();layout();
            assertTrue(field(library,"selectionMode",Boolean.class));assertTrue(field(library,"shareSelection",TextView.class).isEnabled());assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            tap(field(grid.getChildAt(0).getTag(),"cover",ImageView.class));layout();assertFalse(field(library,"shareSelection",TextView.class).isEnabled());assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            field(library,"selectionBar",android.widget.LinearLayout.class).getChildAt(0).performClick();layout();assertFalse(field(library,"selectionMode",Boolean.class));
        }
    }

    @Test public void cancelledTouchDoesNotPlayOrSelect() throws Exception {
        open();View cell=field(library,"grid",GridView.class).getChildAt(0);Rect rect=bounds(library,field(cell.getTag(),"cover",ImageView.class));long time=SystemClock.uptimeMillis();
        dispatch(MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY(),time);dispatch(MotionEvent.ACTION_CANCEL,rect.centerX(),rect.centerY(),time);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertFalse(field(library,"selectionMode",Boolean.class));
    }

    @Test public void restoredFileIsNotBlockedByAStaleUnavailableCatalogueSnapshot() throws Exception {
        assertTrue(media.delete());open();assertTrue(field(library,"videos",java.util.List.class).get(0) instanceof JSONObject);
        JSONObject snapshot=(JSONObject)field(library,"videos",java.util.List.class).get(0);assertTrue(snapshot.optBoolean("missing"));
        Files.copy(new File(project(),"test-media/bbb-licensed-20s.mp4").toPath(),media.toPath());
        tap(field(field(library,"grid",GridView.class).getChildAt(0).getTag(),"cover",ImageView.class));assertPlaybackIntent();
        ActivityController<PlayerActivity> player=Robolectric.buildActivity(PlayerActivity.class,new Intent(app,PlayerActivity.class).putExtra("video_id",videoId));
        try{PlayerActivity view=player.create().get();awaitOwner(view);assertFalse(field(view,"missing",Boolean.class));assertEquals(Uri.fromFile(media),field(view,"mediaUri",Uri.class));assertEquals(5000L,field(view,"positionMs",Long.class).longValue());}
        finally{player.destroy();}
    }

    @Test public void missingFileUsesTheSameRetryablePlayerErrorAndPreservesMarkers() throws Exception {
        try(LibraryStore store=new LibraryStore(app)){store.addMarker(videoId,1000,"保留的标记");}
        open();assertTrue(media.delete());tap(field(field(library,"grid",GridView.class).getChildAt(0).getTag(),"title",TextView.class));assertPlaybackIntent();
        ActivityController<PlayerActivity> player=Robolectric.buildActivity(PlayerActivity.class,new Intent(app,PlayerActivity.class).putExtra("video_id",videoId));
        try{PlayerActivity view=player.create().get();awaitOwner(view);assertNull(field(view,"player",Object.class));assertTrue(field(view,"status",TextView.class).getText().toString().contains("已有精彩标记仍保留"));assertEquals(View.VISIBLE,field(view,"retryButton",View.class).getVisibility());}
        finally{player.destroy();}
        try(LibraryStore store=new LibraryStore(app)){assertEquals(1,store.markers(videoId).size());assertNotNull(store.video(videoId));}
    }

    @Test public void completedDownloadBodyAndThumbnailUseTheSameStablePlaybackIdentity() throws Exception {
        Bundle state=new Bundle();state.putString("tab","queue");ActivityController<MainActivity> main=Robolectric.buildActivity(MainActivity.class);
        try{
            MainActivity view=main.create(state).start().resume().visible().get();ViewGroup root=field(view,"root",android.widget.LinearLayout.class);layoutRoot(root,dp(412),dp(915));
            String title=TaskStore.get(app,"playback-touch").optString("title");View row=described(root,title+"，点按播放，更多查看任务详情");assertNotNull(row);
            tapRoot(root,findText(row,title));awaitOwner(view);
            Intent body=Shadows.shadowOf(view).getNextStartedActivity();assertNotNull(body);assertEquals(PlayerActivity.class.getName(),body.getComponent().getClassName());assertEquals(videoId,body.getLongExtra("video_id",0));
            View play=described(root,"播放："+title);assertNotNull(play);tapRoot(root,play);awaitOwner(view);
            Intent cover=Shadows.shadowOf(view).getNextStartedActivity();assertNotNull(cover);assertEquals(body.getComponent(),cover.getComponent());assertEquals(videoId,cover.getLongExtra("video_id",0));
        }finally{main.pause().stop().destroy();}
    }

    @Test public void inaccessibleContentUriUsesPlayerPermissionFeedbackWithoutChangingIdentity() throws Exception {
        String authority="dev.streampocket.playback.denied";android.content.ContentProvider provider=new DeniedProvider();android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority=authority;
        provider.attachInfo(app,info);org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority,provider);
        Uri uri=Uri.parse("content://"+authority+"/video/1");
        try(LibraryStore store=new LibraryStore(app)){store.removeVideoRecord(videoId);videoId=store.addExportedVideo("界面样例 · 访问已撤销",uri.toString(),"video/mp4",1024,20000);store.addMarker(videoId,2000,"权限失效后仍保留");}
        open();GridView grid=field(library,"grid",GridView.class);assertEquals(1,grid.getAdapter().getCount());
        tap(field(grid.getChildAt(0).getTag(),"title",TextView.class));assertPlaybackIntent();
        ActivityController<PlayerActivity> player=Robolectric.buildActivity(PlayerActivity.class,new Intent(app,PlayerActivity.class).putExtra("video_id",videoId));
        try{PlayerActivity view=player.create().get();awaitOwner(view);assertEquals(uri,field(view,"mediaUri",Uri.class));assertTrue(field(view,"missing",Boolean.class));assertNull(field(view,"player",Object.class));assertTrue(field(view,"status",TextView.class).getText().toString().contains("撤销访问"));assertEquals(View.VISIBLE,field(view,"retryButton",View.class).getVisibility());}
        finally{player.destroy();}
        try(LibraryStore store=new LibraryStore(app)){assertEquals(1,store.markers(videoId).size());}
    }

    public static final class DeniedProvider extends android.content.ContentProvider {
        @Override public boolean onCreate(){return true;}
        @Override public android.os.ParcelFileDescriptor openFile(Uri uri,String mode){throw new SecurityException("Explicit revoked-access test fixture");}
        @Override public String getType(Uri uri){return "video/mp4";}
        @Override public android.database.Cursor query(Uri uri,String[] p,String s,String[] a,String o){return null;}
        @Override public Uri insert(Uri uri,android.content.ContentValues values){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,android.content.ContentValues values,String s,String[] a){return 0;}
        @Override public int delete(Uri uri,String s,String[] a){return 0;}
    }

    private static void awaitOwner(Object owner)throws Exception{ExecutorService io=field(owner,"io",ExecutorService.class);for(int i=0;i<3;i++){io.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private static View findText(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=findText(((ViewGroup)root).getChildAt(i),text);if(v!=null)return v;}return null;}
    private static Rect bounds(ViewGroup root,View target){Rect rect=new Rect();target.getDrawingRect(rect);root.offsetDescendantRectToMyCoords(target,rect);return rect;}
    private static void tapRoot(ViewGroup root,View target){Rect rect=bounds(root,target);long time=SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,rect.centerX(),rect.centerY(),0);root.dispatchTouchEvent(e);e.recycle();}Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));}
    private static void layoutRoot(View root,int width,int height){root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);Shadows.shadowOf(Looper.getMainLooper()).idle();}

    private void assertPlaybackIntent(){Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull("A normal cover/title touch must launch playback",intent);assertEquals(PlayerActivity.class.getName(),intent.getComponent().getClassName());assertEquals(activity.getPackageName(),intent.getComponent().getPackageName());assertEquals(videoId,intent.getLongExtra("video_id",0));assertNull(Shadows.shadowOf(activity).getNextStartedActivity());}
    private void open() throws Exception {library=new VideoLibraryView(activity);activity.setContentView(library);library.refresh();await();}
    private void await() throws Exception {ExecutorService io=field(library,"io",ExecutorService.class);for(int i=0;i<8;i++){io.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();if(!field(library,"loading",Boolean.class))break;}layout();}
    private void layout(){int w=dp(412),h=dp(780);library.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));library.layout(0,0,w,h);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void tap(View target){Rect bounds=new Rect();target.getDrawingRect(bounds);library.offsetDescendantRectToMyCoords(target,bounds);long time=SystemClock.uptimeMillis();dispatch(MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),time);dispatch(MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),time);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));}
    private void dispatch(int action,float x,float y,long time){MotionEvent e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0);library.dispatchTouchEvent(e);e.recycle();}
    private void capture(String name) throws Exception {layout();field(field(library,"covers",ThumbnailRepository.class),"worker",java.util.concurrent.ThreadPoolExecutor.class).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();File dir=new File(project(),"app/build/reports/view-fixtures/030-library");dir.mkdirs();Bitmap b=Bitmap.createBitmap(library.getWidth(),library.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);library.draw(canvas);android.graphics.Paint provenance=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);provenance.setColor(UiTheme.get(activity).muted);provenance.setTextSize(dp(11));provenance.setTextAlign(android.graphics.Paint.Align.CENTER);canvas.drawText("原生 View 布局 · 静态参考帧 · 非真机",b.getWidth()/2f,b.getHeight()-dp(16),provenance);try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}b.recycle();}
    private static File project(){File base=new File(System.getProperty("user.dir"));while(base!=null&&!new File(base,"settings.gradle").isFile())base=base.getParentFile();return base;}
    private static View described(View root,String value){if(value.equals(String.valueOf(root.getContentDescription())))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=described(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static <T>T field(Object target,String name,Class<T> type)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(target);}
}
