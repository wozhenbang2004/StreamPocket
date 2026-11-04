package dev.streampocket;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

/** Exercises subtitle I/O, MediaItem wiring and menu behavior; it does not claim video decoding. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class SubtitlePlaybackTest {
    private static final String SRT="1\n00:00:01,000 --> 00:00:03,000\n测试字幕\n";
    private Context app;
    private final List<File> fixtures=new ArrayList<>();
    private ActivityController<PlayerActivity> controller;

    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("config",0).edit().clear().putBoolean("motion_enabled",false).commit();
    }
    @After public void teardown() throws Exception {
        if(controller!=null) {
            PlayerActivity activity=controller.get();set(activity,"player",null);
            ExecutorService io=(ExecutorService)field(activity,"io");controller.destroy();
            assertTrue(io.awaitTermination(8,TimeUnit.SECONDS));idle();
        }
        app.deleteDatabase("media-library.db");for(File file:fixtures)file.delete();
    }

    @Test public void supportedFilesUseCorrectMimeLanguageLabelAndOneDefault() throws Exception {
        JSONObject srt=record(7,file(".srt",SRT),"标题.zh-Hans.srt","text/plain","zh-Hans");
        JSONObject vtt=record(8,file(".vtt","WEBVTT\n\n00:01.000 --> 00:02.000\nHello\n"),"title.en.vtt","application/octet-stream","en").put("label","English captions");
        SubtitlePlayback.Result result=SubtitlePlayback.load(app,Arrays.asList(srt,vtt));
        assertEquals(2,result.tracks.size());assertEquals("",result.warning());
        MediaItem.SubtitleConfiguration first=result.tracks.get(0),second=result.tracks.get(1);
        assertEquals("sp-subtitle-7",first.id);assertEquals("application/x-subrip",first.mimeType);assertEquals("zh-Hans",first.language);
        assertFalse(first.label.isEmpty());assertEquals(C.SELECTION_FLAG_DEFAULT,first.selectionFlags);
        assertEquals("text/vtt",second.mimeType);assertEquals("English captions",second.label);assertEquals(0,second.selectionFlags);
    }

    @Test public void deniedMissingMalformedAndOversizeSidecarsAreSkippedIndividually() throws Exception {
        DeniedProvider denied=new DeniedProvider();android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="subtitle-denied";
        denied.attachInfo(app,info);ShadowContentResolver.registerProviderInternal("subtitle-denied",denied);
        JSONObject noPermission=new JSONObject().put("id",1).put("uri","content://subtitle-denied/file/1").put("name","denied.srt");
        JSONObject missing=record(2,new File(app.getCacheDir(),"missing-subtitle-unique.srt"),"missing.srt","text/plain","en");
        JSONObject html=record(3,file(".srt","<!doctype html><html>sign in</html>"),"login.srt","application/x-subrip","en");
        File large=file(".srt",SRT);try(RandomAccessFile out=new RandomAccessFile(large,"rw")){out.setLength(SubtitleTracks.MAX_BYTES+1);}
        JSONObject oversize=record(4,large,"large.srt","text/plain","en").put("preferred",true);
        JSONObject valid=record(5,file(".srt",SRT),"valid.srt","text/plain","en");
        SubtitlePlayback.Result result=SubtitlePlayback.load(app,Arrays.asList(noPermission,missing,html,oversize,valid));
        assertTrue("The permission fixture must reject a real provider file-open attempt",denied.opens>0);
        assertEquals(4,result.skipped);assertEquals(1,result.tracks.size());
        assertEquals("sp-subtitle-5",result.tracks.get(0).id);assertEquals(C.SELECTION_FLAG_DEFAULT,result.tracks.get(0).selectionFlags);
        assertTrue(result.warning().contains("4 份"));assertTrue(result.warning().contains("权限"));
    }

    @Test public void selectedPreferredLanguageWinsOverFilenameOrder() throws Exception {
        JSONObject english=record(20,file(".srt",SRT),"clip.en.srt","text/plain","en");
        JSONObject chinese=record(21,file(".srt",SRT),"clip.zh-Hans.srt","text/plain","zh-Hans").put("preferred",true);
        SubtitlePlayback.Result result=SubtitlePlayback.load(app,Arrays.asList(english,chinese));
        assertEquals("zh-Hans",result.tracks.get(0).language);assertEquals(C.SELECTION_FLAG_DEFAULT,result.tracks.get(0).selectionFlags);
        assertEquals("en",result.tracks.get(1).language);assertEquals(0,result.tracks.get(1).selectionFlags);
    }

    @Test public void loadingNeverRequestsRemoteUrisOrNonSubtitleAttachments() throws Exception {
        JSONObject remote=new JSONObject().put("id",1).put("uri","https://example.invalid/remote.srt").put("name","remote.srt");
        JSONObject cover=record(2,file(".jpg",SRT),"cover.jpg","application/x-subrip","en");
        JSONObject lrc=record(3,file(".lrc","[00:01.00]歌词"),"song.lrc","text/plain","zh");
        SubtitlePlayback.Result result=SubtitlePlayback.load(app,Arrays.asList(remote,cover,lrc));
        assertTrue(result.tracks.isEmpty());assertEquals(3,result.skipped);
    }

    @Test public void activityLoadsOnlyItsLibrarySubtitlesAndBuildsTheActualMediaItem() throws Exception {
        long target=download("target","target.zh-Hans.srt"),other=download("other","other.en.srt");
        assertNotEquals(target,other);PlayerActivity activity=open(target);
        MediaItem item=(MediaItem)invoke(activity,"playbackItem",new Class<?>[0]);
        assertEquals(Long.toString(target),item.mediaId);assertNotNull(item.localConfiguration);
        assertEquals(1,item.localConfiguration.subtitleConfigurations.size());
        MediaItem.SubtitleConfiguration subtitle=item.localConfiguration.subtitleConfigurations.get(0);
        assertEquals("zh-Hans",subtitle.language);assertEquals(C.SELECTION_FLAG_DEFAULT,subtitle.selectionFlags);
        assertFalse("A different video with the same title must not contribute tracks",subtitle.language.equals("en"));
        assertEquals("",field(activity,"subtitleWarning"));
    }

    @Test public void trackMenuDistinguishesDownloadedAndEmbeddedSubtitlesAndHasAnHonestEmptyState() throws Exception {
        PlayerActivity activity=open(download("menu","menu.zh-Hans.srt"));
        MediaItem item=(MediaItem)invoke(activity,"playbackItem",new Class<?>[0]);
        String externalId=item.localConfiguration.subtitleConfigurations.get(0).id;
        PlayerStub stub=new PlayerStub(app);
        stub.tracks=new Tracks(Arrays.asList(group(externalId,"zh-Hans"),group("embedded","en")));set(activity,"player",stub.proxy());
        invoke(activity,"renderTrackChoices",new Class<?>[0]);View menu=(View)field(activity,"menuPanel");
        assertTrue(containsText(menu,"已下载字幕"));assertTrue(containsText(menu,"视频内嵌字幕"));
        assertTrue(containsText(menu,item.localConfiguration.subtitleConfigurations.get(0).label));
        stub.tracks=Tracks.EMPTY;invoke(activity,"renderTrackChoices",new Class<?>[0]);
        assertTrue(containsText(menu,"此视频没有可用的字幕。"));assertFalse(containsText(menu,"此视频没有内嵌字幕。"));
    }

    @Test public void confirmedSubtitleFailureResumesAtSamePositionAndKeepsSubtitlesOffPreference() throws Exception {
        app.getSharedPreferences("config",0).edit().putBoolean("player_subtitles_off",true).commit();
        PlayerActivity activity=open(download("recover","recover.en.srt","recover.zh-Hans.srt"));assertEquals(true,field(activity,"subtitlesOff"));
        PlayerStub stub=new PlayerStub(app);stub.parameters=stub.parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build();
        ExoPlayer player=stub.proxy();set(activity,"player",player);set(activity,"started",true);set(activity,"resumed",true);set(activity,"shouldPlay",false);
        MediaItem original=(MediaItem)invoke(activity,"playbackItem",new Class<?>[0]);Uri failed=original.localConfiguration.subtitleConfigurations.get(0).uri;
        IOException captionError=new IOException("subtitle permission revoked");
        @SuppressWarnings("unchecked") Map<Uri,IOException> failures=(Map<Uri,IOException>)field(activity,"subtitleLoadErrors");failures.put(failed,captionError);
        PlaybackException error=new PlaybackException("subtitle load",captionError,PlaybackException.ERROR_CODE_IO_NO_PERMISSION);
        assertEquals(true,invoke(activity,"skipFailedSubtitle",new Class<?>[]{ExoPlayer.class,PlaybackException.class},player,error));
        assertEquals(42_000,stub.resumeAt);assertFalse(stub.playWhenReady);assertEquals(1,stub.prepares);
        assertNotNull(stub.item);assertEquals(1,stub.item.localConfiguration.subtitleConfigurations.size());
        assertEquals("zh-Hans",stub.item.localConfiguration.subtitleConfigurations.get(0).language);
        assertEquals(C.SELECTION_FLAG_DEFAULT,stub.item.localConfiguration.subtitleConfigurations.get(0).selectionFlags);
        assertTrue(stub.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
        assertTrue(app.getSharedPreferences("config",0).getBoolean("player_subtitles_off",false));
        assertTrue(((String)field(activity,"subtitleWarning")).contains("已跳过"));
    }

    @Test public void mainVideoFailureIsNotHiddenByAnEarlierSubtitleFailure() throws Exception {
        PlayerActivity activity=open(download("video-error","video-error.en.srt"));PlayerStub stub=new PlayerStub(app);ExoPlayer player=stub.proxy();set(activity,"player",player);
        MediaItem original=(MediaItem)invoke(activity,"playbackItem",new Class<?>[0]);
        @SuppressWarnings("unchecked") Map<Uri,IOException> failures=(Map<Uri,IOException>)field(activity,"subtitleLoadErrors");
        failures.put(original.localConfiguration.subtitleConfigurations.get(0).uri,new IOException("file missing"));
        PlaybackException error=new PlaybackException("video load",new IOException("file missing"),PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND);
        assertEquals(false,invoke(activity,"skipFailedSubtitle",new Class<?>[]{ExoPlayer.class,PlaybackException.class},player,error));
        assertEquals(0,stub.prepares);assertNull(stub.item);
    }

    private long download(String taskId,String... subtitleNames) throws Exception {
        File video=file(".mp4","metadata fixture, not decoded video");JSONArray attachments=new JSONArray();
        for(String name:subtitleNames)attachments.put(new JSONObject().put("uri",Uri.fromFile(file(".srt",SRT)).toString()).put("name",name).put("mime","text/plain"));
        JSONObject task=new JSONObject().put("id",taskId).put("status","completed").put("uri",Uri.fromFile(video).toString())
                .put("title","相同标题").put("mime","video/mp4").put("url","https://x.com/fixture/status/123").put("size",10)
                .put("media",new JSONObject().put("duration",60))
                .put("attachments",attachments);
        try(LibraryStore store=new LibraryStore(app)){return store.upsertCompletedDownload(task);}
    }
    private PlayerActivity open(long videoId) throws Exception {
        controller=Robolectric.buildActivity(PlayerActivity.class,new Intent(app,PlayerActivity.class).putExtra("video_id",videoId));
        PlayerActivity activity=controller.create().get();ExecutorService io=(ExecutorService)field(activity,"io");
        io.submit(()->{}).get(8,TimeUnit.SECONDS);idle();io.submit(()->{}).get(8,TimeUnit.SECONDS);idle();return activity;
    }
    private File file(String extension,String content) throws Exception {
        File file=File.createTempFile("subtitle-player-",extension,app.getCacheDir());Files.write(file.toPath(),content.getBytes(StandardCharsets.UTF_8));fixtures.add(file);return file;
    }
    private JSONObject record(long id,File file,String name,String mime,String language) throws Exception {
        return new JSONObject().put("id",id).put("uri",Uri.fromFile(file).toString()).put("name",name).put("mime",mime).put("language",language);
    }
    private static Tracks.Group group(String id,String language) {
        return new Tracks.Group(new TrackGroup(id,new Format.Builder().setId(id).setSampleMimeType("application/x-subrip").setLanguage(language).build()),false,new int[]{C.FORMAT_HANDLED},new boolean[]{false});
    }
    private static boolean containsText(View view,String value) {
        if(view instanceof TextView && ((TextView)view).getText().toString().equals(value))return true;
        if(view instanceof ViewGroup)for(int index=0;index<((ViewGroup)view).getChildCount();index++)if(containsText(((ViewGroup)view).getChildAt(index),value))return true;
        return false;
    }
    private static Object field(Object owner,String name) throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void set(Object owner,String name,Object value) throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
    private static Object invoke(Object owner,String name,Class<?>[] types,Object... values) throws Exception {Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,values);}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}

    private static final class PlayerStub {
        TrackSelectionParameters parameters;Tracks tracks=Tracks.EMPTY;MediaItem item;long resumeAt;boolean playWhenReady;int prepares;
        PlayerStub(Context context){parameters=new TrackSelectionParameters.Builder(context).build();}
        ExoPlayer proxy(){return (ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class<?>[]{ExoPlayer.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "getCurrentTracks":return tracks;
                case "getTrackSelectionParameters":return parameters;
                case "setTrackSelectionParameters":parameters=(TrackSelectionParameters)args[0];return null;
                case "getCurrentPosition":return 42_000L;
                case "getPlaybackState":return Player.STATE_READY;
                case "setMediaItem":item=(MediaItem)args[0];resumeAt=(Long)args[1];return null;
                case "prepare":prepares++;return null;
                case "setPlayWhenReady":playWhenReady=(Boolean)args[0];return null;
                default:Class<?> type=method.getReturnType();if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;return null;
            }
        });}
    }
    private static final class DeniedProvider extends ContentProvider {
        int opens;
        @Override public boolean onCreate(){return true;}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode){opens++;throw new SecurityException("fixture permission denied");}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){return null;}
        @Override public String getType(Uri uri){return "application/x-subrip";}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    }
}
