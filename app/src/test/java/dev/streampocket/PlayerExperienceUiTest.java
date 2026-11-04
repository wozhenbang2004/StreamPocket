package dev.streampocket;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real controls and lifecycle callbacks with an interface clock; OS PiP/decoding use instrumentation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PlayerExperienceUiTest {
    private Context app;
    private long videoId;

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("config",0).edit().clear().putBoolean("motion_enabled",false).commit();
        Shadows.shadowOf(app.getPackageManager()).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE,true);
        videoId=seed("current","系列 · 山间的一百秒",10000);
    }
    @After public void clearPlayerPreferences(){app.getSharedPreferences("config",0).edit().remove("player_playback_mode").remove("player_subtitles_off").commit();}

    @Test public void modeChoicesPreservePauseAndPositionAndRememberTheChoice() throws Exception {
        Bundle saved=new Bundle();
        try(Session session=open(null,null)){
            session.clock.base.play=false;session.clock.base.position=41750;
            more(session);capture(session.activity,"01-watching-tools");
            click(session,"播放模式 · 单次播放");assertTrue(button(session,"单次播放").isSelected());
            click(session,"单个循环");assertEquals(Player.REPEAT_MODE_ONE,session.clock.repeatMode);
            assertFalse(session.clock.base.play);assertEquals(41750,session.clock.base.position);
            click(session,"连续播放");assertEquals(Player.REPEAT_MODE_OFF,session.clock.repeatMode);
            assertTrue(button(session,"连续播放").isSelected());capture(session.activity,"02-playback-modes");
            session.activity.onSaveInstanceState(saved);assertEquals("continuous",saved.getString("playback_mode"));assertFalse(saved.getBoolean("should_play"));
            assertEquals("continuous",app.getSharedPreferences("config",0).getString("player_playback_mode",""));
        }
        try(Session restored=open(saved,null)){
            assertFalse(restored.clock.base.play);assertEquals(41750,restored.clock.base.position);
            more(restored);assertNotNull(button(restored,"播放模式 · 连续播放"));
        }
    }

    @Test public void realTrackChoicesDistinguishAutomaticUnsupportedAndSubtitlesOff() throws Exception {
        try(Session session=open(null,null)){
            session.clock.base.play=false;session.clock.base.position=34500;session.clock.tracks=sampleTracks();
            more(session);click(session,"音轨与字幕");
            List<Button> automatic=buttons(session,"自动");assertEquals(2,automatic.size());
            assertTrue(automatic.get(0).isSelected());assertTrue(automatic.get(1).isSelected());
            assertEquals("Default tracks must not create a second check in either group",2,selectedButtons(field(session.activity,"menuPanel",View.class)));
            assertFalse(buttonContaining(session,"特殊音轨").isEnabled());
            capture(session.activity,"03-audio-and-subtitle-choices");
            buttonContaining(session,"解说").performClick();idle();
            TrackSelectionOverride audio=session.clock.parameters.overrides.get(sampleTracks().getGroups().get(1).getMediaTrackGroup());
            assertNotNull(audio);assertEquals(Arrays.asList(1),audio.trackIndices);
            assertFalse(session.clock.base.play);assertEquals(34500,session.clock.base.position);
            click(session,"关闭字幕");assertTrue(session.clock.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
            assertTrue(button(session,"关闭字幕").isSelected());
            layout(session.activity);field(session.activity,"details",android.widget.ScrollView.class).fullScroll(View.FOCUS_DOWN);capture(session.activity,"04-subtitles-off");
            buttons(session,"自动").get(1).performClick();idle();
            assertFalse(session.clock.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
            assertNotNull("Automatic subtitles must preserve the chosen audio track",session.clock.parameters.overrides.get(audio.mediaTrackGroup));
            buttonContaining(session,"中文字幕").performClick();idle();
            assertTrue(session.clock.parameters.overrides.values().stream().anyMatch(value->value.getType()==C.TRACK_TYPE_TEXT));
            assertFalse(session.clock.base.play);assertEquals(34500,session.clock.base.position);
        }
    }

    @Test public void switchingMediaClearsGroupOverridesButKeepsSubtitleOffIntent() throws Exception {
        long next=seed("another","另一个视频",1000);
        try(Session session=open(null,null)){
            session.clock.tracks=sampleTracks();more(session);click(session,"音轨与字幕");buttonContaining(session,"解说").performClick();click(session,"关闭字幕");
            assertFalse(session.clock.parameters.overrides.isEmpty());
            // Observe the replacement contract before allowing another real ExoPlayer to prepare.
            set(session.activity,"started",false);
            session.activity.onNewIntent(new Intent(app,PlayerActivity.class).putExtra("video_id",next));await(session.activity);
            TrackSelectionParameters parameters=field(session.activity,"trackParameters",TrackSelectionParameters.class);
            assertTrue(parameters.overrides.isEmpty());assertTrue(parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
            assertEquals(1,session.clock.releases);assertEquals(next,field(session.activity,"videoId",Long.class).longValue());
            assertTrue(app.getSharedPreferences("config",0).getBoolean("player_subtitles_off",false));
        }
    }

    @Test public void explicitPipKeepsThePlayerAndRemotePauseSurvivesReturnToFullScreen() throws Exception {
        try(Session session=open(null,null)){
            enterPip(session);ExoPlayer before=field(session.activity,"player",ExoPlayer.class);session.pause();
            assertSame(before,field(session.activity,"player",ExoPlayer.class));assertEquals(0,session.clock.releases);
            action(session,"暂停").getActionIntent().send();idle();
            assertFalse(session.clock.base.play);assertFalse(field(session.activity,"shouldPlay",Boolean.class));
            session.clock.base.position=51250;
            // Robolectric models entry, but window animation/return is covered on the emulator.
            session.activity.moveTaskToBack(true);session.activity.onPictureInPictureModeChanged(false,session.activity.getResources().getConfiguration());session.resume();
            assertSame(before,field(session.activity,"player",ExoPlayer.class));assertFalse(session.clock.base.play);
            Bundle state=new Bundle();session.activity.onSaveInstanceState(state);assertFalse(state.getBoolean("should_play"));assertEquals(51250,state.getLong("position_ms"));
        }
    }

    @Test public void closingPipReleasesItsPlayerAndCancelsOldRemoteActions() throws Exception {
        try(Session session=open(null,null)){
            enterPip(session);session.pause();PendingIntent old=action(session,"暂停").getActionIntent();
            session.stop();assertNull(field(session.activity,"player",Object.class));assertEquals(1,session.clock.releases);
            try{old.send();fail("The old window must not keep a live command");}catch(PendingIntent.CanceledException expected){}
        }
    }

    @Test public void pipWatchdogStillPausesAndSystemRetryPreservesPositionAndPlayIntent() throws Exception {
        try(Session session=open(null,null)){
            session.clock.base.position=51250;set(session.activity,"firstFrameRendered",false);enterPip(session);session.pause();
            field(session.activity,"videoStartupCheck",Runnable.class).run();
            assertFalse(session.clock.base.play);assertTrue(field(session.activity,"playbackFailed",Boolean.class));
            assertEquals(View.GONE,field(session.activity,"errorPanel",View.class).getVisibility());
            assertTrue(field(session.activity,"status",TextView.class).getText().toString().contains("视频画面未能启动"));
            CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
            field(session.activity,"io",ExecutorService.class).execute(()->{blocked.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            assertTrue(blocked.await(8,TimeUnit.SECONDS));
            try{action(session,"重试播放").getActionIntent().send();idle();assertTrue(field(session.activity,"shouldPlay",Boolean.class));assertEquals(51250,field(session.activity,"positionMs",Long.class).longValue());set(session.activity,"started",false);}
            finally{release.countDown();}
            await(session.activity);
        }
    }

    @Test public void aPipPauseCancelsAnAutomaticNextThatHasNotTakenEffect() throws Exception {
        seed("next","系列 · 下一条",1000);
        try(Session session=open(null,null)){
            more(session);click(session,"播放模式 · 单次播放");click(session,"连续播放");session.activity.onBackPressed();enterPip(session);session.pause();
            PendingIntent pause=action(session,"暂停").getActionIntent();CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
            field(session.activity,"io",ExecutorService.class).execute(()->{blocked.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(blocked.await(8,TimeUnit.SECONDS));
            try{session.clock.base.position=100000;session.clock.base.playbackState=Player.STATE_ENDED;session.clock.base.play=true;invoke(session.activity,"playbackEnded",new Class<?>[0]);pause.send();idle();}
            finally{release.countDown();}
            await(session.activity);await(session.activity);
            assertEquals(videoId,field(session.activity,"videoId",Long.class).longValue());assertFalse(session.clock.base.play);assertFalse(field(session.activity,"shouldPlay",Boolean.class));
        }
    }

    @Test public void pipContinuousPlaybackKeepsLibraryFiltersEvenAfterCurrentBecomesWatched() throws Exception {
        long watched=seed("watched","系列 · 已看完",9000),notFavorite=seed("not-favorite","系列 · 未收藏",8000),target=seed("target","系列 · 下一条",7000);
        try(LibraryStore store=new LibraryStore(app)){store.setFavorite(videoId,true);store.setFavorite(watched,true);store.savePlayback(watched,100000);store.setFavorite(target,true);}
        Intent context=new Intent(app,PlayerActivity.class).putExtra("video_id",videoId).putExtra("library_query","系列").putExtra("library_watch_filter","unwatched").putExtra("library_favorites_only",true).putExtra("library_order","date");
        try(Session session=open(null,context)){
            more(session);click(session,"播放模式 · 单次播放");click(session,"连续播放");session.activity.onBackPressed();enterPip(session);session.pause();
            session.clock.base.position=100000;session.clock.base.playbackState=Player.STATE_ENDED;session.clock.base.play=true;
            invoke(session.activity,"playbackEnded",new Class<?>[0]);await(session.activity);await(session.activity);
            assertEquals("Only the next eligible item may become current",target,field(session.activity,"videoId",Long.class).longValue());
            try(LibraryStore store=new LibraryStore(app)){
                assertEquals(100000,store.video(videoId).optLong("playbackMs"));assertEquals(0,store.video(target).optLong("playbackMs"));assertEquals(0,store.video(notFavorite).optLong("playbackMs"));
            }
        }
    }

    @Test public void endOfSingleOrLastFilteredItemStopsWithoutWrappingAndBackgroundCancelsAdvance() throws Exception {
        try(Session session=open(null,null)){
            session.clock.base.position=100000;session.clock.base.playbackState=Player.STATE_ENDED;session.clock.base.play=true;
            invoke(session.activity,"playbackEnded",new Class<?>[0]);await(session.activity);assertEquals(videoId,field(session.activity,"videoId",Long.class).longValue());assertFalse(session.clock.base.play);
            more(session);click(session,"播放模式 · 单次播放");click(session,"连续播放");session.clock.base.play=true;
            invoke(session.activity,"playbackEnded",new Class<?>[0]);await(session.activity);await(session.activity);assertEquals(videoId,field(session.activity,"videoId",Long.class).longValue());assertFalse(field(session.activity,"shouldPlay",Boolean.class));
            assertTrue(field(session.activity,"gestureHint",TextView.class).getText().toString().contains("最后一个"));
            seed("later","系列 · 可切换",1000);session.clock.base.play=true;
            invoke(session.activity,"playbackEnded",new Class<?>[0]);session.pause();await(session.activity);assertEquals(videoId,field(session.activity,"videoId",Long.class).longValue());assertNull(field(session.activity,"player",Object.class));
        }
    }

    @Test @Config(sdk=25) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void devicesBeforePipHaveNoEntryAndKeepOrdinaryBackgroundRelease() throws Exception {
        try(Session session=open(null,null)){
            more(session);assertNull(find(field(session.activity,"menuPanel",View.class),"画中画"));
            session.clock.base.play=false;session.pause();assertNull(field(session.activity,"player",Object.class));assertEquals(1,session.clock.releases);
            Bundle state=new Bundle();session.activity.onSaveInstanceState(state);assertFalse(state.getBoolean("should_play"));
        }
    }

    private long seed(String id,String title,long completed) throws Exception {
        File source=new File(app.getCacheDir(),"player050-"+id+".mp4");try(FileOutputStream output=new FileOutputStream(source)){output.write("Clock fixture; no valid encoded frames".getBytes(StandardCharsets.UTF_8));}
        try(LibraryStore store=new LibraryStore(app)){return store.upsertCompletedDownload(new JSONObject().put("id",id).put("status","completed").put("title",title).put("uri",Uri.fromFile(source).toString()).put("completedAt",completed).put("mime","video/mp4").put("media",new JSONObject().put("duration",100)));}
    }
    private Session open(Bundle saved,Intent intent) throws Exception {
        ActivityController<PlayerActivity> controller=Robolectric.buildActivity(PlayerActivity.class,intent==null?new Intent(app,PlayerActivity.class).putExtra("video_id",videoId):intent);
        PlayerActivity activity=controller.create(saved).get();await(activity);Clock clock=new Clock();if(saved!=null)clock.base.position=field(activity,"positionMs",Long.class);
        ExoPlayer proxy=clock.proxy();set(activity,"player",proxy);set(activity,"firstFrameRendered",true);field(activity,"playerView",PlayerView.class).setPlayer(proxy);
        invoke(activity,"setMediaActions",new Class<?>[]{boolean.class},true);field(activity,"errorPanel",View.class).setVisibility(View.GONE);
        TextView provenance=new TextView(activity);provenance.setText("原生控件夹具 · 不含视频解码或系统小窗验证");provenance.setTextSize(11);provenance.setTextColor(0xFF8C979B);provenance.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams label=new FrameLayout.LayoutParams(-1,48,Gravity.CENTER);field(activity,"root",FrameLayout.class).addView(provenance,label);
        controller.start().resume().visible();idle();layout(activity);return new Session(controller,activity,clock);
    }
    private void enterPip(Session session) throws Exception {more(session);click(session,"画中画");assertTrue(session.activity.isInPictureInPictureMode());session.activity.onPictureInPictureModeChanged(true,session.activity.getResources().getConfiguration());}
    private static void more(Session session){session.activity.findViewById(R.id.sp_player_more).performClick();idle();}
    private static void click(Session session,String label) throws Exception {assertTrue(button(session,label).performClick());idle();}
    private static Button button(Session session,String label) throws Exception {TextView found=find(field(session.activity,"menuPanel",View.class),label);assertTrue("Missing real menu button: "+label,found instanceof Button);return (Button)found;}
    private static Button buttonContaining(Session session,String text) throws Exception {for(Button button:allButtons(field(session.activity,"menuPanel",View.class)))if(button.getText().toString().contains(text))return button;throw new AssertionError("Missing track button: "+text);}
    private static List<Button> buttons(Session session,String text) throws Exception {List<Button> result=new ArrayList<>();for(Button button:allButtons(field(session.activity,"menuPanel",View.class)))if(text.contentEquals(button.getText()))result.add(button);return result;}
    private static int selectedButtons(View view){int count=0;for(Button button:allButtons(view))if(button.isSelected())count++;return count;}
    private static List<Button> allButtons(View view){List<Button> result=new ArrayList<>();if(view instanceof Button)result.add((Button)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)result.addAll(allButtons(((ViewGroup)view).getChildAt(i)));return result;}
    private static TextView find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=find(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    private static RemoteAction action(Session session,String title) throws Exception {PictureInPictureParams parameters=(PictureInPictureParams)invoke(session.activity,"pipParams",new Class<?>[0]);for(RemoteAction action:parameters.getActions())if(title.contentEquals(action.getTitle()))return action;throw new AssertionError("Missing PiP action: "+title);}
    private static void await(Object owner) throws Exception {ExecutorService io=field(owner,"io",ExecutorService.class);io.submit(()->{}).get(8,TimeUnit.SECONDS);idle();io.submit(()->{}).get(8,TimeUnit.SECONDS);idle();}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static <T>T field(Object owner,String name,Class<T> type) throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(owner));}
    private static void set(Object owner,String name,Object value) throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
    private static Object invoke(Object owner,String name,Class<?>[] types,Object... values) throws Exception {Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,values);}
    private static void layout(PlayerActivity activity){View view=activity.getWindow().getDecorView();float density=activity.getResources().getDisplayMetrics().density;view.measure(View.MeasureSpec.makeMeasureSpec((int)(412*density),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((int)(915*density),View.MeasureSpec.EXACTLY));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static void capture(PlayerActivity activity,String name) throws Exception {layout(activity);View view=activity.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File root=new File(System.getProperty("user.dir"));while(root.getParentFile()!=null&&!new File(root,"settings.gradle").isFile())root=root.getParentFile();File output=new File(root,"app/build/reports/view-fixtures/player050");assertTrue(output.isDirectory()||output.mkdirs());try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}bitmap.recycle();}
    private static Tracks sampleTracks(){
        List<Tracks.Group> groups=new ArrayList<>();
        groups.add(new Tracks.Group(new TrackGroup("video",new Format.Builder().setSampleMimeType("video/avc").setWidth(1920).setHeight(1080).build()),false,new int[]{C.FORMAT_HANDLED},new boolean[]{true}));
        groups.add(new Tracks.Group(new TrackGroup("audio",new Format.Builder().setSampleMimeType("audio/mp4a-latm").setLabel("原声").setChannelCount(2).build(),new Format.Builder().setSampleMimeType("audio/mp4a-latm").setLabel("解说").setChannelCount(2).build()),false,new int[]{C.FORMAT_HANDLED,C.FORMAT_HANDLED},new boolean[]{true,false}));
        groups.add(new Tracks.Group(new TrackGroup("unsupported",new Format.Builder().setSampleMimeType("audio/ac3").setLabel("特殊音轨").build()),false,new int[]{C.FORMAT_UNSUPPORTED_TYPE},new boolean[]{false}));
        groups.add(new Tracks.Group(new TrackGroup("text",new Format.Builder().setSampleMimeType("text/vtt").setLabel("中文字幕").build(),new Format.Builder().setSampleMimeType("text/vtt").setLabel("英文字幕").build()),false,new int[]{C.FORMAT_HANDLED,C.FORMAT_HANDLED},new boolean[]{true,false}));return new Tracks(groups);
    }
    private static final class Clock implements InvocationHandler {
        final Player022UiTest.Clock base=new Player022UiTest.Clock();Tracks tracks=Tracks.EMPTY;TrackSelectionParameters parameters=TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT;int repeatMode,releases;
        ExoPlayer proxy(){return (ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class<?>[]{ExoPlayer.class},this);}
        @Override public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
            switch(method.getName()){
                case "getCurrentTracks":return tracks;
                case "getTrackSelectionParameters":return parameters;
                case "setTrackSelectionParameters":parameters=(TrackSelectionParameters)args[0];applySelection();return null;
                case "setRepeatMode":repeatMode=(Integer)args[0];return null;
                case "getRepeatMode":return repeatMode;
                case "isPlaying":return base.play&&base.playbackState==Player.STATE_READY;
                case "play":base.play=true;return null;
                case "release":releases++;return null;
                default:return base.invoke(proxy,method,args);
            }
        }
        private void applySelection(){List<Tracks.Group> next=new ArrayList<>();for(Tracks.Group group:tracks.getGroups()){boolean[] selected=new boolean[group.length];int[] support=new int[group.length];TrackSelectionOverride override=parameters.overrides.get(group.getMediaTrackGroup());for(int i=0;i<group.length;i++){support[i]=group.getTrackSupport(i);selected[i]=!parameters.disabledTrackTypes.contains(group.getType())&&(override==null?group.isTrackSelected(i):override.trackIndices.contains(i));}next.add(new Tracks.Group(group.getMediaTrackGroup(),false,support,selected));}tracks=new Tracks(next);}
    }
    private static final class Session implements AutoCloseable {
        final ActivityController<PlayerActivity> controller;final PlayerActivity activity;final Clock clock;boolean paused,stopped;
        Session(ActivityController<PlayerActivity> controller,PlayerActivity activity,Clock clock){this.controller=controller;this.activity=activity;this.clock=clock;}
        void pause(){if(!paused){controller.pause();paused=true;}}
        void resume(){controller.resume();paused=false;}
        void stop(){pause();if(!stopped){controller.stop();stopped=true;}}
        @Override public void close(){stop();controller.destroy();idle();}
    }
}
