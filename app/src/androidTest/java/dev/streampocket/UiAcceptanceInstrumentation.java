package dev.streampocket;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.GridView;
import android.widget.ImageView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** QA APK only. Real licensed media + real Android decoder. Never part of production APK. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class UiAcceptanceInstrumentation extends Instrumentation {
    private Bundle arguments;
    private File output;
    private String phase;
    @Override public void onCreate(Bundle args){arguments=args;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            phase=arguments.getString("scenario","after");Context app=getTargetContext();
            check(app.getPackageName().equals("dev.streampocket.localtest"),"QA must target only localtest");
            output=new File(app.getFilesDir(),"qa-captures/"+phase);check(output.isDirectory()||output.mkdirs(),"capture directory");
            File media=new File(app.getFilesDir(),"qa-media");check(media.isDirectory()||media.mkdirs(),"media directory");
            File wide=copyAsset(media,"bbb-licensed-20s.mp4"),tall=copyAsset(media,"bbb-portrait-crop-20s.mp4");
            MainActivity main=(MainActivity)startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            app.getSharedPreferences("config",0).edit().putString("appearance_theme","light").commit();
            JSONObject a=completed("qa-wide","Big Buck Bunny · 花园里的清晨，完整横幅与声音",wide,320,180,1);
            JSONObject b=completed("qa-tall","Big Buck Bunny · 竖屏构图测试（原片裁切衍生）",tall,180,320,2);
            app.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(a).put(b).toString()).commit();
            long wideId;try(LibraryStore store=new LibraryStore(app)){wideId=store.upsertCompletedDownload(a);store.upsertCompletedDownload(b);if(store.markers(wideId).isEmpty())store.addMarker(wideId,5000,"真实媒体验收标记");}
            onMain(()->invoke(main,"showTab",new Class<?>[]{String.class},"library"));
            waitUntil(()->{try{return field(main,"libraryView",VideoLibraryView.class)!=null&&field(field(main,"libraryView",VideoLibraryView.class),"grid",GridView.class).getCount()==2;}catch(Exception e){return false;}},30000,"two real media records");
            SystemClock.sleep(2500);capture("01-library-real-media");
            PlayerActivity player=openVideo(main,"qa-wide");awaitFrame(player);
            onMain(()->{ExoPlayer p=field(player,"player",ExoPlayer.class);p.pause();p.seekTo(5000);field(player,"playerView",PlayerView.class).showController();});SystemClock.sleep(1400);
            capture("02-player-real-frame");
            check(onMainValue(()->field(player,"firstFrameRendered",Boolean.class)),"real Android first frame callback");
            if("upgrade050".equals(phase)) verifyPictureInPicture(player,wideId);
            if(!"before".equals(phase)){
                onMain(()->field(player,"playerView",PlayerView.class).showController());waitForIdleSync();
                View timeBar=onMainValue(()->field(player,"timeBarView",View.class));int[] at=location(timeBar);float x=at[0]+timeBar.getWidth()*.35f,y=at[1]+timeBar.getHeight()/2f;long down=SystemClock.uptimeMillis();
                inject(MotionEvent.ACTION_DOWN,x,y,down);inject(MotionEvent.ACTION_MOVE,at[0]+timeBar.getWidth()*.65f,y,down);
                waitUntil(()->{try{return field(player,"previewImage",ImageView.class).getDrawable() instanceof BitmapDrawable;}catch(Exception e){return false;}},15000,"real retriever thumbnail during timebar drag");
                capture("03-real-scrub-thumbnail");inject(MotionEvent.ACTION_UP,at[0]+timeBar.getWidth()*.65f,y,down);SystemClock.sleep(300);
            }
            onMain(()->invoke(player,"showSheet",new Class<?>[]{String.class},"info"));SystemClock.sleep(400);capture("04-info-sheet");
            if(!"before".equals(phase)){
                View sheet=onMainValue(()->field(player,"sheetBody",View.class));int[] at=location(sheet);float x=at[0]+sheet.getWidth()/2f,y=at[1]+20;long down=SystemClock.uptimeMillis();
                inject(MotionEvent.ACTION_DOWN,x,y,down);for(int n=1;n<=6;n++){inject(MotionEvent.ACTION_MOVE,x,y+n*15,down);SystemClock.sleep(30);}
                check(onMainValue(()->field(player,"sheetBody",View.class).getTranslationY())>40,"sheet follows finger before release");capture("05-sheet-mid-drag");
                inject(MotionEvent.ACTION_CANCEL,x,y+90,down);SystemClock.sleep(400);check(onMainValue(()->field(player,"sheetBody",View.class).getTranslationY())==0,"cancel returns sheet to open");
                onMain(player::onBackPressed);SystemClock.sleep(400);check(onMainValue(()->field(player,"sheetOverlay",View.class).getVisibility())==View.GONE,"Back closes sheet first");
            }else {onMain(player::onBackPressed);SystemClock.sleep(200);}
            onMain(player::onBackPressed);waitForIdleSync();
            if("upgrade050".equals(phase))waitUntil(()->{try{return main.hasWindowFocus()&&field(main,"libraryView",VideoLibraryView.class).isShown();}catch(Exception e){return false;}},10000,"Back actually returns focus to the visible video library");
            SystemClock.sleep(700);capture("06-returned-library");
            if("upgrade050".equals(phase))check(onMainValue(()->field(player,"player",ExoPlayer.class))==null,"leaving playback releases the real player");
            PlayerActivity portrait=openVideo(main,"qa-tall");awaitFrame(portrait);onMain(()->{ExoPlayer p=field(portrait,"player",ExoPlayer.class);p.pause();p.seekTo(5000);field(portrait,"playerView",PlayerView.class).showController();});SystemClock.sleep(1200);capture("07-portrait-real-frame");onMain(portrait::onBackPressed);SystemClock.sleep(500);
            JSONObject live=new JSONObject(a.toString()).put("id","qa-telemetry").put("title","队列界面样例 · 正在传输的测试片").put("status","downloading").put("phase","video").put("progress",38).put("stageProgress",38).put("downloadedBytes",39845888).put("totalBytes",104857600).put("speedBytesPerSecond",2.4*1048576).put("etaSeconds",26).put("transferMode","parallel-http").put("activeConnections",4).put("configuredConnections",4).put("detail","当前阶段 38% · 本阶段约 26 秒").put("media",new JSONObject().put("thumbnail",Uri.fromFile(wide)));
            JSONObject paused=new JSONObject(b.toString()).put("id","qa-paused").put("title","队列界面样例 · 已暂停，保留原断点").put("status","paused").put("downloadedBytes",6291456).put("totalBytes",104857600);
            app.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(live).put(paused).put(a).put(b).toString()).commit();onMain(()->invoke(main,"showTab",new Class<?>[]{String.class},"queue"));SystemClock.sleep(1000);capture("08-queue-labelled-telemetry-fixture");
            if(!"before".equals(phase)){
                View row=onMainValue(()->findType(main.getWindow().getDecorView(),SwipeActionRow.class));check(row!=null,"swipe row exists");int[] at=location(row);long down=SystemClock.uptimeMillis();float x=at[0]+row.getWidth()*.7f,y=at[1]+row.getHeight()/2f;
                inject(MotionEvent.ACTION_DOWN,x,y,down);for(int n=1;n<=8;n++){inject(MotionEvent.ACTION_MOVE,x-n*20,y,down);SystemClock.sleep(25);}inject(MotionEvent.ACTION_UP,x-160,y,down);SystemClock.sleep(300);capture("09-queue-revealed-action-not-executed");
                check("downloading".equals(TaskStore.get(app,"qa-telemetry").optString("status")),"reveal alone never executes pause");
            }
            onMain(()->invoke(main,"showTab",new Class<?>[]{String.class},"new"));
            Engine.Media parsed=new Engine.Media();parsed.url="https://peach.blender.org/";parsed.id="qa-inline";parsed.platform="合法测试媒体";parsed.title=a.optString("title");parsed.uploader="Blender Foundation";parsed.duration=20;parsed.width=320;parsed.height=180;parsed.thumbnail=Uri.fromFile(wide).toString();parsed.filesize=wide.length();parsed.qualities.add(new Engine.Quality("best","原片画质","best"));
            onMain(()->{field(main,"input",android.widget.EditText.class).setText(parsed.url);invoke(main,"showMedia",new Class<?>[]{java.util.List.class},java.util.Collections.singletonList(parsed));});SystemClock.sleep(1000);capture("10-inline-real-artwork-metadata-fixture");
            result.putString("stream","PASS: "+phase+"; real horizontal/portrait decode, first frame, library return"+("before".equals(phase)?"":"; real scrub bitmap, sheet drag/cancel/back, non-executing swipe")+("upgrade050".equals(phase)?"; system PiP retains playback, advances position, returns to fullscreen, releases on exit":"")+"; captures="+output.getAbsolutePath());finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("stream","FAIL at "+phase+": "+android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}
    }
    private void verifyPictureInPicture(PlayerActivity activity,long videoId)throws Exception{
        check(getTargetContext().getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE),"emulator supports system PiP");
        ExoPlayer original=onMainValue(()->field(activity,"player",ExoPlayer.class));
        onMain(()->{original.seekTo(2000);original.play();field(activity,"playerView",PlayerView.class).showController();activity.findViewById(R.id.sp_player_more).performClick();});
        waitForIdleSync();
        onMain(()->{View entry=findText(activity.getWindow().getDecorView(),"画中画");check(entry!=null&&entry.isShown(),"PiP has a real visible tool entry");check(entry.performClick(),"PiP entry invokes its action");});
        waitUntil(activity::isInPictureInPictureMode,15000,"system enters PiP");
        check(onMainValue(()->field(activity,"player",ExoPlayer.class))==original,"PiP onPause keeps the current player");
        long position=onMainValue(original::getCurrentPosition);
        waitUntil(()->original.getCurrentPosition()>position+500,10000,"real media position advances while in PiP");
        capture("02a-system-pip-real-playback");
        onMain(()->getTargetContext().startActivity(new Intent(getTargetContext(),PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("video_id",videoId)));
        waitUntil(()->!activity.isInPictureInPictureMode(),15000,"existing playback activity returns to fullscreen");
        awaitFrame(activity);
        check(onMainValue(()->field(activity,"videoId",Long.class))==videoId,"fullscreen return keeps the selected video");
        onMain(()->{ExoPlayer current=field(activity,"player",ExoPlayer.class);current.pause();current.seekTo(5000);field(activity,"playerView",PlayerView.class).showController();});
        SystemClock.sleep(700);capture("02b-pip-returned-fullscreen");
    }
    private PlayerActivity openVideo(MainActivity main,String taskId)throws Exception{
        long id;try(LibraryStore store=new LibraryStore(getTargetContext())){id=store.upsertCompletedDownload(TaskStore.get(getTargetContext(),taskId));}
        ActivityMonitor monitor=addMonitor(PlayerActivity.class.getName(),null,false);final long expected=id;
        onMain(()->{GridView grid=field(field(main,"libraryView",VideoLibraryView.class),"grid",GridView.class);for(int i=0;i<grid.getCount();i++){JSONObject entry=(JSONObject)grid.getAdapter().getItem(i);if(entry.optLong("id")==expected){grid.performItemClick(grid.getChildAt(i-grid.getFirstVisiblePosition()),i,grid.getAdapter().getItemId(i));return;}}throw new AssertionError("video missing from grid");});
        Activity opened=waitForMonitorWithTimeout(monitor,30000);removeMonitor(monitor);check(opened instanceof PlayerActivity,"player launched from real library cell");return (PlayerActivity)opened;
    }
    private void awaitFrame(PlayerActivity activity)throws Exception{waitUntil(()->{try{ExoPlayer p=field(activity,"player",ExoPlayer.class);return p!=null&&p.getPlaybackState()==Player.STATE_READY&&field(activity,"firstFrameRendered",Boolean.class);}catch(Exception e){return false;}},60000,"actual Media3 decoded first frame");}
    private File copyAsset(File dir,String name)throws Exception{File file=new File(dir,name);if(file.isFile())return file;try(InputStream in=getContext().getAssets().open(name);FileOutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}return file;}
    private static JSONObject completed(String id,String title,File file,int width,int height,int order)throws Exception{return new JSONObject().put("id",id).put("title",title).put("url","https://peach.blender.org/").put("status","completed").put("progress",100).put("uri",Uri.fromFile(file)).put("mime","video/mp4").put("size",file.length()).put("createdAt",1000+order).put("completedAt",1000+order).put("media",new JSONObject().put("duration",20).put("width",width).put("height",height));}
    private void capture(String name)throws Exception{waitForIdleSync();Bitmap screenshot=getUiAutomation().takeScreenshot();check(screenshot!=null,"native screenshot");try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
    private void inject(int action,float x,float y,long down){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);check(getUiAutomation().injectInputEvent(event,true),"touch injection");event.recycle();}
    private int[] location(View view){int[] at=new int[2];runOnMainSync(()->view.getLocationOnScreen(at));return at;}
    private void waitUntil(BooleanSupplier condition,long timeout,String message)throws Exception{long end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(onMainValue(condition::getAsBoolean))return;SystemClock.sleep(150);}throw new AssertionError("Timeout: "+message);}
    private interface Work{void run()throws Exception;}private interface Value<T>{T get()throws Exception;}
    private void onMain(Work work)throws Exception{onMainValue(()->{work.run();return null;});}
    private <T>T onMainValue(Value<T> work)throws Exception{AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{value.set(work.get());}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new Exception(error.get());return value.get();}
    private static <T>T field(Object o,String name,Class<T> type)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o));}
    private static void invoke(Object o,String name,Class<?>[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,args);}
    private static View findType(View v,Class<?> type){if(type.isInstance(v))return v;if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++){View found=findType(((android.view.ViewGroup)v).getChildAt(i),type);if(found!=null)return found;}return null;}
    private static View findText(View view,String label){if(view instanceof android.widget.Button&&label.contentEquals(((android.widget.Button)view).getText()))return view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){View found=findText(((android.view.ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
