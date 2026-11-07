package dev.streampocket;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/** Native input, selection and durable task journeys; source metadata is an offline fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DirectFileIntakeUiTest {
    private static final String FILE_URL="https://files.example.org/report.pdf?signature=fixture%2Bvalue&part=2&part=1";
    private Context app;

    @Before public void clean() throws Exception {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences(MainActivity.class.getName(),0).edit().clear().commit();
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("download-location",0).edit().clear().commit();
        app.getSharedPreferences(DownloadPresetStore.PREFERENCES,0).edit().clear().commit();
        app.getSharedPreferences("config",0).edit().clear().putBoolean("app_update_auto_check",false).putBoolean("motion_enabled",false).commit();
        app.deleteDatabase(ContentArchiveStore.DATABASE);
        Field initialized=TaskStore.class.getDeclaredField("initialized");initialized.setAccessible(true);initialized.setBoolean(null,false);
    }

    @Test public void fileSelectionAndSignedMetadataSurviveRecreationUntilExplicitDownload() throws Exception {
        AtomicInteger inspected=new AtomicInteger();
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();
            field(a,"input",EditText.class).setText(FILE_URL);
            click(a.getWindow().getDecorView(),"普通文件");
            assertEquals(FILE_URL,field(a,"input",EditText.class).getText().toString());
            set(a,"inspector",(InspectionBatch.Inspector)(url,p,k)->{throw new AssertionError("File request reached video extraction");});
            set(a,"fileInspector",(InspectionBatch.Inspector)(url,p,k)->{inspected.incrementAndGet();assertEquals(FILE_URL,url);return Collections.singletonList(file(url));});
            assertTrue(field(a,"parse",Button.class).performClick());drain(a);
            assertEquals(1,inspected.get());assertTrue(TaskStore.all(a).isEmpty());
            assertOriginalFileControls(a);assertEquals("direct-file",inbox(a).media.get(0).kind);
        }
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();
            assertTrue(field(a,"fileIntake",Boolean.class));assertOriginalFileControls(a);
            assertEquals(1,inbox(a).media.size());assertTrue(TaskStore.all(a).isEmpty());
            click(a.getWindow().getDecorView(),"下载文件");drain(a);
            assertEquals(1,TaskStore.all(a).size());JSONObject task=TaskStore.all(a).get(0);
            assertEquals(FILE_URL,task.getString("url"));
            assertEquals("original-file",task.getJSONObject("quality").getString("id"));
            JSONObject media=task.getJSONObject("media");assertEquals("direct-file",media.getString("kind"));
            assertEquals(FILE_URL,media.getJSONObject("file").getString("url"));
            assertEquals("报告.pdf",media.getJSONObject("file").getString("name"));
            assertEquals("application/pdf",media.getJSONObject("file").getString("mime"));
            assertEquals(3,media.getJSONObject("file").getLong("size"));
            Intent request=Shadows.shadowOf(a).getNextStartedService();assertNotNull(request);
            assertEquals(DownloadService.ENQUEUE,request.getAction());
            assertEquals(Collections.singletonList(task.getString("id")),request.getStringArrayListExtra("ids"));
        }
    }

    @Test public void switchingIntakeWhileVideoIsBeingInspectedKeepsEachQueuedKind() throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        List<String> calls=Collections.synchronizedList(new ArrayList<>());
        String videoUrl="https://www.youtube.com/watch?v=BaW_jenozKc";
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();
            set(a,"inspector",(InspectionBatch.Inspector)(url,p,k)->{calls.add("media:"+url);started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Collections.singletonList(video(url));});
            set(a,"fileInspector",(InspectionBatch.Inspector)(url,p,k)->{calls.add("file:"+url);return Collections.singletonList(file(url));});
            field(a,"input",EditText.class).setText(videoUrl);field(a,"parse",Button.class).performClick();
            assertTrue(started.await(5,TimeUnit.SECONDS));
            click(a.getWindow().getDecorView(),"普通文件");
            field(a,"input",EditText.class).setText(FILE_URL);field(a,"parse",Button.class).performClick();
            InspectionInbox snapshot=new InspectionInbox();snapshot.restore(a.getPreferences(0).getString("inspection_inbox",""));
            assertEquals(2,snapshot.jobs.size());assertEquals("media",snapshot.jobs.get(0).kind);
            assertEquals("direct-file",snapshot.jobs.get(1).kind);assertEquals(FILE_URL,snapshot.jobs.get(1).url);
            assertEquals(Collections.singletonList("media:"+videoUrl),calls);
            release.countDown();drain(a);
            assertEquals(Arrays.asList("media:"+videoUrl,"file:"+FILE_URL),calls);
            assertEquals(2,inbox(a).media.size());assertTrue(TaskStore.all(a).isEmpty());
        } finally {release.countDown();}
    }

    @Test public void failedFileRetryUsesItsStoredKindAfterReturningToVideoModeAndRecreating() throws Exception {
        String failedId;
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();click(a.getWindow().getDecorView(),"普通文件");
            set(a,"fileInspector",(InspectionBatch.Inspector)(url,p,k)->{throw new IOException("offline file inspection fixture");});
            field(a,"input",EditText.class).setText(FILE_URL);field(a,"parse",Button.class).performClick();drain(a);
            assertEquals(1,inbox(a).count("failed"));failedId=inbox(a).jobs.get(0).id;
            click(a.getWindow().getDecorView(),"视频与音频");
        }
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();assertFalse(field(a,"fileIntake",Boolean.class));
            assertEquals(failedId,inbox(a).jobs.get(0).id);assertEquals("direct-file",inbox(a).jobs.get(0).kind);
            set(a,"inspector",(InspectionBatch.Inspector)(url,p,k)->{throw new AssertionError("Stored file kind was lost");});
            set(a,"fileInspector",(InspectionBatch.Inspector)(url,p,k)->{assertEquals(FILE_URL,url);return Collections.singletonList(file(url));});
            click(a.getWindow().getDecorView(),"重新解析");drain(a);
            assertEquals(0,inbox(a).count("failed"));assertEquals(1,inbox(a).media.size());
            assertTrue(inbox(a).media.get(0).directFile());assertOriginalFileControls(a);assertTrue(TaskStore.all(a).isEmpty());
        }
    }

    @Test public void manualFilePasteRetainsSignedYoutubeAndBilibiliRequestsWithoutStartingInspection() throws Exception {
        String first="https://www.youtube.com/watch?v=BaW_jenozKc&signature=first%2Bvalue&download=1";
        String second="https://www.youtube.com/watch?v=BaW_jenozKc&signature=second%2Bvalue&download=1";
        String third="https://www.bilibili.com/video/BV1nx411u79K?timestamp=100&sign=fixture%2Bvalue";
        String fourth="https://www.bilibili.com/video/BV1nx411u79K?timestamp=101&sign=fixture%2Bvalue";
        List<String> urls=Arrays.asList(first,second,third,fourth);
        ClipboardManager clipboard=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData data=ClipData.newPlainText("fixture",first);for(String url:urls.subList(1,urls.size()))data.addItem(new ClipData.Item(url));clipboard.setPrimaryClip(data);
        AtomicInteger inspected=new AtomicInteger();
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();click(a.getWindow().getDecorView(),"普通文件");
            set(a,"inspector",(InspectionBatch.Inspector)(url,p,k)->{inspected.incrementAndGet();throw new AssertionError("Paste must wait for confirmation");});
            set(a,"fileInspector",(InspectionBatch.Inspector)(url,p,k)->{inspected.incrementAndGet();throw new AssertionError("Paste must wait for confirmation");});
            field(a,"input",EditText.class).setText(first);click(a.getWindow().getDecorView(),"粘贴");
            String expected=first+"\n"+second+"\n"+third+"\n"+fourth;
            assertEquals(expected,field(a,"input",EditText.class).getText().toString());
            click(a.getWindow().getDecorView(),"粘贴");Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(expected,field(a,"input",EditText.class).getText().toString());
            assertEquals(ClipboardOffer.fingerprint(urls,true),a.getPreferences(0).getString("clipboard_seen",""));
            assertEquals(0,inspected.get());assertTrue(inbox(a).jobs.isEmpty());assertTrue(TaskStore.all(a).isEmpty());
        } finally {clipboard.clearPrimaryClip();}
    }

    @Test public void dismissingAMediaOfferDoesNotHideTheSameFileOfferOrAnOfferAfterModeSwitch() throws Exception {
        ClipboardManager clipboard=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture",FILE_URL));
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();c.windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(find(a.getWindow().getDecorView(),"查看链接"));
            dismissClipboard(a);
            String mediaDigest=a.getPreferences(0).getString("clipboard_seen","");
            assertEquals(ClipboardOffer.fingerprint(Collections.singletonList(FILE_URL)),mediaDigest);
            inbox(a).media.add(video(FILE_URL));call(a,"persistInspectionInbox",new Class<?>[0]);
            click(a.getWindow().getDecorView(),"普通文件");Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.VISIBLE,field(a,"clipboardNotice",View.class).getVisibility());
            assertEquals(Collections.singletonList(FILE_URL),field(a,"clipboardUrls",List.class));
            assertNotNull(find(a.getWindow().getDecorView(),"查看链接"));assertEquals(mediaDigest,a.getPreferences(0).getString("clipboard_seen",""));
            dismissClipboard(a);
            assertEquals(ClipboardOffer.fingerprint(Collections.singletonList(FILE_URL),true),a.getPreferences(0).getString("clipboard_seen",""));
            assertNotEquals(mediaDigest,a.getPreferences(0).getString("clipboard_seen",""));
            c.pause().resume().windowFocusChanged(true);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.GONE,field(a,"clipboardNotice",View.class).getVisibility());
            assertTrue(inbox(a).jobs.isEmpty());assertTrue(TaskStore.all(a).isEmpty());
        } finally {clipboard.clearPrimaryClip();}
    }

    @Test public void namedPresetEditorPreviewsPersistsAndAppliesOnlyToEligibleMedia() throws Exception {
        String template="{uploader}-{title}-{id}";
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();inbox(a).media.add(video("https://www.youtube.com/watch?v=BaW_jenozKc"));inbox(a).media.add(file(FILE_URL));
            call(a,"persistInspectionInbox",new Class<?>[0]);call(a,"showTab",new Class<?>[]{String.class},"new");
            click(a.getWindow().getDecorView(),"下载预设");choose(latest(),"管理与新建预设");choose(latest(),"新建预设");
            PocketDialog editor=latest();View content=editor.getWindow().getDecorView();
            description(content,"预设名称",EditText.class).setText("离线收藏");
            EditText naming=description(content,"文件命名模板",EditText.class);naming.setText("{unknown}");
            click(content,"保存预设");assertTrue(editor.isShowing());assertTrue(DownloadPresetStore.all(a).isEmpty());
            naming.setText(template);assertNotNull(contains(content,"预览：离线作者-离线视频-video-1"));
            click(content,"保存预设");assertEquals(1,DownloadPresetStore.all(a).size());latest().dismiss();
            click(a.getWindow().getDecorView(),"下载预设");choose(latest(),"离线收藏");
            Engine.Media selected=inbox(a).media.stream().filter(m->!m.directFile()).findFirst().get();
            Engine.Media untouched=inbox(a).media.stream().filter(Engine.Media::directFile).findFirst().get();
            assertEquals("离线收藏",selected.presetName);assertEquals(template,selected.namingTemplate);
            assertEquals("",untouched.presetId);assertEquals("报告.pdf",untouched.file.name);
            assertTrue(TaskStore.all(a).isEmpty());
        }
        try(ActivityController<MainActivity> c=activity()) {
            MainActivity a=c.get();assertEquals(2,inbox(a).media.size());
            Engine.Media selected=inbox(a).media.stream().filter(m->!m.directFile()).findFirst().get();
            assertEquals("离线收藏",selected.presetName);assertEquals(template,selected.namingTemplate);
            assertTrue(TaskStore.all(a).isEmpty());
        }
    }

    private static ActivityController<MainActivity> activity() {
        return Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
    }
    private static Engine.Media file(String url) {
        Engine.Media m=new Engine.Media();m.kind="direct-file";m.id="file-1";m.title="报告.pdf";m.platform="文件";m.url=url;
        m.file=new DirectFileSpec(url,m.title,"application/pdf",3,"\"fixture-v1\"","",true);m.filesize=3;return m;
    }
    private static Engine.Media video(String url) {
        Engine.Media m=new Engine.Media();m.id="video-1";m.title="离线视频";m.uploader="离线作者";m.platform="YouTube";m.url=url;m.hasAudio=true;
        m.qualities.add(new Engine.Quality("best","最高可用画质","bv*+ba/b"));return m;
    }
    private static void assertOriginalFileControls(MainActivity a) {
        View content=a.getWindow().getDecorView();TextView original=find(content,"原文件");assertNotNull(original);assertFalse(original.isEnabled());
        assertNotNull(find(content,"下载文件"));assertNull(find(content,"下载视频"));assertNull(find(content,"下载音频"));
        assertNull(find(content,"字幕与封面  ›"));assertNull(contains(content,"画质："));
        assertNotNull(contains(content,"PDF 文件"));assertNull(contains(content,"application/pdf"));
    }
    private static void dismissClipboard(MainActivity a) {
        Button dismiss=description(a.getWindow().getDecorView(),"忽略剪贴板链接提醒",Button.class);
        assertNotNull(dismiss);assertTrue(dismiss.performClick());
    }
    private static InspectionInbox inbox(MainActivity a) throws Exception {return field(a,"inspectionInbox",InspectionInbox.class);}
    private static void drain(MainActivity a) throws Exception {
        for(int n=0;n<25;n++) {
            field(a,"io",ExecutorService.class).submit(()->{}).get(8,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!field(a,"parsing",Boolean.class)&&inbox(a).count("queued")==0)return;
        }
        fail("Inspection queue did not drain");
    }
    private static void click(View content,String text) {
        TextView button=find(content,text);assertNotNull("Missing control: "+text,button);assertTrue(button.isEnabled());assertTrue(button.performClick());
    }
    private static PocketDialog latest() {PocketDialog dialog=(PocketDialog)ShadowDialog.getLatestDialog();assertNotNull(dialog);return dialog;}
    private static void choose(PocketDialog dialog,String text) {
        ListView list=dialog.getListView();assertNotNull(list);
        for(int i=0;i<list.getAdapter().getCount();i++)if(text.equals(String.valueOf(list.getAdapter().getItem(i)))) {
            assertTrue(list.performItemClick(null,i,i));return;
        }
        fail("Missing choice: "+text);
    }
    private static TextView find(View root,String text) {
        if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return(TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {TextView child=find(((ViewGroup)root).getChildAt(i),text);if(child!=null)return child;}
        return null;
    }
    private static TextView contains(View root,String text) {
        if(root instanceof TextView&&((TextView)root).getText().toString().contains(text))return(TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {TextView child=contains(((ViewGroup)root).getChildAt(i),text);if(child!=null)return child;}
        return null;
    }
    private static <T>T description(View root,String text,Class<T> type) {
        if(text.equals(String.valueOf(root.getContentDescription())))return type.cast(root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {T child=description(((ViewGroup)root).getChildAt(i),text,type);if(child!=null)return child;}
        return null;
    }
    private static <T>T field(Object target,String name,Class<T> type) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);
    }
}
