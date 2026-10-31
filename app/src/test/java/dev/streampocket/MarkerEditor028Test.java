package dev.streampocket;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;
import static org.junit.Assert.*;

/** Native View / SQLite / Media3 configuration fixtures. No device codec is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class MarkerEditor028Test {
    Context app;LibraryStore store;File media;long video,project;
    ActivityController<EditorActivity> controller;
    TimestampThumbnails thumbnails;
    @Before public void setup()throws Exception{
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");store=new LibraryStore(app);
        media=File.createTempFile("timestamp-fixture-",".mp4",app.getCacheDir());try(FileOutputStream out=new FileOutputStream(media)){out.write(new byte[]{1,2,3,4});}
        video=store.addExportedVideo("明确媒体配置夹具",Uri.fromFile(media).toString(),"video/mp4",4,100_800);project=store.createProject("范围草稿");
        app.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).commit();
    }
    @After public void cleanup()throws Exception{
        if(controller!=null){ExecutorService io=field(controller.get(),"io",ExecutorService.class);controller.stop().destroy();assertTrue(io.awaitTermination(5,TimeUnit.SECONDS));}
        if(thumbnails!=null){thumbnails.release();assertTrue(field(thumbnails,"worker",ThreadPoolExecutor.class).awaitTermination(5,TimeUnit.SECONDS));}
        store.close();media.delete();app.deleteDatabase("media-library.db");
    }
    @Test public void realRangePreviewUsesClippedItemAndConvertsRelativeTimeBackToOriginal()throws Exception{
        EditorActivity a=editor();List<MediaItem> items=new ArrayList<>();long[] position={0};boolean[] playing={false};
        ExoPlayer player=(ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class<?>[]{ExoPlayer.class},(proxy,method,args)->{
            switch(method.getName()){
                case "setMediaItem":items.add((MediaItem)args[0]);position[0]=args.length>1?(Long)args[1]:0;return null;
                case "getCurrentPosition":return position[0];case "play":playing[0]=true;return null;case "pause":playing[0]=false;return null;
                case "prepare":case "release":return null;case "seekTo":position[0]=(Long)args[args.length-1];return null;
                case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==args[0];case "toString":return "Configuration-only player fixture";
                default:throw new AssertionError("Unexpected player call "+method.getName());
            }
        });
        set(a,"player",player);call(a,"previewRange");assertTrue(playing[0]);assertEquals(1,items.size());
        MediaItem.ClippingConfiguration clip=items.get(0).clippingConfiguration;
        assertEquals(12_789,clip.startPositionMs);assertEquals(24_567,clip.endPositionMs);assertFalse(clip.startsAtKeyFrame);
        assertEquals("Preview must not rewrite source duration",100_800,field(a,"durationMs",Long.class).longValue());
        position[0]=2000;assertEquals(14_789,(long)call(a,"currentSourcePosition"));
        field(a,"sourceModeButton",Button.class).performClick();assertFalse(field(a,"selectionPreview",Boolean.class));
        assertEquals(0,items.get(1).clippingConfiguration.startPositionMs);assertEquals(12_789,position[0]);
    }
    @Test public void confirmedSingleClipExportIsNotStartedByOpeningTheReviewAndCancelKeepsDraftEmpty()throws Exception{
        EditorActivity a=editor();field(a,"trimExportButton",Button.class).performClick();
        AlertDialog review=(AlertDialog)ShadowDialog.getLatestDialog();assertNotNull(review);assertEquals("开始导出",review.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue(store.clips(project).isEmpty());assertFalse(ExportService.isRunning(project));
        review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(store.clips(project).isEmpty());
    }
    @Test public void exactInputsOutsideZoomedWindowRevealTheSelectedEndpointsAgain()throws Exception{
        EditorActivity a=editor();ClipRangeView range=field(a,"rangeView",ClipRangeView.class);range.zoomToSelection();assertTrue(range.getWindowEndMs()<100800);
        field(a,"startInput",EditText.class).setText("75");field(a,"endInput",EditText.class).setText("85");
        assertEquals(75000,range.getStartMs());assertEquals(85000,range.getEndMs());assertEquals(0,range.getWindowStartMs());assertEquals(100800,range.getWindowEndMs());
    }
    @Test public void millisecondEditsPreserveStableMarkerAndClipIdsAndNeverModifySource()throws Exception{
        long marker=store.addRangeMarker(video,12_789,24_567,"原标记");store.editMarker(video,marker,14_125,32_875,"更新标记");
        assertEquals(marker,store.markers(video).get(0).optLong("id"));assertEquals(14_125,store.markers(video).get(0).optLong("startMs"));
        assertEquals(32_875,store.markers(video).get(0).optLong("endMs"));assertEquals("更新标记",store.markers(video).get(0).optString("label"));
        long clip=store.addClip(project,video,1000,5000);store.updateClipRange(project,clip,Uri.fromFile(media).toString(),14_125,32_875,100_800);
        assertEquals(1,store.clips(project).size());assertEquals(clip,store.clips(project).get(0).optLong("id"));assertEquals(18_750,store.clips(project).get(0).optLong("durationMs"));
        try{store.editMarker(video+1,marker,0,1000,"wrong owner");fail();}catch(IllegalArgumentException expected){}
        assertEquals(4,media.length());assertEquals(100_800,store.video(video).optLong("durationMs"));
    }
    @Test public void encoderRequiresBothTracksEvenWhenSingleH264ClipNeedsNoScalingAndNamesAreSafe(){
        ExportService.PreciseEncoderFactory factory=new ExportService.PreciseEncoderFactory(app);assertTrue(factory.videoNeedsEncoding());assertTrue(factory.audioNeedsEncoding());
        String name=ExportService.outputFileName("a/b\\c:成片\n.mp4",1234);assertTrue(name.endsWith("-1234.mp4"));assertFalse(name.contains("/"));assertFalse(name.contains("\\"));assertFalse(name.contains("\n"));assertFalse(name.contains(".mp4-"));
        assertEquals("拾流剪辑-1.mp4",ExportService.outputFileName("..",1));
    }
    @Test public void clipRemainsEditableBySnapshotUriAfterOnlyItsLibraryRecordWasRemoved()throws Exception{
        long clip=store.addClip(project,video,1000,5000);store.removeVideoRecords(java.util.Collections.singletonList(video));
        assertNull(store.video(video));assertEquals(0,store.clips(project).get(0).optLong("videoId"));assertEquals(4,media.length());
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(app,Uri.fromFile(media)),android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,"100800");
        android.os.Bundle state=new android.os.Bundle();state.putInt("screen",1);state.putLong("project_id",project);state.putLong("video_id",0);state.putLong("editing_clip_id",clip);state.putLong("start_ms",1000);state.putLong("end_ms",5000);
        controller=Robolectric.buildActivity(EditorActivity.class,new Intent(app,EditorActivity.class)).create(state).start();EditorActivity a=controller.get();drainEditor(a);
        assertTrue(field(a,"sourceLoaded",Boolean.class));assertEquals("保存片段范围",field(a,"trimExportButton",Button.class).getText().toString());
        field(a,"startInput",EditText.class).setText("12.789");field(a,"endInput",EditText.class).setText("24.567");field(a,"trimExportButton",Button.class).performClick();drainEditor(a);
        assertEquals(1,store.clips(project).size());assertEquals(clip,store.clips(project).get(0).optLong("id"));assertEquals(12789,store.clips(project).get(0).optLong("startMs"));assertEquals(24567,store.clips(project).get(0).optLong("endMs"));assertEquals(4,media.length());
    }
    @Test public void backgroundedTrimCancelsQueuedFramesAndLateCallbackCannotStartNewOptionalWork()throws Exception{
        EditorActivity a=editor();List<Long> decoded=new ArrayList<>();TimestampThumbnails previous=field(a,"frames",TimestampThumbnails.class);previous.release();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        thumbnails=new TimestampThumbnails(app,(context,uri,at)->{synchronized(decoded){decoded.add(at);}entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Bitmap.createBitmap(16,9,Bitmap.Config.ARGB_8888);});
        set(a,"frames",thumbnails);set(a,"resumed",true);set(a,"firstPreviewFrame",true);call(a,"loadRangeFrames");assertTrue(entered.await(5,TimeUnit.SECONDS));
        a.onPause();release.countDown();drainFrames();assertEquals("Only the already-running native call may finish",1,decoded.size());
        set(a,"firstPreviewFrame",true);call(a,"loadRangeFrames");drainFrames();assertEquals("A late source/renderer callback in background must not enqueue seven more frames",1,decoded.size());
    }
    @Test public void exactTimeThumbnailCacheKeysFileRevisionAndTimeAndRunsDecoderOffMain()throws Exception{
        List<Long> extracted=new ArrayList<>();thumbnails=new TimestampThumbnails(app,(context,uri,at)->{
            assertNotEquals(Looper.getMainLooper(),Looper.myLooper());synchronized(extracted){extracted.add(at);}Bitmap bitmap=Bitmap.createBitmap(16,9,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xFF397D72);return bitmap;
        });List<Bitmap> results=new ArrayList<>();Uri uri=Uri.fromFile(media);
        thumbnails.load(uri,12_789,100_800,results::add);drainFrames();thumbnails.load(uri,24_567,100_800,results::add);drainFrames();
        thumbnails.load(uri,12_789,100_800,results::add);drainFrames();assertEquals(java.util.Arrays.asList(12_789L,24_567L),extracted);assertEquals(3,results.size());
        assertTrue(media.setLastModified(media.lastModified()+5000));thumbnails.load(uri,12_789,100_800,results::add);drainFrames();assertEquals(3,extracted.size());
    }
    @Test public void clearDuringUninterruptibleExtractionRejectsLateViewCallback()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Bitmap> results=new ArrayList<>();
        thumbnails=new TimestampThumbnails(app,(context,uri,at)->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Bitmap.createBitmap(16,9,Bitmap.Config.ARGB_8888);});
        thumbnails.load(Uri.fromFile(media),5_789,100_800,results::add);assertTrue(entered.await(5,TimeUnit.SECONDS));thumbnails.clear();release.countDown();drainFrames();assertTrue(results.isEmpty());
    }
    @Test public void zoomKeepsOriginalMillisecondsAndCancelledDragRestoresBothEndpoints(){
        ClipRangeView range=new ClipRangeView(app);range.measure(exact(720),exact(192));range.layout(0,0,720,192);range.setRange(7_200_000,12_789,24_567);range.zoomToSelection();
        assertTrue(range.getWindowEndMs()-range.getWindowStartMs()<30_000);assertEquals(12_789,range.getStartMs());assertEquals(24_567,range.getEndMs());
        long start=range.getStartMs(),end=range.getEndMs();touch(range,MotionEvent.ACTION_DOWN,150,80);touch(range,MotionEvent.ACTION_MOVE,300,80);touch(range,MotionEvent.ACTION_CANCEL,300,80);
        assertEquals(start,range.getStartMs());assertEquals(end,range.getEndMs());range.showFullTimeline();assertEquals(7_200_000,range.getWindowEndMs());
    }
    private EditorActivity editor()throws Exception{controller=Robolectric.buildActivity(EditorActivity.class,new Intent(app,EditorActivity.class).putExtra("video_id",video).putExtra("project_id",project).putExtra("start_ms",12_789L).putExtra("end_ms",24_567L)).create().start();EditorActivity a=controller.get();drainEditor(a);return a;}
    private static void drainEditor(EditorActivity a)throws Exception{for(int i=0;i<3;i++){field(a,"io",ExecutorService.class).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private void drainFrames()throws Exception{field(thumbnails,"worker",ThreadPoolExecutor.class).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static int exact(int value){return View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY);}
    private static void touch(View view,int action,float x,float y){MotionEvent event=MotionEvent.obtain(0,1,action,x,y,0);view.onTouchEvent(event);event.recycle();}
    static <T>T field(Object owner,String name,Class<T> type)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(owner));}
    static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    static Object call(Object owner,String name)throws Exception{Method method=owner.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(owner);}
}
