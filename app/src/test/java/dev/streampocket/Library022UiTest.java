package dev.streampocket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

/** Actual catalogue/Views and explicit image fixtures; no video decoder or user files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Library022UiTest {
    private Context app;
    private ActivityController<Activity> controller;
    private Activity activity;
    private VideoLibraryView library;
    private final List<File> files=new ArrayList<>();

    @Before public void setup() {
        app=RuntimeEnvironment.getApplication(); app.deleteDatabase("media-library.db");
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","light").putBoolean("motion_enabled",false).remove("library_view").remove("library_order").remove("library_columns").commit();
        controller=Robolectric.buildActivity(Activity.class);activity=controller.get();UiTheme.apply(activity);
        controller.create().start().resume().visible();
    }
    @After public void teardown() {
        if(library!=null)library.release(); controller.pause().stop().destroy();
        for(File file:files)file.delete(); app.deleteDatabase("media-library.db");
    }

    @Test public void youtubeSourceCanBeSelectedAndKeepsItsNameAfterReturn()throws Exception{
        seed("youtube","YouTube 视频库 · 离线样例","https://youtu.be/BaW_jenozKc",1024,1920,1080);
        seed("other","其他来源 · 离线样例","https://x.com/reference/status/10",1024,1920,1080);open();
        field(library,"sourceButton",TextView.class).performClick();
        PocketDialog dialog=(PocketDialog)ShadowDialog.getLatestDialog();
        int youtube=-1;for(int n=0;n<dialog.getListView().getAdapter().getCount();n++)
            if("YouTube".equals(dialog.getListView().getAdapter().getItem(n).toString()))youtube=n;
        assertTrue(youtube>=0);dialog.getListView().performItemClick(null,youtube,youtube);
        dialog.getButton(PocketDialog.BUTTON_POSITIVE).performClick();await();
        assertEquals(1,field(library,"grid",GridView.class).getAdapter().getCount());
        assertEquals("YouTube",field(library,"sourceButton",TextView.class).getText().toString());
        Bundle saved=library.saveUiState();assertTrue(saved.getStringArrayList("sources").contains("youtube"));
        capture("youtube-source-fixture.png");
    }

    @Test public void multipleSourceFiltersApplyAtomicallySurviveReturnAndCanBeCleared()throws Exception{
        seed("x","X 样例","https://x.com/a/status/1",1024,1920,1080);seed("b","B站样例","https://www.bilibili.com/video/BV1test",2048,1080,1920);seed("d","抖音样例","https://v.douyin.com/sample",3072,1080,1920);open();
        field(library,"sourceButton",TextView.class).performClick();PocketDialog cancel=(PocketDialog)ShadowDialog.getLatestDialog();cancel.getListView().performItemClick(null,0,0);assertEquals(3,field(library,"grid",GridView.class).getAdapter().getCount());cancel.getButton(PocketDialog.BUTTON_NEGATIVE).performClick();await();assertEquals(3,field(library,"grid",GridView.class).getAdapter().getCount());
        field(library,"sourceButton",TextView.class).performClick();PocketDialog apply=(PocketDialog)ShadowDialog.getLatestDialog();apply.getListView().performItemClick(null,0,0);apply.getListView().performItemClick(null,1,1);apply.getButton(PocketDialog.BUTTON_POSITIVE).performClick();await();assertEquals(2,field(library,"grid",GridView.class).getAdapter().getCount());assertEquals("2 个来源",field(library,"sourceButton",TextView.class).getText().toString());
        Bundle saved=library.saveUiState();assertEquals(2,saved.getStringArrayList("sources").size());library.release();library=new VideoLibraryView(activity);library.restoreUiState(saved);activity.setContentView(library);library.refresh();await();assertEquals(2,field(library,"grid",GridView.class).getAdapter().getCount());
        described(library,"清除视频库筛选条件").performClick();await();assertEquals(3,field(library,"grid",GridView.class).getAdapter().getCount());assertEquals(View.GONE,field(library,"clearFilters",TextView.class).getVisibility());
    }
    @Test public void coverFirstGridWithReferenceStillsKeepsPortraitAndLandscapeFrames()throws Exception{
        seed("wide","Big Buck Bunny · 清晨的草地与溪流","https://x.com/reference/status/10",2216913,1920,1080);seed("tall","Big Buck Bunny · 竖屏裁切参考片段","https://www.bilibili.com/video/BVreference",1900000,1080,1920);open();
        File project=new File(System.getProperty("user.dir"));while(project!=null&&!new File(project,"settings.gradle").isFile())project=project.getParentFile();assertNotNull(project);Bitmap frame=android.graphics.BitmapFactory.decodeFile(new File(project,"test-media/marker-editor-028/ref20-5000.png").getPath());assertNotNull(frame);
        Bitmap portrait=Bitmap.createBitmap(frame,frame.getWidth()/3,0,Math.max(1,frame.getWidth()/3),frame.getHeight());capture("035-cover-first-reference-stills-not-device",Arrays.asList(portrait,frame));
    }
    @Test public void mediaMetadataViewModeAndSearchAreCompactAndPersistent() throws Exception {
        seed("one","界面样例 · 山间一百秒的日落与云海，这是一条真实长度的中文标题","https://x.com/fixture/status/1",36L*1048576,1920,1080);
        seed("two","界面样例 · 安静房间的光线","https://www.bilibili.com/video/BV1fixture",268L*1048576,1080,1920);
        open(); GridView grid=field(library,"grid",GridView.class);
        assertEquals(2,grid.getAdapter().getCount()); assertEquals(2,grid.getNumColumns());
        assertEquals(android.widget.AdapterView.INVALID_ROW_ID,grid.getAdapter().getItemId(-1));
        assertEquals(android.widget.AdapterView.INVALID_ROW_ID,grid.getAdapter().getItemId(2));
        assertEquals(View.GONE,field(library,"search",EditText.class).getVisibility());
        String all=text(library);assertTrue(all.contains("1080P"));assertTrue(all.contains("36.0 MB"));assertTrue(all.contains("268.0 MB"));
        ImageView cover=(ImageView)field(grid.getChildAt(0).getTag(),"cover",ImageView.class);
        assertEquals(ImageView.ScaleType.FIT_CENTER,cover.getScaleType());
        capture("01-grid-light-bitmap-fixtures");
        described(library,"选择视频库视图").performClick();((PocketDialog)ShadowDialog.getLatestDialog()).getListView().performItemClick(null,2,2);layout();
        assertEquals(1,grid.getNumColumns());assertEquals("list",app.getSharedPreferences("config",0).getString("library_view",""));
        capture("02-list-light-bitmap-fixtures");
        described(library,"搜索视频").performClick();EditText input=field(library,"search",EditText.class);assertEquals(View.VISIBLE,input.getVisibility());
        input.setText("安静房间");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));await();
        assertEquals(1,grid.getAdapter().getCount());Bundle state=library.saveUiState();assertEquals("安静房间",state.getString("query"));assertTrue(state.getBoolean("list"));
        library.release();library=new VideoLibraryView(activity);library.restoreUiState(state);activity.setContentView(library);library.refresh();await();
        assertEquals("安静房间",field(library,"search",EditText.class).getText().toString());assertEquals(1,field(library,"grid",GridView.class).getAdapter().getCount());
        assertEquals(1,field(library,"grid",GridView.class).getNumColumns());
    }
    @Test public void threeColumnChoicePersistsAndSmallIconsKeepLargeTouchTargets()throws Exception{
        for(int n=0;n<6;n++)seed("three-"+n,"界面样例 · 第 "+(n+1)+" 个视频，完整保留真实长度的中文标题","https://x.com/fixture/status/"+(100+n),1048576,1920,1080);
        open();described(library,"选择视频库视图").performClick();PocketDialog view=(PocketDialog)ShadowDialog.getLatestDialog();view.getListView().performItemClick(null,1,1);layout();
        GridView grid=field(library,"grid",GridView.class);assertEquals(3,grid.getNumColumns());assertEquals(3,app.getSharedPreferences("config",0).getInt("library_columns",0));
        Object holder=grid.getChildAt(0).getTag();ImageView menu=field(holder,"menu",ImageView.class),play=field(holder,"play",ImageView.class);float d=activity.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(48*d),menu.getLayoutParams().width);assertEquals(Math.round(22*d),play.getLayoutParams().width);assertTrue(menu.getPaddingLeft()>=Math.round(18*d));assertTrue(play.getPaddingLeft()>=Math.round(6*d));
        capture("three-column-small-controls-fixture");Bundle saved=library.saveUiState();library.release();library=new VideoLibraryView(activity);library.restoreUiState(saved);activity.setContentView(library);library.refresh();await();assertEquals(3,field(library,"grid",GridView.class).getNumColumns());
        described(library,"选择视频库视图").performClick();((PocketDialog)ShadowDialog.getLatestDialog()).getListView().performItemClick(null,0,0);layout();assertEquals(2,field(library,"grid",GridView.class).getNumColumns());
    }

    @Test public void multiSelectionSharesOnlySelectedUrisAndZeroSelectionDisablesActions() throws Exception {
        long first=seed("one","界面样例 · 第一条","https://x.com/fixture/status/1",1048576,1280,720);
        long second=seed("two","界面样例 · 第二条","https://www.bilibili.com/video/BV1fixture",2097152,1920,1080);
        open();GridView grid=field(library,"grid",GridView.class);View cell=grid.getChildAt(0);
        grid.getOnItemLongClickListener().onItemLongClick(grid,cell,0,grid.getAdapter().getItemId(0));layout();
        assertTrue(field(library,"shareSelection",TextView.class).isEnabled());
        grid.performItemClick(cell,0,grid.getAdapter().getItemId(0));layout();
        assertFalse(field(library,"shareSelection",TextView.class).isEnabled());
        field(library,"shareSelection",TextView.class).performClick();assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        grid.performItemClick(grid.getChildAt(0),0,grid.getAdapter().getItemId(0));grid.performItemClick(grid.getChildAt(1),1,grid.getAdapter().getItemId(1));layout();
        capture("03-selection-bitmap-fixtures");field(library,"shareSelection",TextView.class).performClick();
        Intent chooser=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(chooser);
        Intent send=chooser.getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Intent.ACTION_SEND_MULTIPLE,send.getAction());
        ArrayList<Uri> uris=send.getParcelableArrayListExtra(Intent.EXTRA_STREAM);assertEquals(2,uris.size());assertEquals(2,send.getClipData().getItemCount());
        try(LibraryStore store=new LibraryStore(app)){assertTrue(uris.contains(Uri.parse(store.video(first).optString("uri"))));assertTrue(uris.contains(Uri.parse(store.video(second).optString("uri"))));}
    }

    @Test public void batchRemovalNeedsConfirmationAndPreservesOriginalsQueueAndDrafts() throws Exception {
        long first=seed("one","界面样例 · 第一条","https://x.com/fixture/status/1",1048576,1280,720);
        long second=seed("two","界面样例 · 第二条","https://x.com/fixture/status/2",2097152,1280,720);
        long draft;
        try(LibraryStore store=new LibraryStore(app)){store.addMarker(first,5000,"保留前的标记");draft=store.createProject("保留草稿");store.addClip(draft,first,0,10000);}
        open();GridView grid=field(library,"grid",GridView.class);
        grid.getOnItemLongClickListener().onItemLongClick(grid,grid.getChildAt(0),0,grid.getAdapter().getItemId(0));grid.performItemClick(grid.getChildAt(1),1,grid.getAdapter().getItemId(1));
        field(library,"removeSelection",TextView.class).performClick();PocketDialog confirm=(PocketDialog)ShadowDialog.getLatestDialog();
        assertTrue(text(confirm.getWindow().getDecorView()).contains("原视频文件"));
        try(LibraryStore store=new LibraryStore(app)){assertNotNull(store.video(first));assertNotNull(store.video(second));}
        confirm.getButton(PocketDialog.BUTTON_POSITIVE).performClick();await();
        assertEquals(0,grid.getAdapter().getCount());
        assertEquals(android.widget.AdapterView.INVALID_ROW_ID,grid.getAdapter().getItemId(0));
        try(LibraryStore store=new LibraryStore(app)){
            assertNull(store.video(first));assertNull(store.video(second));assertEquals(1,store.clips(draft).size());assertFalse(store.clips(draft).get(0).optString("uri").isEmpty());
            store.syncCompletedDownloads();assertTrue(store.listVideos("","",0,30,0).isEmpty());
        }
        assertEquals(2,TaskStore.all(app).size());for(File f:files)if(f.getName().endsWith(".mp4"))assertTrue(f.exists());
    }

    @Test public void batchTransactionsRollBackAndDarkLibraryKeepsMetadata() throws Exception {
        long id=seed("one","界面样例 · 暗色浏览","https://x.com/fixture/status/1",4194304,1280,720);
        try(LibraryStore store=new LibraryStore(app)){
            long group=store.createCollection("收藏");
            try{store.setCollectionForVideos(Arrays.asList(id,999999L),group);fail("Missing member must fail the batch");}catch(RuntimeException expected){}
            assertTrue(store.collectionIds(id).isEmpty());
            try{store.removeVideoRecords(Arrays.asList(id,0L));fail("Invalid member must roll back prior deletes");}catch(IllegalArgumentException expected){}
            assertNotNull(store.video(id));store.setCollectionForVideos(Arrays.asList(id),group);assertEquals(Arrays.asList(group),store.collectionIds(id));
            assertEquals(id,store.listVideos("","",0,30,0,"untrusted SQL").get(0).optLong("id"));
        }
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","dark").commit();open();assertTrue(text(library).contains("4.0 MB"));capture("04-grid-dark-bitmap-fixture");
        assertEquals(0xff0f1215,((android.graphics.drawable.ColorDrawable)library.getBackground()).getColor());
    }

    @Test public void recreatedListRestoresANonzeroViewportAndItsOffset() throws Exception {
        for(int i=0;i<18;i++)seed("scroll-"+i,"界面样例 · 浏览位置 "+i,"https://x.com/fixture/status/"+(100+i),1048576,1280,720);
        app.getSharedPreferences("config",0).edit().putString("library_view","list").commit();open();
        GridView grid=field(library,"grid",GridView.class);
        grid.scrollListBy(Math.round(350*activity.getResources().getDisplayMetrics().density));layout();
        Bundle saved=library.saveUiState();assertTrue("Fixture must exercise a scrolled viewport",saved.getInt("first")>0);
        long firstId=grid.getAdapter().getItemId(saved.getInt("first"));
        library.release();library=new VideoLibraryView(activity);library.restoreUiState(saved);activity.setContentView(library);library.refresh();await();
        GridView restored=field(library,"grid",GridView.class);assertEquals(18,restored.getAdapter().getCount());
        assertEquals(saved.getInt("first"),restored.getFirstVisiblePosition());
        assertEquals(firstId,restored.getAdapter().getItemId(restored.getFirstVisiblePosition()));
        assertEquals(saved.getInt("top"),restored.getChildAt(0).getTop());
    }

    private long seed(String key,String title,String url,long bytes,int width,int height) throws Exception {
        File file=new File(app.getCacheDir(),"library022-"+key+".mp4");try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{1,2,3,4});}files.add(file);
        JSONObject task=new JSONObject().put("id",key).put("title",title).put("status","completed").put("uri",Uri.fromFile(file).toString()).put("mime","video/mp4").put("size",bytes).put("url",url).put("completedAt",10000+files.size())
                .put("media",new JSONObject().put("duration",100).put("width",width).put("height",height));
        JSONArray tasks=new JSONArray(app.getSharedPreferences("tasks",0).getString("items","[]"));tasks.put(task);app.getSharedPreferences("tasks",0).edit().putString("items",tasks.toString()).commit();
        try(LibraryStore store=new LibraryStore(app)){
            long id=store.upsertCompletedDownload(task);JSONObject video=store.video(id);File cover=new File(app.getCacheDir(),"video-covers/"+video.optString("thumbnailKey")+".jpg");cover.getParentFile().mkdirs();
            Bitmap bitmap=Bitmap.createBitmap(320,180,Bitmap.Config.RGB_565);bitmap.eraseColor(key.equals("one")?Color.rgb(88,116,102):Color.rgb(100,119,144));
            try(FileOutputStream out=new FileOutputStream(cover)){bitmap.compress(Bitmap.CompressFormat.JPEG,80,out);}bitmap.recycle();files.add(cover);return id;
        }
    }
    private void open() throws Exception {library=new VideoLibraryView(activity);activity.setContentView(library);library.refresh();await();}
    private void await() throws Exception {
        ExecutorService worker=field(library,"io",ExecutorService.class);
        for(int i=0;i<8;i++){worker.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();if(!field(library,"loading",Boolean.class))break;}
        layout();
    }
    private void layout(){int w=Math.round(412*activity.getResources().getDisplayMetrics().density),h=Math.round(780*activity.getResources().getDisplayMetrics().density);library.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));library.layout(0,0,w,h);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void capture(String name)throws Exception{capture(name,null);}
    private void capture(String name,List<Bitmap> referenceStills) throws Exception {
        layout();java.util.concurrent.ThreadPoolExecutor thumbnailWorker=field(field(library,"covers",ThumbnailRepository.class),"worker",java.util.concurrent.ThreadPoolExecutor.class);thumbnailWorker.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();File base=new File(System.getProperty("user.dir"));while(base!=null&&!new File(base,"settings.gradle").exists())base=base.getParentFile();File dir=new File(base,"docs/design/assets/022-library");dir.mkdirs();
        if(referenceStills!=null){GridView grid=field(library,"grid",GridView.class);for(int n=0;n<grid.getChildCount()&&n<referenceStills.size();n++){ImageView cover=field(grid.getChildAt(n).getTag(),"cover",ImageView.class);field(library,"covers",ThumbnailRepository.class).cancel(cover);cover.setImageBitmap(referenceStills.get(n));assertEquals(ImageView.ScaleType.FIT_CENTER,cover.getScaleType());}}
        Bitmap bitmap=Bitmap.createBitmap(library.getWidth(),library.getHeight(),Bitmap.Config.ARGB_8888);library.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
    }
    private static View described(View root,String value){if(value.equals(String.valueOf(root.getContentDescription())))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=described(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static String text(View root){StringBuilder s=new StringBuilder();if(root instanceof TextView)s.append(((TextView)root).getText()).append('\n');if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)s.append(text(((ViewGroup)root).getChildAt(i)));return s.toString();}
    private static <T>T field(Object value,String name,Class<T> type)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(value);}
}
