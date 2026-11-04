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
import java.util.concurrent.CountDownLatch;
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
        Object holder=grid.getChildAt(0).getTag();ImageView menu=field(holder,"menu",ImageView.class),cover=field(holder,"cover",ImageView.class);float d=activity.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(48*d),menu.getLayoutParams().width);assertEquals(Math.round(48*d),menu.getLayoutParams().height);
        assertTrue("Visible menu is closer to the upper/right corner",menu.getPaddingTop()<menu.getPaddingBottom()&&menu.getPaddingRight()<menu.getPaddingLeft());
        ViewGroup media=(ViewGroup)cover.getParent();int images=0;for(int i=0;i<media.getChildCount();i++)if(media.getChildAt(i) instanceof ImageView)images++;
        assertEquals("Cover and menu only; no redundant play overlay",2,images);
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
        field(library,"moreSelection",TextView.class).performClick();PocketDialog more=(PocketDialog)ShadowDialog.getLatestDialog();
        int remove=-1;for(int i=0;i<more.getListView().getAdapter().getCount();i++)if("从视频库移除".equals(more.getListView().getAdapter().getItem(i).toString()))remove=i;
        assertTrue(remove>=0);more.getListView().performItemClick(null,remove,remove);PocketDialog confirm=(PocketDialog)ShadowDialog.getLatestDialog();
        assertTrue(text(confirm.getWindow().getDecorView()).contains("原视频文件"));
        try(LibraryStore store=new LibraryStore(app)){assertNotNull(store.video(first));assertNotNull(store.video(second));}
        confirm.getButton(PocketDialog.BUTTON_NEGATIVE).performClick();await();assertEquals(2,grid.getAdapter().getCount());
        field(library,"moreSelection",TextView.class).performClick();choose("从视频库移除");confirm=latest();
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

    @Test public void refreshBeyondOneHundredUpdatesProgressAndFavoriteWithoutLosingViewport()throws Exception{
        for(int n=0;n<140;n++)seed("deep-"+n,"分页片段 · "+n,"https://x.com/fixture/status/"+(1000+n),1048576,1280,720);
        Bundle position=new Bundle();position.putBoolean("list",true);position.putInt("first",109);
        library=new VideoLibraryView(activity);library.restoreUiState(position);activity.setContentView(library);library.refresh();await();await();
        GridView grid=grid();assertTrue(grid.getAdapter().getCount()>100);assertEquals(109,grid.getFirstVisiblePosition());
        grid.scrollListBy(Math.round(17*activity.getResources().getDisplayMetrics().density));layout();
        Bundle before=library.saveUiState();assertEquals(109,before.getInt("first"));assertTrue(before.getInt("top")<0);
        long id=grid.getAdapter().getItemId(109);JSONObject stale=(JSONObject)grid.getAdapter().getItem(109);assertEquals(0,stale.optLong("playbackMs"));assertFalse(stale.optBoolean("favorite"));
        try(LibraryStore store=new LibraryStore(app)){store.savePlayback(id,45000);store.setFavorite(id,true);}
        library.refresh();await();await();
        assertEquals(before.getInt("first"),grid.getFirstVisiblePosition());assertEquals(before.getInt("top"),grid.getChildAt(0).getTop());assertEquals(id,grid.getAdapter().getItemId(109));
        JSONObject fresh=(JSONObject)grid.getAdapter().getItem(109);assertEquals(45000,fresh.optLong("playbackMs"));assertTrue(fresh.optBoolean("favorite"));
        assertEquals("收藏 · 续播 0:45",field(grid.getChildAt(0).getTag(),"context",TextView.class).getText().toString());
        int cached=grid.getAdapter().getCount();Object first=grid.getAdapter().getItem(0);
        for(int refresh=0;refresh<2;refresh++){
            library.refresh();await();await();
            assertEquals(cached,grid.getAdapter().getCount());assertSame(first,grid.getAdapter().getItem(0));assertSame("Unchanged periodic refresh must retain rows beyond the first 100",fresh,grid.getAdapter().getItem(109));
            assertEquals(before.getInt("first"),grid.getFirstVisiblePosition());assertEquals(before.getInt("top"),grid.getChildAt(0).getTop());
        }
    }

    @Test public void watchFavoritesSourceCollectionAndSearchComposeAndReachPlayerAfterRecreation()throws Exception{
        long a=seed("watch-a","日落云海 · 尚未看完的长标题","https://x.com/fixture/status/21",1048576,1920,1080);
        long b=seed("watch-b","另一段旅程","https://www.bilibili.com/video/BV1fixture",2097152,1080,1920);
        long c=seed("watch-c","已看完片段","https://x.com/fixture/status/23",1048576,1920,1080);
        long d=seed("watch-d","尚未收藏片段","https://x.com/fixture/status/24",1048576,1920,1080);
        long e=seed("watch-e","等待第一次观看","https://x.com/fixture/status/25",1048576,1920,1080);long group;
        try(LibraryStore store=new LibraryStore(app)){
            for(long id:new long[]{a,b,d})store.savePlayback(id,15000);store.savePlayback(c,100000);
            store.setFavoriteForVideos(Arrays.asList(a,b,c,e),true);group=store.createCollection("周末");store.setCollectionForVideos(Arrays.asList(a,b,c,e),group);
        }
        open();watch("继续观看");assertEquals(3,grid().getAdapter().getCount());
        field(library,"watchButton",TextView.class).performClick();latest().getButton(PocketDialog.BUTTON_NEUTRAL).performClick();await();assertEquals(2,grid().getAdapter().getCount());
        field(library,"sourceButton",TextView.class).performClick();choose("X");latest().getButton(PocketDialog.BUTTON_POSITIVE).performClick();await();assertEquals(1,grid().getAdapter().getCount());
        field(library,"collectionButton",TextView.class).performClick();choose("周末 · 4");await();
        described(library,"搜索视频").performClick();field(library,"search",EditText.class).setText("日落");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));await();
        assertEquals(a,grid().getAdapter().getItemId(0));assertTrue(text(library).contains("收藏 · 续播 0:15"));
        Bundle saved=library.saveUiState();assertEquals("continue",saved.getString("watch_filter"));assertTrue(saved.getBoolean("favorites_only"));
        library.release();library=new VideoLibraryView(activity);library.restoreUiState(saved);activity.setContentView(library);library.refresh();await();
        grid().getChildAt(0).performClick();Intent player=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(player);
        assertEquals(a,player.getLongExtra("video_id",0));assertEquals("continue",player.getStringExtra("library_watch_filter"));assertTrue(player.getBooleanExtra("library_favorites_only",false));
        assertEquals("日落",player.getStringExtra("library_query"));assertEquals(group,player.getLongExtra("library_collection",0));assertEquals(Arrays.asList("x"),player.getStringArrayListExtra("library_sources"));
        capture("050-watch-favorites-combined-light-native-fixture");
        described(library,"清除视频库筛选条件").performClick();await();assertEquals("all",library.saveUiState().getString("watch_filter"));assertFalse(library.saveUiState().getBoolean("favorites_only"));
        assertEquals("日落",field(library,"search",EditText.class).getText().toString());
        field(library,"search",EditText.class).setText("");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));await();assertEquals(5,grid().getAdapter().getCount());
        watch("未看");assertEquals(e,grid().getAdapter().getItemId(0));watch("已看完");assertEquals(c,grid().getAdapter().getItemId(0));
    }

    @Test public void itemAndBatchFavoritesUpdateOnlyTheirTargetsAndKeepProgress()throws Exception{
        long a=seed("favorite-a","可以收藏的片段","https://x.com/fixture/status/31",1048576,1280,720),b=seed("favorite-b","另一条片段","https://x.com/fixture/status/32",2097152,1280,720);
        try(LibraryStore store=new LibraryStore(app)){store.savePlayback(b,10000);}open();
        field(grid().getChildAt(0).getTag(),"menu",ImageView.class).performClick();choose("收藏视频");await();
        try(LibraryStore store=new LibraryStore(app)){assertTrue(store.video(b).getBoolean("favorite"));assertFalse(store.video(a).getBoolean("favorite"));assertEquals(10000,store.video(b).getLong("playbackMs"));}
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertTrue(text(library).contains("收藏 · 续播 0:10"));
        grid().getChildAt(0).performLongClick();grid().getChildAt(1).performClick();field(library,"moreSelection",TextView.class).performClick();choose("收藏所选视频");await();
        try(LibraryStore store=new LibraryStore(app)){assertEquals(2,store.countVideosFromSources("",null,0,"all",true));}
        field(library,"watchButton",TextView.class).performClick();latest().getButton(PocketDialog.BUTTON_NEUTRAL).performClick();await();
        grid().getChildAt(0).performLongClick();field(library,"moreSelection",TextView.class).performClick();assertFalse(dialogItems().contains("从当前合集移除"));choose("取消所选视频收藏");await();
        assertEquals(1,grid().getAdapter().getCount());assertEquals(a,grid().getAdapter().getItemId(0));assertFalse(field(library,"moreSelection",TextView.class).isEnabled());
        try(LibraryStore store=new LibraryStore(app)){assertFalse(store.video(b).getBoolean("favorite"));assertEquals(10000,store.video(b).getLong("playbackMs"));}
    }

    @Test public void batchMoveOutOfCurrentCollectionCanBeCancelledAndUndoneWithoutLosingMedia()throws Exception{
        long a=seed("move-a","合集片段甲","https://x.com/fixture/status/41",1048576,1280,720),b=seed("move-b","合集片段乙","https://x.com/fixture/status/42",2097152,1280,720),group,other,project,marker;
        try(LibraryStore store=new LibraryStore(app)){
            group=store.createCollection("旅行");other=store.createCollection("备选");store.setCollectionForVideos(Arrays.asList(a,b),group);store.setCollection(b,other,true);
            store.savePlayback(b,12000);store.setFavorite(b,true);marker=store.addMarker(b,3000,"保留标记");project=store.createProject("保留剪辑");store.addClip(project,b,1000,5000);
        }
        open();field(library,"collectionButton",TextView.class).performClick();choose("旅行 · 2");await();
        grid().getChildAt(0).performLongClick();field(library,"moreSelection",TextView.class).performClick();choose("从当前合集移除");
        assertTrue(text(latest().getWindow().getDecorView()).contains("其他合集不变"));latest().getButton(PocketDialog.BUTTON_NEGATIVE).performClick();await();assertEquals(2,grid().getAdapter().getCount());
        field(library,"moreSelection",TextView.class).performClick();choose("从当前合集移除");latest().getButton(PocketDialog.BUTTON_POSITIVE).performClick();await();assertEquals(1,grid().getAdapter().getCount());
        try(LibraryStore store=new LibraryStore(app)){
            assertEquals(Arrays.asList(other),store.collectionIds(b));assertEquals(marker,store.markers(b).get(0).getLong("id"));assertEquals(12000,store.video(b).getLong("playbackMs"));
            assertTrue(store.video(b).getBoolean("favorite"));assertEquals(b,store.clips(project).get(0).getLong("videoId"));assertEquals(2,TaskStore.all(app).size());
        }
        View undo=described(activity.getWindow().getDecorView(),"已从合集移除，视频保留，点按撤销");assertNotNull(undo);undo.performClick();await();
        assertEquals(2,grid().getAdapter().getCount());try(LibraryStore store=new LibraryStore(app)){assertTrue(store.collectionIds(b).contains(group));assertTrue(store.collectionIds(b).contains(other));}
        for(File file:files)if(file.getName().endsWith(".mp4"))assertTrue(file.exists());
    }

    @Test public void moveOutUndoSurvivesLeavingTheTabBeforeCommitOrBeforeSuccessDelivery()throws Exception{
        for(boolean releaseBeforeCommit:new boolean[]{true,false}){
            String suffix=releaseBeforeCommit?"before":"after";
            long a=seed("delayed-a-"+suffix,"延迟提交甲","https://x.com/fixture/status/71",1048576,1280,720),b=seed("delayed-b-"+suffix,"延迟提交乙","https://x.com/fixture/status/72",1048576,1280,720),group,other;
            try(LibraryStore store=new LibraryStore(app)){group=store.createCollection("延迟合集 "+suffix);other=store.createCollection("其他合集 "+suffix);store.setCollectionForVideos(Arrays.asList(a,b),group);store.setCollection(a,other,true);store.savePlayback(a,12000);}
            open();field(library,"collectionButton",TextView.class).performClick();choose("延迟合集 "+suffix+" · 2");await();
            grid().getChildAt(0).performLongClick();grid().getChildAt(1).performClick();field(library,"moreSelection",TextView.class).performClick();choose("从当前合集移除");
            ExecutorService worker=field(library,"io",ExecutorService.class);CountDownLatch entered=new CountDownLatch(1),allowCommit=new CountDownLatch(1);
            worker.submit(()->{entered.countDown();try{if(!allowCommit.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Commit gate timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}});
            try{
                assertTrue(entered.await(5,TimeUnit.SECONDS));latest().getButton(PocketDialog.BUTTON_POSITIVE).performClick();
                // The old selection contains b, but this transaction must undo
                // only a: b was already moved out before the queued commit ran.
                try(LibraryStore store=new LibraryStore(app)){store.setCollection(b,group,false);}
                if(releaseBeforeCommit)library.release();allowCommit.countDown();
                if(!releaseBeforeCommit){worker.submit(()->{}).get(10,TimeUnit.SECONDS);library.release();}
                activity.setContentView(new TextView(activity));assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();
                try(LibraryStore store=new LibraryStore(app)){assertFalse(store.collectionIds(a).contains(group));assertFalse(store.collectionIds(b).contains(group));assertTrue(store.collectionIds(a).contains(other));}
                View undo=described(activity.getWindow().getDecorView(),"已从合集移除，视频保留，点按撤销");assertNotNull("Committed work must expose undo after leaving the tab",undo);undo.performClick();awaitNotice("已撤销移出合集");
                try(LibraryStore store=new LibraryStore(app)){assertTrue(store.collectionIds(a).contains(group));assertFalse(store.collectionIds(b).contains(group));assertTrue(store.collectionIds(a).contains(other));assertEquals(12000,store.video(a).getLong("playbackMs"));}
            }finally{allowCommit.countDown();if(!worker.isShutdown())library.release();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }

    @Test public void collectionManagerValidatesRenameAndDeletesOnlyTheCollectionAfterConfirmation()throws Exception{
        long a=seed("manage-a","整理后的原视频","https://x.com/fixture/status/51",1048576,1280,720),group,other;
        try(LibraryStore store=new LibraryStore(app)){group=store.createCollection("旅行");other=store.createCollection("工作");store.setCollection(a,group,true);store.setCollection(a,other,true);store.savePlayback(a,9000);store.addMarker(a,1000,"保留");}
        open();field(library,"collectionButton",TextView.class).performClick();choose("旅行 · 1");await();
        manage("旅行 · 1","重命名合集");PocketDialog rename=latest();EditText input=(EditText)described(rename.getWindow().getDecorView(),"新的合集名称");assertNotNull(input);
        input.setText("工作");rename.getButton(PocketDialog.BUTTON_POSITIVE).performClick();await();assertTrue(rename.isShowing());assertNotNull(input.getError());
        input.setText("周末旅行");rename.getButton(PocketDialog.BUTTON_POSITIVE).performClick();await();assertFalse(rename.isShowing());assertEquals("周末旅行",field(library,"collectionButton",TextView.class).getText().toString());
        manage("周末旅行 · 1","删除合集");assertTrue(text(latest().getWindow().getDecorView()).contains("观看进度、精彩标记和剪辑草稿不变"));latest().getButton(PocketDialog.BUTTON_NEGATIVE).performClick();await();
        assertEquals(group,library.saveUiState().getLong("collection"));manage("周末旅行 · 1","删除合集");latest().getButton(PocketDialog.BUTTON_POSITIVE).performClick();await();
        assertEquals(0,library.saveUiState().getLong("collection"));assertEquals("全部合集",field(library,"collectionButton",TextView.class).getText().toString());assertEquals(1,grid().getAdapter().getCount());
        try(LibraryStore store=new LibraryStore(app)){assertEquals(Arrays.asList(other),store.collectionIds(a));assertEquals(9000,store.video(a).getLong("playbackMs"));assertEquals(1,store.markers(a).size());assertEquals(1,store.collections().size());}
    }

    @Test public void completedProgressLeavesContinueFilterAndEmptyStateKeepsAnExit()throws Exception{
        long id=seed("finish","继续观看的片段","https://x.com/fixture/status/61",1048576,1280,720);
        try(LibraryStore store=new LibraryStore(app)){store.savePlayback(id,5000);}open();watch("继续观看");assertEquals(1,grid().getAdapter().getCount());
        try(LibraryStore store=new LibraryStore(app)){store.savePlayback(id,100000);}library.refresh();await();assertEquals(0,grid().getAdapter().getCount());
        assertTrue(text(library).contains("播放过且未看完的视频会出现在这里"));assertEquals(View.VISIBLE,field(library,"clearFilters",TextView.class).getVisibility());
        described(library,"清除视频库筛选条件").performClick();await();assertEquals(1,grid().getAdapter().getCount());assertTrue(text(library).contains("已看完"));
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","dark").commit();Bundle saved=library.saveUiState();library.release();library=new VideoLibraryView(activity);library.restoreUiState(saved);activity.setContentView(library);library.refresh();await();
        capture("050-watch-completed-dark-native-fixture");
    }

    private GridView grid()throws Exception{return field(library,"grid",GridView.class);}
    private static PocketDialog latest(){return (PocketDialog)ShadowDialog.getLatestDialog();}
    private static List<String> dialogItems(){List<String> values=new ArrayList<>();PocketDialog dialog=latest();for(int i=0;i<dialog.getListView().getAdapter().getCount();i++)values.add(dialog.getListView().getAdapter().getItem(i).toString());return values;}
    private static void choose(String label){PocketDialog dialog=latest();List<String> values=dialogItems();int position=values.indexOf(label);assertTrue("Missing dialog item: "+label+" in "+values,position>=0);dialog.getListView().performItemClick(null,position,position);}
    private void watch(String label)throws Exception{field(library,"watchButton",TextView.class).performClick();choose(label);await();}
    private void manage(String name,String action)throws Exception{described(library,"视频库更多操作").performClick();choose("管理合集");await();choose(name);choose(action);}
    private void awaitNotice(String message)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();if(text(activity.getWindow().getDecorView()).contains(message))return;Thread.sleep(10);}fail("Missing completed operation feedback: "+message);}

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
        layout();java.util.concurrent.ThreadPoolExecutor thumbnailWorker=field(field(library,"covers",ThumbnailRepository.class),"worker",java.util.concurrent.ThreadPoolExecutor.class);thumbnailWorker.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();File base=new File(System.getProperty("user.dir"));while(base!=null&&!new File(base,"settings.gradle").exists())base=base.getParentFile();File dir=new File(base,name.startsWith("050-")?"app/build/reports/view-fixtures/050-library":"docs/design/assets/022-library");dir.mkdirs();
        if(referenceStills!=null){GridView grid=field(library,"grid",GridView.class);for(int n=0;n<grid.getChildCount()&&n<referenceStills.size();n++){ImageView cover=field(grid.getChildAt(n).getTag(),"cover",ImageView.class);field(library,"covers",ThumbnailRepository.class).cancel(cover);cover.setImageBitmap(referenceStills.get(n));assertEquals(ImageView.ScaleType.FIT_CENTER,cover.getScaleType());}}
        Bitmap bitmap=Bitmap.createBitmap(library.getWidth(),library.getHeight(),Bitmap.Config.ARGB_8888);library.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
    }
    private static View described(View root,String value){if(value.equals(String.valueOf(root.getContentDescription())))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=described(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static String text(View root){StringBuilder s=new StringBuilder();if(root instanceof TextView)s.append(((TextView)root).getText()).append('\n');if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)s.append(text(((ViewGroup)root).getChildAt(i)));return s.toString();}
    private static <T>T field(Object value,String name,Class<T> type)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(value);}
}
