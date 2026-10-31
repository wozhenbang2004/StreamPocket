package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.common.*;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Production View gestures plus a decoder-free interface clock; images are explicit bitmap fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class Player022UiTest {
    private Context app;
    private long videoId;
    private File source;
    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");
        app.getSharedPreferences("config",0).edit().clear().putBoolean("motion_enabled",false).commit();
        source=new File(app.getCacheDir(),"player022-clock-source.mp4");
        try(FileOutputStream out=new FileOutputStream(source)){out.write("Explicit metadata fixture, not a video decoder".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        try(LibraryStore store=new LibraryStore(app)) {
            videoId=store.upsertCompletedDownload(new JSONObject().put("id","player022-fixture").put("status","completed")
                    .put("title","山间的一百秒 · 播放手势和精彩标记的明确界面夹具")
                    .put("uri",android.net.Uri.fromFile(source)).put("size",source.length()).put("mime","video/mp4")
                    .put("media",new JSONObject().put("duration",100)));
            store.addMarker(videoId,20000,"山风");store.addRangeMarker(videoId,80000,95000,"夕阳片段");
        }
        Settings.System.putInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,127);
    }
    @Test public void defaultFitAndExplicitCropUseSameCanvasAndKeepSelectedModeAcrossSavedState() throws Exception {
        try(Session s=open()) {
            PlayerView surface=field(s.a,"playerView",PlayerView.class);
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT,surface.getResizeMode());
            surface.showController();idle();capture(s.a,"00-canvas-controls-clock-fixture");
            surface.hideController();idle();capture(s.a,"00b-canvas-hidden-controls-clock-fixture");
            surface.showController();idle();
            surface.findViewById(R.id.sp_player_more).performClick();idle();
            find(field(s.a,"menuPanel",View.class),"画面比例 · 完整显示").performClick();idle();
            find(field(s.a,"menuPanel",View.class),"填充裁切").performClick();idle();
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM,surface.getResizeMode());
            assertEquals(View.GONE,field(s.a,"sheetOverlay",View.class).getVisibility());
            Bundle saved=new Bundle();s.a.onSaveInstanceState(saved);assertTrue(saved.getBoolean("fill_video"));
            surface.findViewById(R.id.sp_player_more).performClick();idle();
            find(field(s.a,"menuPanel",View.class),"画面比例 · 填充裁切").performClick();idle();
            capture(s.a,"01-aspect-sheet-clock-fixture");
            find(field(s.a,"menuPanel",View.class),"完整显示").performClick();idle();
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT,surface.getResizeMode());
        }
    }
    /** Fixed-source behavior: animated Media3 keeps ONLY_PROGRESS_VISIBLE for 2s.
     * https://github.com/androidx/media/blob/1.9.4/libraries/ui/src/main/java/androidx/media3/ui/PlayerControlViewLayoutManager.java
     * Whole-overlay disappearance therefore uses the public single-stage timer;
     * appearance remains a short optional fade, without a second hide scheduler. */
    @Test public void motionEnabledStillHidesTheWholeOverlayAt2500AndHiddenMarkDoesNotConsumeTheWakeTap() throws Exception {
        app.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",true).commit();
        try(Session s=open()) {
            PlayerView surface=field(s.a,"playerView",PlayerView.class);
            View controls=surface.findViewById(androidx.media3.ui.R.id.exo_controller);
            assertFalse("Single-stage auto-hide must not opt back into Media3's progress-only stage",((androidx.media3.ui.PlayerControlView)controls).isAnimationEnabled());
            surface.showController();idle();
            View mark=field(s.a,"markerOptionsButton",View.class);int[] at=new int[2],base=new int[2];mark.getLocationInWindow(at);surface.getLocationInWindow(base);
            float x=(at[0]-base[0]+mark.getWidth()/2f)/surface.getWidth(),y=(at[1]-base[1]+mark.getHeight()/2f)/surface.getHeight();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2499));assertEquals(View.VISIBLE,controls.getVisibility());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertEquals(View.GONE,controls.getVisibility());
            assertEquals(1f,controls.getAlpha(),.001f);assertFalse(mark.isShown());
            long down=SystemClock.uptimeMillis();dispatch(surface,MotionEvent.ACTION_DOWN,x,y,down);dispatch(surface,MotionEvent.ACTION_UP,x,y,down);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));await(s.a);
            assertEquals("A canvas tap wakes the actual controller",View.VISIBLE,controls.getVisibility());
            try(LibraryStore store=new LibraryStore(app)){assertEquals("The previously hidden mark control must not receive this wake tap",2,store.markers(videoId).size());}
        }
    }
    @Test public void quickBookmarkWritesOriginalMillisecondsWhileLongPressOpensSavedHighlightsAndDrawsTicks() throws Exception {
        try(Session s=open()) {
            s.clock.position=64250;
            View marks=field(s.a,"markerOptionsButton",View.class);marks.performClick();await(s.a);
            assertEquals("",field(s.a,"sheetMode",String.class));
            try(LibraryStore store=new LibraryStore(app)) {
                assertTrue(store.markers(videoId).stream().anyMatch(m->m.optLong("startMs")==64250));
            }
            assertEquals("已标记 · 01:04",field(s.a,"gestureHint",TextView.class).getText().toString());
            marks.performLongClick();idle();assertEquals("markers",field(s.a,"sheetMode",String.class));
            assertNotNull(find(field(s.a,"markerList",View.class),"01:04\n精彩时刻"));
            View ticks=field(s.a,"highlightTicks",View.class);ticks.measure(exact(400),exact(48));ticks.layout(0,0,400,48);
            Bitmap bitmap=Bitmap.createBitmap(400,48,Bitmap.Config.ARGB_8888);ticks.draw(new Canvas(bitmap));
            boolean visible=false;for(int x=0;x<400;x++)for(int y=0;y<48;y++)if((bitmap.getPixel(x,y)>>>24)!=0)visible=true;
            assertTrue("Stored marks produce visible timeline ticks",visible);bitmap.recycle();
            capture(s.a,"02-long-press-highlight-sheet-clock-fixture");
        }
    }
    @Test public void actualCanvasDoubleTapsHonorConfiguredSecondsAndClampAtBothEnds() throws Exception {
        try(Session s=open()) {
            PlayerView surface=field(s.a,"playerView",PlayerView.class);surface.hideController();idle();
            s.clock.position=95000;doubleTap(surface,.8f);assertEquals(100000,s.clock.position);
            app.getSharedPreferences("config",0).edit().putInt("seek_seconds",30).commit();
            doubleTap(surface,.2f);assertEquals(70000,s.clock.position);
            s.clock.position=1000;doubleTap(surface,.2f);assertEquals(0,s.clock.position);
            assertTrue(field(s.a,"gestureHint",TextView.class).getText().toString().contains("快退 30 秒"));
        }
    }
    @Test public void horizontalCanvasDragPreviewsBeforeCommitAndOptionalVerticalGesturesStayPlayerLocal() throws Exception {
        try(Session s=open()) {
            PlayerView surface=field(s.a,"playerView",PlayerView.class);surface.hideController();idle();
            s.clock.position=10000;long down=SystemClock.uptimeMillis();
            dispatch(surface,MotionEvent.ACTION_DOWN,.2f,.35f,down);
            dispatch(surface,MotionEvent.ACTION_MOVE,.7f,.35f,down);
            assertEquals("Dragging does not issue repeated media seeks",10000,s.clock.position);
            assertEquals(View.VISIBLE,field(s.a,"previewBubble",View.class).getVisibility());
            capture(s.a,"03-canvas-drag-preview-pending-frame-clock-fixture");
            dispatch(surface,MotionEvent.ACTION_UP,.7f,.35f,down);idle();
            assertTrue(s.clock.position>50000 && s.clock.position<70000);
            assertEquals(View.GONE,field(s.a,"previewBubble",View.class).getVisibility());
            s.clock.volume=.4f;
            swipe(surface,.85f,.75f,.85f,.25f);assertEquals(.4f,s.clock.volume,.001f);
            app.getSharedPreferences("config",0).edit().putBoolean("player_gestures",true).commit();
            swipe(surface,.85f,.75f,.85f,.25f);assertTrue(s.clock.volume>.4f && s.clock.volume<=1f);
            swipe(surface,.15f,.75f,.15f,.25f);assertTrue(s.a.getWindow().getAttributes().screenBrightness>0f);
            assertEquals("Window brightness must not rewrite the system setting",127,Settings.System.getInt(app.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS));
        }
    }
    @Test public void transitionBitmapLoadsOnlyFromBoundedPrivateCoverDirectoryAndWaitsForVideoFrame() throws Exception {
        File folder=new File(app.getCacheDir(),"video-covers");assertTrue(folder.isDirectory()||folder.mkdirs());
        File cover=new File(folder,"player022.jpg");Bitmap bitmap=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xFF526F69);
        try(FileOutputStream out=new FileOutputStream(cover)){assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,out));}bitmap.recycle();
        assertEquals(cover.getCanonicalFile(),PlayerActivity.validatedCoverFile(cover.getPath(),app.getCacheDir()));
        assertNull(PlayerActivity.validatedCoverFile(source.getPath(),app.getCacheDir()));
        assertNull(PlayerActivity.validatedCoverFile(new File(folder,"../player022-clock-source.mp4").getPath(),app.getCacheDir()));
        app.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",true).commit();
        Intent intent=new Intent(app,PlayerActivity.class).putExtra("video_id",videoId).putExtra("transition_name","sp-video-"+videoId).putExtra("transition_cover_path",cover.getPath());
        ActivityController<PlayerActivity> c=Robolectric.buildActivity(PlayerActivity.class,intent);
        try {
            PlayerActivity a=c.create().get();await(a);ImageView image=field(a,"transitionCover",ImageView.class);
            assertTrue(image.getDrawable() instanceof BitmapDrawable);assertEquals(View.VISIBLE,image.getVisibility());
            assertFalse("Cover stays until a real renderer callback; reading a bitmap is not a rendered video frame",field(a,"firstFrameRendered",Boolean.class));
            assertEquals("sp-video-"+videoId,image.getTransitionName());assertNotNull(a.getWindow().getSharedElementEnterTransition());
            TextView provenance=new TextView(a);provenance.setText("真实缓存封面夹具，非视频硬件解码");provenance.setTextColor(0xFFFAFAFA);provenance.setTextSize(12);provenance.setGravity(android.view.Gravity.CENTER);
            FrameLayout.LayoutParams caption=new FrameLayout.LayoutParams(-1,64,android.view.Gravity.CENTER);field(a,"root",FrameLayout.class).addView(provenance,caption);
            capture(a,"04-private-jpeg-cover-awaiting-real-video-frame-fixture");
        } finally {c.destroy();idle();}
    }
    @Test public void centerDragMovesSheetBeforeReleaseAndCancellationClosesThePartialReveal() throws Exception {
        try(Session s=open()) {
            PlayerView surface=field(s.a,"playerView",PlayerView.class);surface.hideController();idle();
            long down=SystemClock.uptimeMillis();dispatch(surface,MotionEvent.ACTION_DOWN,.5f,.75f,down);
            dispatch(surface,MotionEvent.ACTION_MOVE,.5f,.68f,down);layout(s.a);
            View overlay=field(s.a,"sheetOverlay",View.class),sheet=field(s.a,"sheetBody",View.class);
            assertEquals(View.VISIBLE,overlay.getVisibility());assertTrue("Panel follows MOVE, not only UP",sheet.getTranslationY()>0);
            float partial=sheet.getTranslationY();dispatch(surface,MotionEvent.ACTION_MOVE,.5f,.58f,down);
            assertTrue("Further upward movement reveals more sheet",sheet.getTranslationY()<partial);
            dispatch(surface,MotionEvent.ACTION_CANCEL,.5f,.58f,down);idle();assertEquals(View.GONE,overlay.getVisibility());
            dispatch(surface,MotionEvent.ACTION_DOWN,.5f,.75f,down);dispatch(surface,MotionEvent.ACTION_MOVE,.5f,.30f,down);dispatch(surface,MotionEvent.ACTION_UP,.5f,.30f,down);idle();layout(s.a);
            assertEquals(View.VISIBLE,overlay.getVisibility());assertEquals(0f,sheet.getTranslationY(),.01f);
        }
    }
    @Test public void sheetHandleTracksReversalCancelAndCloseWithoutBreakingBackPriority() throws Exception {
        try(Session s=open()) {
            invoke(s.a,"showSheet",new Class<?>[]{String.class},"info");layout(s.a);
            View overlay=field(s.a,"sheetOverlay",View.class),sheet=field(s.a,"sheetBody",View.class);float x=overlay.getWidth()/2f,y=sheet.getTop()+20;
            long down=SystemClock.uptimeMillis();sheetEvent(overlay,MotionEvent.ACTION_DOWN,x,y,down);
            sheetEvent(overlay,MotionEvent.ACTION_MOVE,x,y+180,down);assertTrue(sheet.getTranslationY()>100);
            sheetEvent(overlay,MotionEvent.ACTION_MOVE,x,y+60,down);assertEquals(60f,sheet.getTranslationY(),1f);
            sheetEvent(overlay,MotionEvent.ACTION_CANCEL,x,y+60,down);assertEquals(0f,sheet.getTranslationY(),.01f);assertEquals(View.VISIBLE,overlay.getVisibility());
            sheetEvent(overlay,MotionEvent.ACTION_DOWN,x,y,down);sheetEvent(overlay,MotionEvent.ACTION_MOVE,x,y+sheet.getHeight()*.6f,down);sheetEvent(overlay,MotionEvent.ACTION_UP,x,y+sheet.getHeight()*.6f,down);idle();assertEquals(View.GONE,overlay.getVisibility());
            invoke(s.a,"showSheet",new Class<?>[]{String.class},"info");s.a.onBackPressed();assertEquals(View.GONE,overlay.getVisibility());assertFalse("First Back dismisses the sheet, not the player",s.a.isFinishing());
        }
    }
    @Test public void interruptedSheetCloseReversesFromItsCurrentPosition() throws Exception {
        try(Session s=open()) {
            invoke(s.a,"showSheet",new Class<?>[]{String.class},"info");layout(s.a);
            app.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",true).commit();
            Settings.Global.putFloat(app.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f);
            invoke(s.a,"closeSheet",new Class<?>[0]);android.animation.ValueAnimator closing=field(s.a,"sheetAnimator",android.animation.ValueAnimator.class);assertNotNull("Enabled motion uses the production settle animator",closing);closing.setCurrentFraction(.4f);
            View sheet=field(s.a,"sheetBody",View.class);float partial=sheet.getTranslationY();assertTrue(partial>0);
            invoke(s.a,"showSheet",new Class<?>[]{String.class},"info");assertEquals("Reversal begins where the interrupted close left off",partial,sheet.getTranslationY(),1f);
            field(s.a,"sheetAnimator",android.animation.ValueAnimator.class).end();assertEquals(0f,sheet.getTranslationY(),.01f);assertEquals(View.VISIBLE,field(s.a,"sheetOverlay",View.class).getVisibility());
        }
    }

    private static void sheetEvent(View v,int action,float x,float y,long down){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);v.dispatchTouchEvent(e);e.recycle();}

    private Session open() throws Exception {
        ActivityController<PlayerActivity> c=Robolectric.buildActivity(PlayerActivity.class,new Intent(app,PlayerActivity.class).putExtra("video_id",videoId));
        PlayerActivity a=c.create().get();await(a);Clock clock=new Clock();ExoPlayer player=clock.proxy();set(a,"player",player);field(a,"playerView",PlayerView.class).setPlayer(player);
        invoke(a,"setMediaActions",new Class<?>[]{boolean.class},true);field(a,"errorPanel",View.class).setVisibility(View.GONE);
        TextView provenance=new TextView(a);provenance.setText("界面时钟夹具 · 不含视频解码");provenance.setTextColor(0xFF71717A);provenance.setTextSize(12);provenance.setGravity(android.view.Gravity.CENTER);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,48,android.view.Gravity.CENTER);field(a,"root",FrameLayout.class).addView(provenance,p);
        c.start().resume().visible();idle();layout(a);return new Session(c,a,clock);
    }
    private static void doubleTap(PlayerView v,float x) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        long down=SystemClock.uptimeMillis();dispatch(v,MotionEvent.ACTION_DOWN,x,.3f,down);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));dispatch(v,MotionEvent.ACTION_UP,x,.3f,down);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90));down=SystemClock.uptimeMillis();dispatch(v,MotionEvent.ACTION_DOWN,x,.3f,down);dispatch(v,MotionEvent.ACTION_UP,x,.3f,down);idle();
    }
    private static void swipe(PlayerView v,float x1,float y1,float x2,float y2) {long down=SystemClock.uptimeMillis();dispatch(v,MotionEvent.ACTION_DOWN,x1,y1,down);dispatch(v,MotionEvent.ACTION_MOVE,x2,y2,down);dispatch(v,MotionEvent.ACTION_UP,x2,y2,down);idle();}
    private static void dispatch(PlayerView v,int action,float x,float y,long down) {MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x*v.getWidth(),y*v.getHeight(),0);v.dispatchTouchEvent(event);event.recycle();}
    private static void await(Object a) throws Exception {ExecutorService io=field(a,"io",ExecutorService.class);io.submit(()->{}).get(8,TimeUnit.SECONDS);idle();io.submit(()->{}).get(8,TimeUnit.SECONDS);idle();}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static int exact(int value){return View.MeasureSpec.makeMeasureSpec(value,View.MeasureSpec.EXACTLY);}
    private static void layout(PlayerActivity a){View v=a.getWindow().getDecorView();float d=a.getResources().getDisplayMetrics().density;v.measure(exact((int)(412*d)),exact((int)(915*d)));v.layout(0,0,v.getMeasuredWidth(),v.getMeasuredHeight());}
    private static void capture(PlayerActivity a,String name) throws Exception {
        layout(a);View view=a.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        File root=new File(System.getProperty("user.dir"));while(root!=null&&!new File(root,"settings.gradle").isFile())root=root.getParentFile();
        File output=new File(root,"docs/design/assets/022-player");assertTrue(output.isDirectory()||output.mkdirs());try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}bitmap.recycle();
    }
    private static <T>T field(Object owner,String name,Class<T> type) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(owner));}
    private static void set(Object owner,String name,Object value) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Object invoke(Object owner,String name,Class<?>[] types,Object... args) throws Exception {Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static TextView find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=find(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    private static final class Session implements AutoCloseable {final ActivityController<PlayerActivity> c;final PlayerActivity a;final Clock clock;Session(ActivityController<PlayerActivity> c,PlayerActivity a,Clock clock){this.c=c;this.a=a;this.clock=clock;}@Override public void close(){c.pause().stop().destroy();idle();}}
    private static final class Clock implements InvocationHandler {
        long position=34250;boolean play,scrub;float volume=1f;
        ExoPlayer proxy(){return (ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class<?>[]{ExoPlayer.class},this);}
        @Override public Object invoke(Object proxy,Method method,Object[] args){
            switch(method.getName()){
                case "getApplicationLooper":return Looper.getMainLooper();case "getAvailableCommands":return Player.Commands.EMPTY;case "isCommandAvailable":return false;
                case "isScrubbingModeEnabled":return scrub;case "setScrubbingModeEnabled":scrub=(Boolean)args[0];return null;
                case "addListener":case "removeListener":case "setImageOutput":return null;
                case "getPlaybackState":return Player.STATE_READY;case "getPlayerError":return null;case "getCurrentTimeline":return Timeline.EMPTY;case "getCurrentTracks":return Tracks.EMPTY;
                case "getMediaMetadata":case "getPlaylistMetadata":return MediaMetadata.EMPTY;case "getVideoSize":return VideoSize.UNKNOWN;case "getCurrentCues":return CueGroup.EMPTY_TIME_ZERO;
                case "getPlaybackParameters":return PlaybackParameters.DEFAULT;case "getPlaybackSuppressionReason":case "getRepeatMode":case "getCurrentMediaItemIndex":return 0;
                case "getShuffleModeEnabled":case "isPlayingAd":return false;case "getCurrentMediaItem":return null;
                case "getContentPosition":case "getBufferedPosition":case "getContentBufferedPosition":case "getCurrentPosition":return position;
                case "getContentDuration":case "getDuration":case "getTotalBufferedDuration":return 100000L;case "getSeekBackIncrement":case "getSeekForwardIncrement":return 10000L;
                case "setPlaybackSpeed":return null;case "seekTo":position=(Long)args[args.length-1];return null;
                case "getVolume":return volume;case "setVolume":volume=(Float)args[0];return null;
                case "getPlayWhenReady":return play;case "setPlayWhenReady":play=(Boolean)args[0];return null;case "isPlaying":return false;case "pause":play=false;return null;case "release":return null;
                case "toString":return "Decoder-free clock fixture";case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==args[0];default:throw new UnsupportedOperationException("No decoder in clock fixture: "+method.getName());
            }
        }
    }
}
