package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
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
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Native View replay. The attached interface clock denies surface commands and cannot decode. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class VideoFirstPlayerUiTest {
    private static final String LONG_TITLE="当山脊上的风遇见整片日落与云海：这一百秒的完整视频值得慢慢看完，也值得留下一段属于自己的精彩标记";
    private static final File OUTPUT=new File(outputDirectory());
    private Context app;
    private long videoId;
    private File source;

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication(); app.deleteDatabase("media-library.db");
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        source=new File(app.getCacheDir(),"video-first-view-clock.bin");
        try (FileOutputStream out=new FileOutputStream(source)) { out.write("NATIVE UI CLOCK FIXTURE; NOT A VIDEO".getBytes(StandardCharsets.UTF_8)); }
        JSONObject task=new JSONObject().put("id","video-first-fixture").put("status","completed").put("title",LONG_TITLE)
                .put("uri",Uri.fromFile(source).toString()).put("url","https://x.com/fixture/status/1")
                .put("mime","video/mp4").put("size",source.length()).put("media",new JSONObject().put("duration",100));
        try (LibraryStore store=new LibraryStore(app)) {
            videoId=store.upsertCompletedDownload(task);
            store.addMarker(videoId,20250,"第一阵风吹过山脊，我们都慢下了脚步");
            store.addRangeMarker(videoId,80000,95000,"留给夕阳的一段完整时间：远处的云海终于透出柔和的光");
        }
    }

    @Test public void portraitCanvasFillsUsableScreenControlsAutoHideAndToolsOverlayWithoutResizingVideo() throws Exception {
        try (Session session=openClockSession()) {
            PlayerActivity activity=session.activity; FrameLayout root=field(activity,"root",FrameLayout.class);
            PlayerView surface=field(activity,"playerView",PlayerView.class);
            capture(activity,"01-portrait-controls-clock-fixture"); assertFullCanvas(root,surface);
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT,surface.getResizeMode());
            TextView title=surface.findViewById(R.id.sp_player_title); assertEquals(1,title.getMaxLines()); assertNotNull(title.getEllipsize());
            assertEquals(LONG_TITLE,title.getText().toString());
            assertEquals(View.VISIBLE,controller(surface).getVisibility());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000));
            assertNotEquals("No permanent toolbar over video",View.VISIBLE,controller(surface).getVisibility());
            capture(activity,"02-portrait-video-only-clock-fixture"); assertFullCanvas(root,surface);
            surface.performClick(); idle(); assertEquals(View.VISIBLE,controller(surface).getVisibility());
            int canvasWidth=surface.getWidth(),canvasHeight=surface.getHeight();
            surface.findViewById(R.id.sp_player_marks).performLongClick(); idle();
            assertEquals("markers",field(activity,"sheetMode",String.class));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2800));
            assertEquals("An open tool sheet pauses controller hiding",View.VISIBLE,controller(surface).getVisibility());
            capture(activity,"03-portrait-dark-markers-long-title-clock-fixture");
            assertEquals(canvasWidth,surface.getWidth()); assertEquals(canvasHeight,surface.getHeight());
            assertNotNull(findText(field(activity,"markerPanel",View.class),"01:20.000 — 01:35.000"));assertNotNull(findText(field(activity,"markerPanel",View.class),"留给夕阳的一段完整时间：远处的云海终于透出柔和的光"));
            findButton(field(activity,"markerPanel",View.class),"标记精彩片段").performClick(); idle();
            capture(activity,"04-portrait-dark-range-clock-fixture");
            assertEquals("range",field(activity,"sheetMode",String.class)); assertFullCanvas(root,surface);
            activity.onBackPressed(); idle(); assertFalse("Back closes tools before leaving video",activity.isFinishing());
            assertEquals(View.GONE,field(activity,"sheetOverlay",FrameLayout.class).getVisibility());
            surface.findViewById(R.id.sp_player_more).performClick(); idle();
            findButton(field(activity,"menuPanel",View.class),"视频信息").performClick(); idle();
            assertNotNull(findText(field(activity,"menuPanel",View.class),LONG_TITLE));
            assertNotNull(findText(field(activity,"menuPanel",View.class),"文件大小  "+MediaSize.format(source.length())));
            assertFullCanvas(root,surface);
        }
    }

    @Test @Config(sdk=35,qualifiers="w915dp-h412dp-land-xhdpi")
    public void landscapeUsesSameFitCanvasAndCompactOverlayWithNoPermanentSideOrBottomPage() throws Exception {
        try (Session session=openClockSession()) {
            PlayerActivity activity=session.activity; PlayerView surface=field(activity,"playerView",PlayerView.class);
            capture(activity,"05-landscape-controls-clock-fixture"); assertFullCanvas(field(activity,"root",FrameLayout.class),surface);
            surface.hideController(); idle(); capture(activity,"06-landscape-video-only-clock-fixture");
            surface.showController(); surface.findViewById(R.id.sp_player_marks).performLongClick(); idle();
            capture(activity,"07-landscape-dark-markers-clock-fixture");
            assertFullCanvas(field(activity,"root",FrameLayout.class),surface);
            FrameLayout sheet=field(activity,"sheetOverlay",FrameLayout.class); assertEquals(View.VISIBLE,sheet.getVisibility());
            activity.onBackPressed(); idle(); assertFalse(activity.isFinishing()); assertEquals(View.GONE,sheet.getVisibility());
        }
    }

    @Test public void missingFileShowsCenteredErrorAndKeepsMarkersOnDemand() throws Exception {
        assertTrue(source.delete());
        ActivityController<PlayerActivity> controller=Robolectric.buildActivity(PlayerActivity.class,intent());
        try {
            PlayerActivity activity=controller.create().start().resume().visible().get(); await(activity);
            capture(activity,"08-missing-original-centered-overlay-fixture");
            assertNull(field(activity,"player",Object.class)); assertFullCanvas(field(activity,"root",FrameLayout.class),field(activity,"playerView",PlayerView.class));
            assertEquals(View.VISIBLE,field(activity,"errorPanel",LinearLayout.class).getVisibility());
            assertTrue(field(activity,"status",TextView.class).getText().toString().contains("已有精彩标记仍保留"));
            field(activity,"markerOptionsButton",View.class).performClick(); idle();
            assertEquals("markers",field(activity,"sheetMode",String.class));
            assertFalse(field(activity,"bookmarkButton",Button.class).isEnabled());
            assertNotNull(findText(field(activity,"markerList",View.class),"00:20.250"));assertNotNull(findText(field(activity,"markerList",View.class),"第一阵风吹过山脊，我们都慢下了脚步"));
        } finally { controller.pause().stop().destroy(); idle(); }
    }

    @Test public void actualScrubListenersDriveLoaderBitmapTooltipClampAndIgnoreAFrameAfterStop() throws Exception {
        Frames frames=new Frames();
        try (Session session=openClockSession()) {
            PlayerActivity activity=session.activity;
            ScrubPreviewLoader original=field(activity,"previewLoader",ScrubPreviewLoader.class);
            ScrubPreviewLoader.Listener actualUiListener=field(original,"listener",ScrubPreviewLoader.Listener.class);
            original.release();
            ScrubPreviewLoader loader=new ScrubPreviewLoader(app,actualUiListener,frames);
            set(activity,"previewLoader",loader); loader.setSource(Uri.fromFile(source),100000);
            capture(activity,"09-preview-clock-and-bitmap-fixture-before-scrub");
            TimeBar bar=field(activity,"timeBar",TimeBar.class);
            replayScrub(bar,"start",1234,false); advanceAndDrain(loader);
            FrameLayout bubble=field(activity,"previewBubble",FrameLayout.class);
            ImageView image=field(activity,"previewImage",ImageView.class);
            assertEquals(View.VISIBLE,bubble.getVisibility()); assertTrue(image.getDrawable() instanceof BitmapDrawable);
            assertSame("The image must come through the real worker/listener path",frames.outputs.get(0),((BitmapDrawable)image.getDrawable()).getBitmap());
            assertEquals("00:01 · 预览≈00:01",field(activity,"previewTime",TextView.class).getText().toString());
            assertEquals(Long.valueOf(1000),frames.samples.get(0));
            layout(activity); FrameLayout root=field(activity,"root",FrameLayout.class);
            assertTrue("Tooltip stays inside the left edge",bubble.getLeft()>=root.getPaddingLeft()+dp(activity,8));

            replayScrub(bar,"move",99999,false);
            assertEquals("A retained frame must not masquerade as the new seek target", "01:39 · 预览≈00:01",field(activity,"previewTime",TextView.class).getText().toString());
            advanceAndDrain(loader); layout(activity);
            assertEquals("01:39 · 预览≈01:39",field(activity,"previewTime",TextView.class).getText().toString());
            assertTrue("Tooltip stays inside the right edge",bubble.getRight()<=root.getWidth()-root.getPaddingRight()-dp(activity,8));
            assertTrue(bubble.getWidth()<=dp(activity,160));
            View timeBarView=field(activity,"timeBarView",View.class); int[] barLocation=new int[2],bubbleLocation=new int[2];
            timeBarView.getLocationInWindow(barLocation); bubble.getLocationInWindow(bubbleLocation);
            assertTrue("Preview floats above the real time bar",bubbleLocation[1]+bubble.getHeight()<=barLocation[1]);
            capture(activity,"10-preview-above-clamped-thumb-bitmap-fixture");

            loader.setSource(Uri.parse("content://preview.fixture/another-video"),100000);
            assertNull("Source invalidation immediately clears the old displayed bitmap",image.getDrawable());
            assertEquals("01:39 · 正在预览",field(activity,"previewTime",TextView.class).getText().toString());

            replayScrub(bar,"move",95000,false);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90));
            assertTrue(frames.blocked.await(5,TimeUnit.SECONDS));
            replayScrub(bar,"stop",95000,false); idle();
            assertEquals(View.GONE,bubble.getVisibility()); assertNull(image.getDrawable());
            frames.finish.countDown(); drainPreview(loader);
            assertEquals("A late worker frame cannot reopen the tooltip",View.GONE,bubble.getVisibility());
            assertNull(image.getDrawable());
        } finally { frames.finish.countDown(); }
    }

    private Session openClockSession() throws Exception {
        ActivityController<PlayerActivity> controller=Robolectric.buildActivity(PlayerActivity.class,intent());
        PlayerActivity activity=controller.create().get(); await(activity);
        assertNull("No real player before onStart",field(activity,"player",Object.class));
        Clock clock=new Clock(); ExoPlayer clockPlayer=clock.proxy(); set(activity,"player",clockPlayer);
        // PlayerView treats a null player as indefinitely visible regardless of auto-show.
        // Attach through the real API, while the fixture denies every surface/decoder command.
        field(activity,"playerView",PlayerView.class).setPlayer(clockPlayer);
        invoke(activity,"setMediaActions",new Class<?>[]{boolean.class},true);
        field(activity,"errorPanel",LinearLayout.class).setVisibility(View.GONE);
        TextView provenance=new TextView(activity); provenance.setText("界面夹具 · 不含视频解码"); provenance.setTextColor(0xFF71717A); provenance.setTextSize(12); provenance.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,dp(activity,24),Gravity.CENTER);
        field(activity,"root",FrameLayout.class).addView(provenance,p);
        controller.start().resume().visible(); idle(); field(activity,"playerView",PlayerView.class).showController();
        return new Session(controller,activity);
    }
    private Intent intent() { return new Intent(app,PlayerActivity.class).putExtra("video_id",videoId); }
    private static View controller(PlayerView surface) { return surface.findViewById(androidx.media3.ui.R.id.exo_controller); }
    private static void assertFullCanvas(FrameLayout root,PlayerView surface) {
        assertTrue(root.getBackground() instanceof ColorDrawable); assertEquals(0xFF0A0C0F,((ColorDrawable)root.getBackground()).getColor());
        assertEquals(root.getWidth()-root.getPaddingLeft()-root.getPaddingRight(),surface.getWidth());
        assertEquals(root.getHeight()-root.getPaddingTop()-root.getPaddingBottom(),surface.getHeight());
        assertEquals(root.getPaddingLeft(),surface.getLeft()); assertEquals(root.getPaddingTop(),surface.getTop());
    }
    private static int dp(Context context,int n) { return Math.round(context.getResources().getDisplayMetrics().density*n); }
    private static void await(Object owner) throws Exception { ExecutorService io=field(owner,"io",ExecutorService.class); io.submit(()->{}).get(10,TimeUnit.SECONDS); idle(); io.submit(()->{}).get(10,TimeUnit.SECONDS); idle(); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    @SuppressWarnings("unchecked") private static void replayScrub(TimeBar bar,String phase,long at,boolean canceled) throws Exception {
        // Replay the actual DefaultTimeBar listener list (Media3 listener + Activity listener).
        // No onPreview callback is fabricated; the loader produces its own worker callback.
        for (TimeBar.OnScrubListener listener:(Iterable<TimeBar.OnScrubListener>)field(bar,"listeners",Iterable.class)) {
            if ("start".equals(phase)) listener.onScrubStart(bar,at);
            else if ("move".equals(phase)) listener.onScrubMove(bar,at);
            else listener.onScrubStop(bar,at,canceled);
        }
    }
    private static void advanceAndDrain(ScrubPreviewLoader loader) throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90)); drainPreview(loader);
    }
    private static void drainPreview(ScrubPreviewLoader loader) throws Exception { field(loader,"worker",ExecutorService.class).submit(()->{}).get(5,TimeUnit.SECONDS); idle(); }
    private static <T>T field(Object owner,String name,Class<T> type) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(owner)); }
    private static void set(Object owner,String name,Object value) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value); }
    private static Object invoke(Object owner,String name,Class<?>[] types,Object... values) throws Exception { Method m=owner.getClass().getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(owner,values); }
    private static Button findButton(View view,String title) { TextView found=findText(view,title); assertTrue(found instanceof Button); return (Button)found; }
    private static TextView findText(View view,String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) { TextView found=findText(group.getChildAt(i),text); if (found!=null) return found; } } return null;
    }
    private static String outputDirectory() {
        File root=new File(System.getProperty("user.dir")); while (root!=null && !new File(root,"settings.gradle").isFile() && !new File(root,"settings.gradle.kts").isFile()) root=root.getParentFile();
        return new File(root==null?new File(System.getProperty("user.dir")):root,"docs/design/assets/022-player-regression").getAbsolutePath();
    }
    private static void capture(PlayerActivity activity,String name) throws Exception {
        idle(); layout(activity); View decor=activity.getWindow().getDecorView();
        Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.TRANSPARENT); decor.draw(new Canvas(bitmap));
        if (!OUTPUT.isDirectory() && !OUTPUT.mkdirs()) throw new java.io.IOException("Cannot create replay output");
        try (FileOutputStream out=new FileOutputStream(new File(OUTPUT,name+".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); }
        StringBuilder tree=new StringBuilder("Real native Android Views in Robolectric. Explicit clock/source-metadata fixture; NO decoded video frames or installed-phone claim.\n"); dump(decor,tree,0);
        try (FileOutputStream out=new FileOutputStream(new File(OUTPUT,name+".txt"))) { out.write(tree.toString().getBytes(StandardCharsets.UTF_8)); } bitmap.recycle();
    }
    private static void layout(PlayerActivity activity) {
        View decor=activity.getWindow().getDecorView(); float density=activity.getResources().getDisplayMetrics().density;
        int width=Math.round(activity.getResources().getConfiguration().screenWidthDp*density),height=Math.round(activity.getResources().getConfiguration().screenHeightDp*density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); decor.layout(0,0,decor.getMeasuredWidth(),decor.getMeasuredHeight());
    }
    private static void dump(View view,StringBuilder text,int depth) {
        if (view.getVisibility()!=View.VISIBLE) return; Rect bounds=new Rect(); view.getGlobalVisibleRect(bounds);
        for (int i=0;i<depth;i++) text.append("  "); text.append(view.getClass().getSimpleName()).append(' ').append(bounds.toShortString()); if (view instanceof TextView) text.append(" text=").append(((TextView)view).getText().toString().replace('\n',' ')); text.append('\n');
        if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) dump(group.getChildAt(i),text,depth+1); }
    }
    private static final class Session implements AutoCloseable {
        final ActivityController<PlayerActivity> controller; final PlayerActivity activity;
        Session(ActivityController<PlayerActivity> controller,PlayerActivity activity) { this.controller=controller; this.activity=activity; }
        @Override public void close() { controller.pause().stop().destroy(); idle(); }
    }
    private static final class Clock implements InvocationHandler {
        long position=34250; boolean play,scrubMode;
        ExoPlayer proxy() { return (ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class<?>[]{ExoPlayer.class},this); }
        @Override public Object invoke(Object proxy,Method method,Object[] args) {
            assertEquals(Looper.getMainLooper(),Looper.myLooper());
            switch (method.getName()) {
                case "getApplicationLooper": return Looper.getMainLooper();
                case "getAvailableCommands": return Player.Commands.EMPTY;
                case "isCommandAvailable": return false;
                case "isScrubbingModeEnabled": return scrubMode;
                case "setScrubbingModeEnabled": scrubMode=(Boolean)args[0]; return null;
                case "addListener": case "removeListener": case "setImageOutput": return null;
                case "getPlaybackState": return Player.STATE_READY;
                case "getPlayerError": return null;
                case "getCurrentTimeline": return Timeline.EMPTY;
                case "getCurrentTracks": return Tracks.EMPTY;
                case "getMediaMetadata": case "getPlaylistMetadata": return MediaMetadata.EMPTY;
                case "getVideoSize": return VideoSize.UNKNOWN;
                case "getCurrentCues": return CueGroup.EMPTY_TIME_ZERO;
                case "getPlaybackParameters": return PlaybackParameters.DEFAULT;
                case "getPlaybackSuppressionReason": case "getRepeatMode": case "getCurrentMediaItemIndex": return 0;
                case "getShuffleModeEnabled": case "isPlayingAd": return false;
                case "getCurrentMediaItem": return null;
                case "getContentPosition": case "getBufferedPosition": case "getContentBufferedPosition": return position;
                case "getContentDuration": case "getTotalBufferedDuration": return 100000L;
                case "getSeekBackIncrement": case "getSeekForwardIncrement": return 10000L;
                case "getCurrentPosition": return position;
                case "getDuration": return 100000L;
                case "setPlaybackSpeed": return null;
                case "seekTo": position=(Long)args[args.length-1]; return null;
                case "getPlayWhenReady": return play;
                case "setPlayWhenReady": play=(Boolean)args[0]; return null;
                case "isPlaying": return false;
                case "pause": play=false; return null;
                case "release": return null;
                case "toString": return "Clock-only view fixture";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy==args[0];
                default: throw new UnsupportedOperationException("Clock fixture cannot "+method.getName());
            }
        }
    }
    private static final class Frames implements ScrubPreviewLoader.DecoderFactory {
        final java.util.List<Long> samples=new CopyOnWriteArrayList<>();
        final java.util.List<Bitmap> outputs=new CopyOnWriteArrayList<>();
        final CountDownLatch blocked=new CountDownLatch(1),finish=new CountDownLatch(1);
        @Override public ScrubPreviewLoader.FrameDecoder open(Context context,Uri source) {
            assertNotEquals("Fixture frame opening stays on the worker",Looper.getMainLooper(),Looper.myLooper());
            return new ScrubPreviewLoader.FrameDecoder() {
                @Override public Bitmap frameAt(long at) throws Exception {
                    assertNotEquals(Looper.getMainLooper(),Looper.myLooper()); samples.add(at);
                    if (at==95000) { blocked.countDown(); if (!finish.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Blocked bitmap fixture timed out"); }
                    Bitmap image=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888); image.eraseColor(at<5000?0xFF52525B:0xFF71717A); outputs.add(image); return image;
                }
                @Override public void close() { assertNotEquals(Looper.getMainLooper(),Looper.myLooper()); }
            };
        }
    }
}
