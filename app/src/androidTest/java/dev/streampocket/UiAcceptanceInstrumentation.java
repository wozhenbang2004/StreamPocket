package dev.streampocket;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
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
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
            if("startup051".equals(phase)){
                verifyStartupUpdates();
                result.putString("stream","PASS: startup051; real Activity navigation and HOME/reopen; failed check retries on next opening; internal navigation does not repeat; real switch disables/enables checks; metadata is an offline fixture, no APK download or installation; captures="+output.getAbsolutePath());
                finish(Activity.RESULT_OK,result);return;
            }
            if("directfile".equals(phase)){
                verifyDirectFile();
                result.putString("stream","PASS: directfile; real HTTPS Engine.inspectFile, bundled native aria2 download, pinned bytes/SHA-256, completed-cache reuse with a new process ID, public Downloads save and independent read-back SHA-256; real Download file button through archive check, TaskStore, foreground DownloadService, native transfer, public save and matching archive record; dedicated output cleanup; real ordinary-file, preset-editor and completed-queue screens; licensed Big Buck Bunny QA media; captures="+output.getAbsolutePath());
                finish(Activity.RESULT_OK,result);return;
            }
            File media=new File(app.getFilesDir(),"qa-media");check(media.isDirectory()||media.mkdirs(),"media directory");
            File wide=copyAsset(media,"bbb-licensed-20s.mp4"),tall=copyAsset(media,"bbb-portrait-crop-20s.mp4");
            MainActivity main=(MainActivity)startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            app.getSharedPreferences("config",0).edit().putString("appearance_theme","light").commit();
            JSONObject a=completed("qa-wide","Big Buck Bunny · 花园里的清晨，完整横幅与声音",wide,320,180,"upgrade050".equals(phase)?2:1);
            JSONObject b=completed("qa-tall","Big Buck Bunny · 竖屏构图测试（原片裁切衍生）",tall,180,320,"upgrade050".equals(phase)?1:2);
            app.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(a).put(b).toString()).commit();
            long wideId,tallId;try(LibraryStore store=new LibraryStore(app)){wideId=store.upsertCompletedDownload(a);tallId=store.upsertCompletedDownload(b);if(store.markers(wideId).isEmpty())store.addMarker(wideId,5000,"真实媒体验收标记");}
            onMain(()->invoke(main,"showTab",new Class<?>[]{String.class},"library"));
            waitUntil(()->{try{return field(main,"libraryView",VideoLibraryView.class)!=null&&field(field(main,"libraryView",VideoLibraryView.class),"grid",GridView.class).getCount()==2;}catch(Exception e){return false;}},30000,"two real media records");
            SystemClock.sleep(2500);capture("01-library-real-media");
            PlayerActivity player=openVideo(main,"qa-wide");awaitFrame(player);
            onMain(()->{ExoPlayer p=field(player,"player",ExoPlayer.class);p.pause();p.seekTo(5000);field(player,"playerView",PlayerView.class).showController();});SystemClock.sleep(1400);
            capture("02-player-real-frame");
            check(onMainValue(()->field(player,"firstFrameRendered",Boolean.class)),"real Android first frame callback");
            if("upgrade050".equals(phase)) verifyPictureInPicture(player,main,wideId,tallId);
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
            result.putString("stream","PASS: "+phase+"; real horizontal/portrait decode, first frame, library return"+("before".equals(phase)?"":"; real scrub bitmap, sheet drag/cancel/back, non-executing swipe")+("upgrade050".equals(phase)?"; system PiP retains playback, next media keeps playback and audio focus, returns to fullscreen, releases on exit":"")+"; captures="+output.getAbsolutePath());finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("stream","FAIL at "+phase+": "+failureStack(failure));finish(Activity.RESULT_CANCELED,result);}
    }
    /** No network fixture: production inspection, native transfer, cache validation and publication APIs. */
    private void verifyDirectFile()throws Exception{
        Context app=getTargetContext().getApplicationContext();
        String source="https://raw.githubusercontent.com/wozhenbang2004/StreamPocket/e38088e44d88a8a35b787445abac6e177bf176b8/test-media/bbb-licensed-20s.mp4";
        String expectedSha="e6fa30bb74073423c83b4cd9b88bd6630a31e69cb19ed6dbb9f8ff50328f3a28";
        long expectedBytes=2216913L,deadline=SystemClock.elapsedRealtime()+160000;
        BooleanSupplier expired=()->SystemClock.elapsedRealtime()>=deadline;
        String runId=java.util.UUID.randomUUID().toString();
        File work=new File(new File(app.getCacheDir(),"qa-directfile"),runId);
        check(work.mkdirs(),"fresh isolated native download directory");
        JSONObject evidence=new JSONObject().put("source",source).put("expectedBytes",expectedBytes).put("expectedSha256",expectedSha)
                .put("network","Real HTTPS requests; no inspection, transport or native-process fixture")
                .put("license","Big Buck Bunny QA excerpt; (c) 2008 Blender Foundation; Creative Commons Attribution 3.0")
                .put("licenseSource","https://peach.blender.org/about/")
                .put("supportedAbis",new JSONArray(java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS)))
                .put("uiScope","Real production ordinary-file screen with network-inspected metadata; unsaved labelled QA preset in the real editor");
        Uri saved=null;MainActivity main=null;boolean verified=false;
        AtomicReference<String> serviceTaskId=new AtomicReference<>();
        java.util.Set<String> tasksBeforeClick=new java.util.HashSet<>();
        android.content.SharedPreferences updatePreferences=app.getSharedPreferences("app_updates",0);
        boolean hadAutomatic=updatePreferences.contains("automatic"),automaticBefore=updatePreferences.getBoolean("automatic",true);
        boolean updatePreferenceTouched=false;
        try{
            check(java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("x86_64"),"directfile scenario requires the isolated CI x86_64 APK/emulator");
            updatePreferenceTouched=true;
            check(updatePreferences.edit().putBoolean("automatic",false).commit(),"disable only unrelated automatic update checks during this QA scenario");
            app.getSharedPreferences("config",0).edit().putString("appearance_theme","light").commit();
            main=(MainActivity)startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity screen=main;
            waitUntil(()->{
                try{return screen.hasWindowFocus()&&screen.getWindow().getDecorView().isShown()
                        &&screen.getWindow().getDecorView().getWindowVisibility()==View.VISIBLE
                        &&field(screen,"foreground",Boolean.class)&&field(app,"startedActivities",Integer.class)>0;}
                catch(Exception failure){throw new IllegalStateException("Cannot verify the actual foreground Activity lifecycle",failure);}
            },10000,"real resumed and visible MainActivity has window focus before the first network request");
            onMain(()->{
                Field mode=MainActivity.class.getDeclaredField("fileIntake");mode.setAccessible(true);mode.setBoolean(screen,true);
                invoke(screen,"showTab",new Class<?>[]{String.class},"new");
                field(screen,"input",android.widget.EditText.class).setText("合法 QA 媒体 · Big Buck Bunny / CC BY 3.0\n"+source);
            });
            check(onMainValue(()->field(screen,"input",android.widget.EditText.class).getContentDescription().toString().startsWith("文件链接")),
                    "ordinary-file input is the real production mode before any inspection");
            android.app.ActivityManager.RunningAppProcessInfo processState=new android.app.ActivityManager.RunningAppProcessInfo();
            android.app.ActivityManager.getMyMemoryState(processState);
            evidence.put("foregroundLifecycle",onMainValue(()->new JSONObject().put("beforeFirstInspection",true)
                    .put("hasWindowFocus",screen.hasWindowFocus()).put("decorVisible",screen.getWindow().getDecorView().isShown())
                    .put("resumed",field(screen,"foreground",Boolean.class)).put("startedActivities",field(app,"startedActivities",Integer.class))
                    .put("processImportance",processState.importance).put("automaticUpdateChecksDisabledForQa",true)));
            File packages=new File(app.getNoBackupFilesDir(),"youtubedl-android/packages");
            check(!new File(packages,"python").exists()&&!new File(packages,"aria2c").exists()&&!new File(packages,"ffmpeg").exists(),
                    "visible MainActivity has not pre-extracted Python/aria2/FFmpeg; first file download must initialize its own dependencies");
            evidence.put("freshFileFirstRuntime",true).put("runtimeBeforeFirstTransfer",new JSONObject()
                    .put("pythonExtracted",new File(packages,"python").exists()).put("aria2Extracted",new File(packages,"aria2c").exists())
                    .put("ffmpegExtracted",new File(packages,"ffmpeg").exists()));
            writeDirectFileEvidence(evidence);SystemClock.sleep(500);capture("01-ordinary-file-mode-licensed-qa-link");
            Engine.Media media=onMainValue(()->field(screen,"engine",Engine.class)).inspectFile(source,expired);
            check(media.directFile()&&media.file!=null,"real Engine inspection selects ordinary-file mode");
            check(source.equals(media.url)&&source.equals(media.file.url),"the selected file retains the pinned source URL");
            check("bbb-licensed-20s.mp4".equals(media.file.name),"the real response resolves the expected file name");
            check(media.file.size==expectedBytes,"real response size matches the pinned licensed file");
            evidence.put("inspection",media.file.toJson());

            AtomicInteger nativeStarts=new AtomicInteger();
            File downloaded=DirectFileDownloader.download(app,source,media.file,work,"qa-directfile-"+runId,expired,patch->{
                if("正在下载文件".equals(patch.optString("stageLabel")))nativeStarts.incrementAndGet();
            });
            check(nativeStarts.get()>0,"first transfer reaches the real native-process execution path");
            evidence.put("nativeAria2Version",nativeAria2Version(app));
            FileDigest transfer=readDigest(new java.io.FileInputStream(downloaded));
            check(transfer.bytes==expectedBytes&&expectedSha.equals(transfer.sha256),"native output matches the pinned complete file bytes and SHA-256");
            File cache=new File(work,"direct-http"),marker=new File(cache,"complete.json");
            check(marker.isFile()&&!new File(cache,"payload.aria2").exists(),"native completion is confirmed and journaled without a remaining aria2 control file");
            long lastModified=downloaded.lastModified();AtomicInteger cacheCallbacks=new AtomicInteger();
            File reused=DirectFileDownloader.download(app,source,media.file,work,"qa-directfile-cache-"+runId,expired,patch->cacheCallbacks.incrementAndGet());
            check(downloaded.getCanonicalFile().equals(reused.getCanonicalFile())&&reused.lastModified()==lastModified,
                    "a second process identity reuses the same verified completed payload");
            check(cacheCallbacks.get()==0,"completed-cache reuse returns before any new probe or native transfer");
            evidence.put("nativeDownload",new JSONObject().put("bytes",transfer.bytes).put("sha256",transfer.sha256).put("nativeStartEvents",nativeStarts.get()))
                    .put("cacheReuse",new JSONObject().put("newProcessId",true).put("samePayload",true).put("noNewTransferCallbacks",true));

            String publicName="StreamPocket-QA-"+runId+".mp4";
            MediaExporter.Result published=MediaExporter.saveFile(app,reused,publicName,media.file.mime,expired,
                    new DownloadLocation.Target(null,DownloadLocation.DEFAULT_LABEL));
            saved=published.uri;
            FileDigest publicRead=readDigest(app.getContentResolver().openInputStream(saved));
            check(publicRead.bytes==expectedBytes&&expectedSha.equals(publicRead.sha256),"public Downloads URI independently reads back the pinned bytes and SHA-256");
            check(published.bytes==expectedBytes&&expectedSha.equals(published.sha256),"publication receipt agrees with the independent public read-back");
            JSONObject publicMetadata=publicFileMetadata(app,saved);
            String expectedPath=android.os.Environment.DIRECTORY_DOWNLOADS+"/StreamPocket";
            check(publicName.equals(publicMetadata.getString("name")),"public file keeps its dedicated QA output name");
            check(published.mime.equals(publicMetadata.getString("mime")),"published MIME matches the actual public MediaStore row");
            check(publicMetadata.getLong("bytes")==expectedBytes&&publicMetadata.getInt("pending")==0,"saved file is published, complete and not pending");
            check(expectedPath.equals(publicMetadata.getString("relativePath"))||(expectedPath+"/").equals(publicMetadata.getString("relativePath")),
                    "ordinary file is actually published in default Downloads/StreamPocket");
            evidence.put("publicOutput",publicMetadata.put("uri",saved.toString()).put("readBackSha256",publicRead.sha256));

            onMain(()->invoke(screen,"showMedia",new Class<?>[]{java.util.List.class},java.util.Collections.singletonList(media)));
            SystemClock.sleep(600);capture("02-real-network-inspected-file");
            DownloadPresets.Preset sample=new DownloadPresets.Preset("qa-directfile","合法 QA 媒体样例（未保存）","best",false,false,
                    SubtitleChoices.PREFER_ORIGINAL,java.util.Collections.emptyList(),NamingTemplate.DEFAULT);
            onMain(()->invoke(screen,"editPreset",new Class<?>[]{DownloadPresets.Preset.class},sample));
            waitUntil(()->activeWindowContains("文件命名模板"),10000,"the real preset editor opens with its template input");
            SystemClock.sleep(500);capture("03-real-preset-editor-unsaved-qa-sample");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            waitUntil(screen::hasWindowFocus,5000,"Back dismisses the unsaved preset editor");
            evidence.put("service",verifyServiceFile(screen,media,expectedBytes,expectedSha,deadline,serviceTaskId,tasksBeforeClick,evidence));
            check(!expired.getAsBoolean(),"directfile verification completes within the bounded scenario");
            verified=true;evidence.put("verified",true);
        }catch(Exception|AssertionError failure){
            evidence.put("verified",false).put("failure",failureStack(failure));throw failure;
        }finally{
            boolean serviceRemoved=true;
            try{
                // An empty pre-click set is normal on a fresh install. Only scan
                // after the real button was pressed, never guess a pre-existing task.
                if(serviceTaskId.get()==null&&evidence.optBoolean("serviceButtonClicked")){
                    MainActivity screen=main;
                    // Cancel only a still-pending archive check, so it cannot
                    // enqueue after this final scan has finished.
                    if(screen!=null)onMain(()->invoke(screen,"cancelArchiveCheck",new Class<?>[]{long.class},field(screen,"archiveGeneration",Long.class)));
                    serviceTaskId.set(findNewFileTask(app,tasksBeforeClick,source));
                }
                if(serviceTaskId.get()!=null){
                    evidence.put("serviceTaskId",serviceTaskId.get());writeDirectFileEvidence(evidence);
                    evidence.put("serviceCleanup",cleanupServiceFile(app,serviceTaskId.get(),source));
                }
            }catch(Exception|AssertionError cleanup){serviceRemoved=false;evidence.put("serviceCleanupError",failureStack(cleanup));}
            boolean publicRemoved=saved==null;
            if(saved!=null)try{publicRemoved=MediaExporter.delete(app,saved);}catch(Exception cleanup){evidence.put("publicCleanupError",cleanup.toString());}
            boolean cacheRemoved=deleteQaTree(work);
            evidence.put("dedicatedPublicOutputRemoved",publicRemoved).put("dedicatedNativeCacheRemoved",cacheRemoved);
            boolean uiClosed=true;MainActivity screen=main;
            if(screen!=null)try{onMain(()->{if(!screen.isFinishing())screen.finish();});}
            catch(Exception cleanup){uiClosed=false;evidence.put("uiCleanupError",cleanup.toString());}
            boolean updatesRestored=true;
            if(updatePreferenceTouched){
                android.content.SharedPreferences.Editor restore=updatePreferences.edit();
                if(hadAutomatic)restore.putBoolean("automatic",automaticBefore);else restore.remove("automatic");
                updatesRestored=restore.commit();
            }
            evidence.put("automaticUpdatePreferenceRestored",updatesRestored);
            evidence.put("passed",verified&&serviceRemoved&&publicRemoved&&cacheRemoved&&uiClosed&&updatesRestored);
            writeDirectFileEvidence(evidence);
            if(verified)check(serviceRemoved&&publicRemoved&&cacheRemoved&&uiClosed&&updatesRestored,"only this run's QA data is cleaned up after execution finishes, and the update preference is restored");
        }
    }
    /** Starts only by clicking the production row action; no Service entry point or worker is invoked here. */
    private JSONObject verifyServiceFile(MainActivity screen,Engine.Media inspected,long expectedBytes,String expectedSha,long deadline,
                                         AtomicReference<String> taskId,java.util.Set<String> previous,JSONObject report)throws Exception{
        Context app=getTargetContext().getApplicationContext();
        for(JSONObject task:TaskStore.all(app))previous.add(task.optString("id"));
        check(ContentArchiveStore.find(app,inspected,inspected.downloadChoices().get(0)).isEmpty(),
                "fresh service acceptance has no seeded archive record for this source");
        long budget=Math.min(60000,deadline-SystemClock.elapsedRealtime()-15000);
        check(budget>0,"the service path has bounded time remaining, including cleanup");
        long serviceDeadline=SystemClock.elapsedRealtime()+budget;
        onMain(()->{
            View download=findText(screen.getWindow().getDecorView(),"下载文件");
            check(download!=null&&download.isShown()&&download.isEnabled(),"the actual inspected-file row exposes an enabled Download file button");
            check(download.performClick(),"the real Download file button dispatches its production action");
        });
        report.put("serviceButtonClicked",true).put("serviceTaskIdsBeforeClick",new JSONArray(previous));writeDirectFileEvidence(report);
        waitUntil(()->{
            String found=findNewFileTask(app,previous,inspected.url);
            if(found!=null)taskId.compareAndSet(null,found);
            return taskId.get()!=null;
        },Math.max(1,serviceDeadline-SystemClock.elapsedRealtime()),"archive-check I/O leads to a genuinely created download task");
        report.put("serviceTaskId",taskId.get());writeDirectFileEvidence(report);
        java.util.Set<String> seenStates=new java.util.LinkedHashSet<>();
        waitUntil(()->{
            JSONObject task=TaskStore.get(app,taskId.get());
            check(task!=null,"the submitted service task remains present until verification");
            String status=task.optString("status");seenStates.add(status+":"+task.optString("phase"));
            if("failed".equals(status)||"cancelled".equals(status)||"interrupted".equals(status))
                throw new AssertionError("Real DownloadService did not complete: "+task);
            try{return "completed".equals(status)&&!task.optBoolean("publicationBusy")&&!serviceOwns(taskId.get());}
            catch(Exception failure){throw new IllegalStateException("Unable to confirm Service ownership release",failure);}
        },Math.max(1,serviceDeadline-SystemClock.elapsedRealtime()),"real foreground Service transfers, publishes, archives and releases the task");
        JSONObject task=TaskStore.get(app,taskId.get());
        check("direct-file".equals(task.optString("kind")),"TaskStore retains the submitted direct-file kind");
        JSONObject metadata=task.getJSONObject("media"),quality=task.getJSONObject("quality");
        check("direct-file".equals(metadata.optString("kind"))&&"original-file".equals(quality.optString("id")),
                "real Service task uses ordinary-file metadata and the original-file selection");
        check("direct-http".equals(task.optString("transferMode")),"the completed task reports the real direct-file transport");
        check(expectedBytes==task.optLong("size",-1)&&expectedSha.equals(task.optString("sha256")),"Service persists the verified complete file receipt");
        Uri uri=Uri.parse(task.getString("uri"));
        FileDigest actual=readDigest(app.getContentResolver().openInputStream(uri));
        check(actual.bytes==expectedBytes&&expectedSha.equals(actual.sha256),"Service-produced public URI independently matches the pinned bytes and SHA-256");
        JSONObject publicMetadata=publicFileMetadata(app,uri);
        check(inspected.file.name.equals(publicMetadata.getString("name"))&&inspected.file.name.equals(task.optString("title")),
                "Service preserves the inspected original file name");
        check(task.getString("mime").equals(publicMetadata.getString("mime"))&&"video/mp4".equals(publicMetadata.getString("mime")),
                "Service receipt and the public row preserve the file's resolved MP4 MIME");
        check(expectedBytes==publicMetadata.getLong("bytes")&&publicMetadata.getInt("pending")==0,"Service output is a complete published public row");
        JSONObject archive=null;
        for(JSONObject row:ContentArchiveStore.find(app,Engine.Media.fromJson(metadata),Engine.Quality.fromJson(quality)))
            if(taskId.get().equals(row.optString("taskId")))archive=row;
        check(archive!=null&&archive.optBoolean("sameVariant")&&"file".equals(archive.optString("variantKey")),
                "the production archive contains this same completed task and original-file variant");
        check(uri.toString().equals(archive.getString("uri"))&&expectedBytes==archive.getLong("size")
                &&task.getString("mime").equals(archive.getString("mime")),"archive receipt matches the actual Service result");
        onMain(()->{
            check("queue".equals(field(screen,"tab",String.class)),"the actual Download file action navigated to the queue");
            invoke(screen,"refreshQueue",new Class<?>[]{boolean.class},true);
        });
        SystemClock.sleep(500);capture("04-real-service-completed-queue-licensed-qa");
        return new JSONObject().put("taskId",taskId.get()).put("clickedProductionButton",true).put("kind",task.optString("kind"))
                .put("mediaKind",metadata.optString("kind")).put("quality",quality.optString("id"))
                .put("observedStates",new JSONArray(seenStates)).put("status",task.optString("status"))
                .put("transferMode",task.optString("transferMode")).put("publicationBusy",task.optBoolean("publicationBusy"))
                .put("nativeOwnershipReleased",true).put("bytes",actual.bytes).put("sha256",actual.sha256)
                .put("publicOutput",publicMetadata.put("uri",uri.toString())).put("archive",archive);
    }
    private static String findNewFileTask(Context app,java.util.Set<String> previous,String source){
        String found=null;
        for(JSONObject task:TaskStore.all(app)){
            if(previous.contains(task.optString("id"))||!source.equals(task.optString("url")))continue;
            JSONObject media=task.optJSONObject("media");
            if(media==null||!"direct-file".equals(media.optString("kind")))continue;
            check(found==null,"one production click must create exactly one matching file task");found=task.optString("id");
        }
        return found;
    }
    @SuppressWarnings("unchecked") private static boolean serviceOwns(String taskId)throws Exception{
        Field field=DownloadService.class.getDeclaredField("PROCESS_OWNED");field.setAccessible(true);
        java.util.Set<String> owned=(java.util.Set<String>)field.get(null);
        synchronized(owned){return owned.contains(taskId);}
    }
    /** Test APK only: remove this run's exact identifiers, never clear a table or delete a database. */
    private JSONObject cleanupServiceFile(Context app,String taskId,String source)throws Exception{
        check(taskId.matches("[0-9a-fA-F-]{36}"),"cleanup accepts only the real generated task UUID");
        JSONObject task=TaskStore.get(app,taskId);
        if(task==null)return new JSONObject().put("taskId",taskId).put("alreadyAbsent",true);
        check(source.equals(task.optString("url")),"cleanup is scoped to the submitted QA source");
        if(DownloadQueuePolicy.active(task.optString("status"))&&!"completed".equals(task.optString("status")))
            onMain(()->app.startService(new Intent(app,DownloadService.class).setAction(DownloadService.CANCEL).putExtra("id",taskId)));
        waitUntil(()->{
            JSONObject current=TaskStore.get(app,taskId);
            try{return !serviceOwns(taskId)&&(current==null||(!current.optBoolean("publicationBusy")&&!DownloadQueuePolicy.active(current.optString("status"))));}
            catch(Exception failure){throw new IllegalStateException(failure);}
        },15000,"cleanup waits for native execution and publication to finish");
        task=TaskStore.get(app,taskId);
        if(task==null)return new JSONObject().put("taskId",taskId).put("alreadyAbsent",true);
        String uri=task.optString("uri");
        JSONObject cleaned=new JSONObject().put("taskId",taskId).put("terminalStatus",task.optString("status"));
        cleaned.put("library",removeQaLibraryRows(app,taskId,uri));
        File archive=app.getDatabasePath(ContentArchiveStore.DATABASE);int archiveRows=0;
        if(archive.isFile())try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(archive.getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)){
            archiveRows=db.delete("completed_content","task_id=? AND source_url=?",new String[]{taskId,source});
            check(android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM completed_content WHERE task_id=?",new String[]{taskId})==0,
                    "no archive row for this exact QA task remains");
        }
        boolean publicRemoved=uri.isEmpty()||MediaExporter.delete(app,Uri.parse(uri));
        check(publicRemoved,"the Service's dedicated QA public output is removed");
        check(TaskStore.removeIfInactive(app,taskId),"only the completed/stopped QA task is removed");
        File downloads=app.getExternalFilesDir("downloads");if(downloads==null)downloads=new File(app.getFilesDir(),"downloads");
        check(deleteQaTree(new File(downloads,taskId)),"only the now-unowned QA task directory is removed");
        return cleaned.put("archiveRowsRemoved",archiveRows).put("publicOutputRemoved",true).put("taskRecordRemoved",true)
                .put("nativeOwnershipReleasedBeforeCleanup",true).put("taskDirectoryRemoved",true);
    }
    private static JSONObject removeQaLibraryRows(Context app,String taskId,String uri)throws Exception{
        File file=app.getDatabasePath("media-library.db");int videos=0,ledger=0;
        if(!file.isFile())return new JSONObject().put("videoRowsRemoved",0).put("ledgerRowsRemoved",0);
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)){
            db.setForeignKeyConstraintsEnabled(true);db.beginTransaction();
            try{
                long videoId=0;
                try(android.database.Cursor row=db.query("imported_task",new String[]{"uri","video_id"},"task_id=?",new String[]{taskId},null,null,null)){
                    if(row.moveToFirst()){
                        check(uri.equals(row.getString(0)),"QA library ledger refers only to the verified task output");
                        if(!row.isNull(1))videoId=row.getLong(1);
                    }
                }
                if(videoId>0){
                    long otherOwners=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM imported_task WHERE video_id=? AND task_id<>?",new String[]{String.valueOf(videoId),taskId});
                    check(otherOwners==0,"cleanup must not remove a library item referenced by another task");
                    videos=db.delete("video","id=? AND uri=?",new String[]{String.valueOf(videoId),uri});
                    check(videos==1,"cleanup removes only the library video that belongs to the verified QA URI");
                }
                ledger=db.delete("imported_task","task_id=? AND uri=?",new String[]{taskId,uri});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        return new JSONObject().put("videoRowsRemoved",videos).put("ledgerRowsRemoved",ledger);
    }
    private void writeDirectFileEvidence(JSONObject evidence)throws Exception{
        try(FileOutputStream report=new FileOutputStream(new File(output,"directfile-evidence.json"))){report.write(evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));report.getFD().sync();}
    }
    private static String failureStack(Throwable failure){
        // Android Log.getStackTraceString suppresses UnknownHostException chains.
        // Network acceptance must retain the real exception, causes and frames.
        java.io.StringWriter text=new java.io.StringWriter();
        java.io.PrintWriter writer=new java.io.PrintWriter(text);
        failure.printStackTrace(writer);writer.flush();return text.toString();
    }
    private String nativeAria2Version(Context app)throws Exception{
        // Inspect only after production download performed first-use extraction.
        // No Engine/FFmpeg/YoutubeDL/Aria2 warm-up is performed by this QA path.
        File binary=new File(app.getApplicationInfo().nativeLibraryDir,"libaria2c.so");
        File packages=new File(app.getNoBackupFilesDir(),"youtubedl-android/packages");
        File certificate=new File(packages,"python/usr/etc/tls/cert.pem");
        check(binary.isFile()&&binary.canExecute(),"Maven x86_64 aria2 library is installed as an executable native component");
        check(certificate.isFile()&&certificate.length()>0,"the production wrapper extracted its TLS trust bundle");
        ProcessBuilder command=new ProcessBuilder(binary.getAbsolutePath(),"--version").redirectErrorStream(true);
        command.environment().put("LD_LIBRARY_PATH",new File(packages,"python/usr/lib")+":"+new File(packages,"ffmpeg/usr/lib")+":"+new File(packages,"aria2c/usr/lib"));
        Process process=command.start();
        try{
            check(process.waitFor(5,TimeUnit.SECONDS),"bundled x86_64 aria2 version probe exits within five seconds");
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] block=new byte[1024];int count;
            try(InputStream input=process.getInputStream()){while((count=input.read(block))!=-1){check(bytes.size()+count<=16384,"bounded aria2 version output");bytes.write(block,0,count);}}
            String version=bytes.toString("UTF-8");
            check(process.exitValue()==0&&version.toLowerCase(java.util.Locale.ROOT).contains("aria2"),"bundled aria2 executes successfully on this Android ABI: "+version);
            return version.trim();
        }finally{process.destroy();if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();}
    }
    private JSONObject publicFileMetadata(Context app,Uri uri)throws Exception{
        String[] columns={android.provider.MediaStore.MediaColumns.DISPLAY_NAME,android.provider.MediaStore.MediaColumns.SIZE,
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,android.provider.MediaStore.MediaColumns.IS_PENDING,
                android.provider.MediaStore.MediaColumns.MIME_TYPE};
        try(android.database.Cursor row=app.getContentResolver().query(uri,columns,null,null,null)){
            check(row!=null&&row.moveToFirst(),"published file has a readable MediaStore row");
            return new JSONObject().put("name",row.getString(0)).put("bytes",row.getLong(1)).put("relativePath",row.getString(2)).put("pending",row.getInt(3)).put("mime",row.getString(4));
        }
    }
    private static final class FileDigest{final long bytes;final String sha256;FileDigest(long bytes,String sha256){this.bytes=bytes;this.sha256=sha256;}}
    private static FileDigest readDigest(InputStream stream)throws Exception{
        check(stream!=null,"readable private/public output stream");java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        long bytes=0;byte[] block=new byte[32768];int count;
        try(InputStream input=stream){while((count=input.read(block))!=-1){digest.update(block,0,count);bytes+=count;}}
        StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return new FileDigest(bytes,hex.toString());
    }
    private boolean activeWindowContains(String expected){
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null)return false;try{return nodeContains(root,expected);}finally{root.recycle();}
    }
    private static boolean nodeContains(android.view.accessibility.AccessibilityNodeInfo node,String expected){
        if(expected.contentEquals(node.getText()==null?"":node.getText())||expected.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return true;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null)try{if(nodeContains(child,expected))return true;}finally{child.recycle();}}
        return false;
    }
    private static boolean deleteQaTree(File file){
        boolean removed=true;File[] children=file.listFiles();if(children!=null)for(File child:children)removed=deleteQaTree(child)&&removed;
        return (!file.exists()||file.delete())&&removed;
    }
    /** Real Android lifecycle and controls, with only the metadata transport replaced. */
    private void verifyStartupUpdates()throws Exception{
        Context app=getTargetContext().getApplicationContext();
        app.getSharedPreferences("app_updates",0).edit().clear().putBoolean("automatic",true)
                .putLong("last_check",System.currentTimeMillis()).commit();
        AtomicInteger requests=new AtomicInteger();
        AppUpdates updates=new AppUpdates(app,(url,limit,missingIsNone)->{
            check(UpdateOffer.API.equals(url)&&missingIsNone,"startup uses the pinned release endpoint");
            if(requests.incrementAndGet()==1)throw new IOException("测试网络暂不可用");
            return null;
        });
        Field singleton=AppUpdates.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,updates);
        MainActivity main=null;UpdateActivity updatePage=null;
        try{
            main=(MainActivity)startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity home=main;
            // Navigate immediately; report timing rather than assuming a fast CI machine.
            boolean pendingWhenLeavingMain=requests.get()==0;
            updatePage=openUpdatePage(home);
            UpdateActivity firstPage=updatePage;
            waitUntil(()->firstPage.hasWindowFocus()&&updates.snapshot().phase==AppUpdates.Phase.ERROR,10000,"startup check completes on the update page after leaving MainActivity");
            check(requests.get()==1,"one failed request in the first foreground session");
            capture("01-network-failure-fixture");

            onMain(firstPage::finish);waitUntil(home::hasWindowFocus,10000,"internal Back returns to MainActivity");
            SystemClock.sleep(1800);check(requests.get()==1,"internal return must not retry the same foreground session");
            backgroundAndReopen(home,app);
            waitUntil(()->requests.get()==2&&updates.snapshot().phase==AppUpdates.Phase.NONE,10000,"reopening after a failed check retries despite the old last_check value");

            updatePage=openUpdatePage(home);
            toggleAutomatic(updatePage,updates,false);
            backgroundAndReopen(home,app);
            SystemClock.sleep(1800);check(requests.get()==2,"disabled automatic updates do not request metadata on reopening");

            updatePage=openUpdatePage(home);
            toggleAutomatic(updatePage,updates,true);
            waitUntil(()->requests.get()==3&&updates.snapshot().phase==AppUpdates.Phase.NONE,10000,"enabling the switch immediately starts a check");
            capture("02-enabled-check-completed-network-fixture");
            try(FileOutputStream out=new FileOutputStream(new File(output,"startup-fixture.txt"))){
                out.write(("Real Android Activity/foreground lifecycle. Only GitHub metadata is a fixture: first request throws IOException, later requests return no release. Requests="+requests.get()+". Startup check pending when leaving MainActivity="+pendingWhenLeavingMain+". No update package was downloaded or installed.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }finally{
            UpdateActivity page=updatePage;MainActivity home=main;
            onMain(()->{if(page!=null&&!page.isFinishing())page.finish();if(home!=null&&!home.isFinishing())home.finish();});
            waitForIdleSync();
            ExecutorService worker=field(updates,"worker",ExecutorService.class);worker.shutdownNow();
            check(worker.awaitTermination(5,TimeUnit.SECONDS),"fixture update worker stops");
        }
    }
    private UpdateActivity openUpdatePage(MainActivity home)throws Exception{
        ActivityMonitor monitor=addMonitor(UpdateActivity.class.getName(),null,false);
        try{
            onMain(()->home.startActivity(new Intent(home,UpdateActivity.class)));
            Activity opened=waitForMonitorWithTimeout(monitor,10000);check(opened instanceof UpdateActivity,"update page opens through real navigation");
            UpdateActivity page=(UpdateActivity)opened;waitUntil(page::hasWindowFocus,10000,"update page has focus");return page;
        }finally{removeMonitor(monitor);}
    }
    private void toggleAutomatic(UpdateActivity page,AppUpdates updates,boolean enabled)throws Exception{
        PocketSwitch toggle=onMainValue(()->(PocketSwitch)findType(page.getWindow().getDecorView(),PocketSwitch.class));
        check(toggle!=null&&onMainValue(()->toggle.isShown()&&toggle.isChecked()!=enabled),"automatic update switch has the expected state");
        String step=enabled?"04-switch-enable":"03-switch-disable";
        capture(step+"-before-touch");
        int[] target=onMainValue(()->{
            Rect visible=new Rect();boolean inView=toggle.getLocalVisibleRect(visible);
            int[] at=new int[2];toggle.getLocationOnScreen(at);
            android.util.Log.i("StreamPocketQA",step+" focus="+page.hasWindowFocus()+" clickable="+toggle.isClickable()+" focusable="+toggle.isFocusable()+" enabled="+toggle.isEnabled()+" checked="+toggle.isChecked()+" visible="+visible+" screen="+at[0]+","+at[1]+" size="+toggle.getWidth()+"x"+toggle.getHeight());
            check(page.hasWindowFocus()&&toggle.isEnabled()&&toggle.isClickable()&&toggle.isFocusable(),"visible switch must accept touch and keyboard/accessibility focus");
            check(inView&&visible.contains(toggle.getWidth()/2,toggle.getHeight()/2),"switch touch target is inside the visible, unclipped row");
            return new int[]{at[0]+toggle.getWidth()/2,at[1]+toggle.getHeight()/2};
        });
        long down=SystemClock.uptimeMillis();
        inject(MotionEvent.ACTION_DOWN,target[0],target[1],down);inject(MotionEvent.ACTION_UP,target[0],target[1],down);
        try{waitUntil(()->toggle.isChecked()==enabled&&updates.automatic()==enabled,5000,"touching the real switch updates the preference");}
        catch(AssertionError failure){capture(step+"-touch-failed");throw failure;}
    }
    private void backgroundAndReopen(MainActivity home,Context app)throws Exception{
        check(getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME),"HOME backgrounds the app");
        waitUntil(()->{try{return field(app,"startedActivities",Integer.class)==0;}catch(Exception unavailable){return false;}},10000,"all app Activities stop in the background");
        onMain(()->app.startActivity(new Intent(app,MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        waitUntil(home::hasWindowFocus,10000,"launcher reopening restores MainActivity");
    }
    private void verifyPictureInPicture(PlayerActivity activity,MainActivity libraryActivity,long videoId,long nextVideoId)throws Exception{
        check(getTargetContext().getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE),"emulator supports system PiP");
        int fullWidth=onMainValue(()->activity.getWindow().getDecorView().getWidth()),fullHeight=onMainValue(()->activity.getWindow().getDecorView().getHeight());
        ExoPlayer original=onMainValue(()->field(activity,"player",ExoPlayer.class));
        onMain(()->{original.seekTo(2000);original.play();field(activity,"playerView",PlayerView.class).showController();activity.findViewById(R.id.sp_player_more).performClick();});
        waitForIdleSync();
        onMain(()->{View entry=findText(activity.getWindow().getDecorView(),"画中画");check(entry!=null&&entry.isShown(),"PiP has a real visible tool entry");check(entry.performClick(),"PiP entry invokes its action");});
        waitUntil(activity::isInPictureInPictureMode,15000,"system enters PiP");
        waitUntil(()->activity.getWindow().getDecorView().getWidth()<fullWidth*.8&&activity.getWindow().getDecorView().getHeight()<fullHeight*.8,15000,"system PiP transition reaches a visibly smaller window");
        check(getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME),"navigate to the launcher while PiP remains visible");
        waitUntil(()->{try{return !field(libraryActivity,"foreground",Boolean.class)&&activity.isInPictureInPictureMode();}catch(Exception e){return false;}},10000,"the main app leaves foreground while system PiP stays visible");
        check(onMainValue(()->field(activity,"player",ExoPlayer.class))==original,"PiP onPause keeps the current player");
        long position=onMainValue(original::getCurrentPosition);
        waitUntil(()->original.getCurrentPosition()>position+500,10000,"real media position advances while in PiP");
        capture("02a-system-pip-real-playback");
        onMain(()->{PendingIntent next=(PendingIntent)field(activity,"pipCommands",java.util.Map.class).get("next");check(next!=null,"current PiP session has a next action");next.send();});
        waitUntil(()->{try{return field(activity,"videoId",Long.class)==nextVideoId;}catch(Exception e){return false;}},15000,"PiP next action selects the next real media item");
        awaitFrame(activity);
        ExoPlayer nextPlayer=onMainValue(()->field(activity,"player",ExoPlayer.class));
        check(onMainValue(()->activity.isInPictureInPictureMode()&&nextPlayer.isPlaying()&&nextPlayer.getPlaybackSuppressionReason()==Player.PLAYBACK_SUPPRESSION_REASON_NONE),"new media plays with audio focus while still in PiP");
        long nextPosition=onMainValue(nextPlayer::getCurrentPosition);
        waitUntil(()->nextPlayer.getCurrentPosition()>nextPosition+500,10000,"next media advances without returning to foreground");
        capture("02a-next-media-in-system-pip");
        onMain(()->getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        waitUntil(libraryActivity::hasWindowFocus,15000,"return to the existing library before expanding PiP");
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
