package dev.streampocket;

import android.app.Dialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowDialog;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * Thin prototype: real catalogue, Android Views, native graphics and click/Intent paths.
 * Queue entries and content-provider files are explicit synthetic fixtures. The small
 * files are not playable videos; covers are allowed to stay unavailable. These tests
 * do not download, decode, export or claim installed-phone playback performance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class MediaWorkspaceUiTest {
    private static final String AUTHORITY="dev.streampocket.ui.fixture";
    private static final File OUTPUT=new File(System.getProperty("streampocket.media.ui.output",outputDirectory()));
    private Context app;
    private FixtureProvider provider;

    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.deleteDatabase("media-library.db");
        provider=new FixtureProvider(); ProviderInfo info=new ProviderInfo(); info.authority=AUTHORITY;
        provider.attachInfo(app,info); ShadowContentResolver.registerProviderInternal(AUTHORITY,provider);
    }

    @Test public void completedHistoryAppearsInLibraryAndSupportsFilterRenameEntryAndInternalPlayerRoute() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            Bundle state=new Bundle(); state.putString("tab","new"); state.putString("draft","");
            MainActivity activity=controller.create(state).start().resume().visible().get(); idle();
            clickText(field(activity,"navigation",View.class),"视频库");
            VideoLibraryView library=field(activity,"libraryView",VideoLibraryView.class); awaitWorker(library);
            GridView grid=field(library,"grid",GridView.class);
            assertEquals(0,grid.getAdapter().getCount());
            assertTrue(field(library,"status",TextView.class).getText().toString().contains("下载完成"));
            capture(activity.getWindow().getDecorView(),"01-library-empty");

            Uri x=fixtureFile("1","日落山脊.mp4");
            String longTitle="山间徒步一百秒：走到山脊时看见了整片日落与远处的云海，想把这个瞬间一直留在手机里";
            completedTask(x,longTitle,"https://x.com/fixture/status/1001",100);
            completedTask(fixtureFile("2","空间与光线.mp4"),"用一束自然光记录安静的房间：视频构图与色彩练习", "https://www.bilibili.com/video/BV1fixture",186);
            completedTask(fixtureFile("3","练习片段.mp4"),"下班路上的一小段风景，慢一点也没有关系", "https://www.douyin.com/video/1003",42);
            Engine.Media pending=new Engine.Media(); pending.url="https://x.com/fixture/status/1004";
            pending.title="未完成下载不能进入视频库"; pending.platform="X";
            TaskStore.create(app,pending,new Engine.Quality("best","最高可用画质","best"));

            // Navigate through the actual host controls; no private load/adapter substitution.
            clickText(field(activity,"navigation",View.class),"下载");
            clickText(field(activity,"navigation",View.class),"视频库");
            library=field(activity,"libraryView",VideoLibraryView.class); awaitWorker(library);
            grid=field(library,"grid",GridView.class);
            assertEquals("Only completed history imports",3,grid.getAdapter().getCount());
            capture(activity.getWindow().getDecorView(),"02-library-history-fixtures");
            assertNotNull(findText(grid,longTitle));
            TextView cardTitle=findText(grid,longTitle); assertEquals(2,cardTitle.getMaxLines());
            assertNotNull("Long Chinese titles must be ellipsized",cardTitle.getEllipsize());

            field(library,"sourceButton",TextView.class).performClick(); idle();
            PocketDialog filter=latestAlert(); capture(filter.getWindow().getDecorView(),"03-source-filter");
            filter.getListView().performItemClick(null,0,0);filter.getButton(PocketDialog.BUTTON_POSITIVE).performClick(); awaitWorker(library);
            grid=field(library,"grid",GridView.class);
            assertEquals(1,grid.getAdapter().getCount());
            JSONObject chosen=(JSONObject)grid.getAdapter().getItem(0);
            assertEquals("x",chosen.optString("source"));
            assertFalse("Fixture provider grants a real readable descriptor",chosen.optBoolean("missing"));
            capture(activity.getWindow().getDecorView(),"04-library-x-filter-fixture");

            View cell=grid.getChildAt(0); assertNotNull(cell);
            View more=findDescription(cell,"视频更多操作："+chosen.optString("title")); assertNotNull(more); more.performClick(); idle();
            PocketDialog actions=latestAlert();
            assertEquals("重命名文件",actions.getListView().getAdapter().getItem(2).toString());
            actions.getListView().performItemClick(null,2,2); idle();
            PocketDialog rename=latestAlert();
            EditText name=findEdit(rename.getWindow().getDecorView()); assertNotNull(name);
            assertEquals("日落山脊",name.getText().toString());
            assertNotNull(findContainingText(rename.getWindow().getDecorView(),"保留 .mp4"));
            capture(rename.getWindow().getDecorView(),"05-real-file-rename-entry-fixture");
            rename.getButton(PocketDialog.BUTTON_NEGATIVE).performClick(); idle();

            grid.performItemClick(cell,0,grid.getAdapter().getItemId(0)); idle();
            Intent destination=Shadows.shadowOf(activity).getNextStartedActivity(); assertNotNull(destination);
            assertEquals("dev.streampocket.PlayerActivity",destination.getComponent().getClassName());
            assertEquals(chosen.optLong("id"),destination.getLongExtra("video_id",0));
            assertNull("Internal route passes a catalogue id, not a network URL",destination.getData());
        } finally { controller.pause().stop().destroy(); idle(); }
    }

    @Test public void missingOriginalKeepsNamedPointAndRangeHighlightsWithoutBuildingAPlayer() throws Exception {
        Uri uri=fixtureFile("10","完整的一百秒视频.mp4");
        JSONObject task=completedTask(uri,"一百秒的完整视频：片段标记保留原视频时间，不随倍速改变", "https://x.com/fixture/status/1010",100);
        long id;
        try (LibraryStore store=new LibraryStore(app)) {
            id=store.upsertCompletedDownload(task); store.savePlayback(id,34000);
            store.addMarker(id,20000,"第一次转场"); store.addRangeMarker(id,80000,95000,"山顶的日落与云海");
        }
        provider.entries.get(uri.toString()).readable=false;
        ActivityController<PlayerActivity> controller=Robolectric.buildActivity(PlayerActivity.class,
                new Intent(app,PlayerActivity.class).putExtra("video_id",id));
        try {
            PlayerActivity activity=controller.create().start().resume().visible().get(); awaitWorker(activity);
            assertNull("Missing-file path must not instantiate ExoPlayer",field(activity,"player",Object.class));
            assertTrue(field(activity,"status",TextView.class).getText().toString().contains("已有精彩标记仍保留"));
            assertFalse(field(activity,"bookmarkButton",Button.class).isEnabled());
            activity.findViewById(R.id.sp_player_more).performClick(); idle();
            assertFalse(field(activity,"clipButton",Button.class).isEnabled());
            findText(field(activity,"menuPanel",View.class),"精彩标记").performClick(); idle();
            assertEquals("markers",field(activity,"sheetMode",String.class));
            LinearLayout markers=field(activity,"markerList",LinearLayout.class);
            assertNotNull(findContainingText(markers,"00:20.000"));assertNotNull(findContainingText(markers,"第一次转场"));
            assertNotNull(findContainingText(markers,"01:20.000 — 01:35.000"));assertNotNull(findContainingText(markers,"山顶的日落与云海"));
            capture(activity.getWindow().getDecorView(),"06-player-missing-original-fixture");
            ScrollView details=field(activity,"details",ScrollView.class); details.fullScroll(View.FOCUS_DOWN); idle();
            capture(activity.getWindow().getDecorView(),"07-player-preserved-point-range-fixtures");
            ((View)findContainingText(markers,"01:20.000 — 01:35.000").getParent()).performClick(); idle();
            assertNull(field(activity,"player",Object.class));
            try (LibraryStore store=new LibraryStore(app)) {
                List<JSONObject> saved=store.markers(id);
                assertEquals(2,saved.size()); assertEquals(80000,saved.get(1).optLong("startMs"));
                assertEquals(95000,saved.get(1).optLong("endMs"));
                assertEquals("Missing playback must not reset the saved position",34000,store.video(id).optLong("playbackMs"));
            }
        } finally { controller.pause().stop().destroy(); idle(); }
    }

    private Uri fixtureFile(String id,String name) throws Exception {
        File file=new File(app.getCacheDir(),"ui-fixture-"+id+".bin");
        try (FileOutputStream out=new FileOutputStream(file)) { out.write("SYNTHETIC UI FILE; NOT A PLAYABLE VIDEO".getBytes(StandardCharsets.UTF_8)); }
        Uri uri=Uri.parse("content://"+AUTHORITY+"/video/"+id); provider.entries.put(uri.toString(),new Entry(file,name)); return uri;
    }
    private JSONObject completedTask(Uri uri,String title,String url,int seconds) throws Exception {
        Engine.Media media=new Engine.Media(); media.title=title; media.url=url; media.duration=seconds; media.uploader="界面测试样例";
        media.platform=url.contains("bilibili")?"哔哩哔哩":url.contains("douyin")?"抖音":"X";
        String id=TaskStore.create(app,media,new Engine.Quality("best","最高可用画质","best"));
        TaskStore.update(app,id,new JSONObject().put("status","completed").put("uri",uri.toString())
                .put("mime","video/mp4").put("size",41).put("completedAt",System.currentTimeMillis()));
        return TaskStore.get(app,id);
    }
    private static void awaitWorker(Object owner) throws Exception {
        ExecutorService worker=field(owner,"io",ExecutorService.class);
        worker.submit(()->{}).get(10,TimeUnit.SECONDS); idle();
        worker.submit(()->{}).get(10,TimeUnit.SECONDS); idle();
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static String outputDirectory() {
        File folder=new File(System.getProperty("user.dir"));
        while (folder!=null && !new File(folder,"settings.gradle").isFile() && !new File(folder,"settings.gradle.kts").isFile()) folder=folder.getParentFile();
        return new File(folder==null?new File(System.getProperty("user.dir")):folder,"docs/design/assets/022-regression").getAbsolutePath();
    }
    private static <T>T field(Object owner,String name,Class<T> type) throws Exception {
        Field member=owner.getClass().getDeclaredField(name); member.setAccessible(true); return type.cast(member.get(owner));
    }
    private static PocketDialog latestAlert() { Dialog dialog=ShadowDialog.getLatestDialog(); assertTrue(dialog instanceof PocketDialog); return (PocketDialog)dialog; }
    private static void clickText(View parent,String exact) { TextView target=findText(parent,exact); assertNotNull("Missing visible control: "+exact,target); target.performClick(); idle(); }
    private static TextView findText(View view,String value) { return findText(view,value,false); }
    private static TextView findContainingText(View view,String value) { return findText(view,value,true); }
    private static TextView findText(View view,String value,boolean contains) {
        if (view instanceof TextView && (contains?((TextView)view).getText().toString().contains(value):((TextView)view).getText().toString().equals(value))) return (TextView)view;
        if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) { TextView found=findText(group.getChildAt(i),value,contains); if (found!=null) return found; } }
        return null;
    }
    private static EditText findEdit(View view) {
        if (view instanceof EditText) return (EditText)view;
        if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) { EditText found=findEdit(group.getChildAt(i)); if (found!=null) return found; } }
        return null;
    }
    private static View findDescription(View view,String value) {
        if (value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) { View found=findDescription(group.getChildAt(i),value); if (found!=null) return found; } }
        return null;
    }
    private static void capture(View decor,String name) throws Exception {
        idle(); float density=decor.getResources().getDisplayMetrics().density;
        int width=Math.round(412*density),height=Math.round(915*density); ViewGroup.LayoutParams params=decor.getLayoutParams();
        boolean wrapWidth=params!=null && params.width==ViewGroup.LayoutParams.WRAP_CONTENT;
        boolean wrapHeight=params!=null && params.height==ViewGroup.LayoutParams.WRAP_CONTENT;
        if (params!=null && params.width>0) width=Math.min(width,params.width);
        if (params!=null && params.height>0) height=Math.min(height,params.height);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width,wrapWidth?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,wrapHeight?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY));
        decor.layout(0,0,decor.getMeasuredWidth(),decor.getMeasuredHeight());
        Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT); decor.draw(new Canvas(bitmap));
        if (!OUTPUT.isDirectory() && !OUTPUT.mkdirs()) throw new java.io.IOException("Cannot create native UI output folder");
        try (FileOutputStream out=new FileOutputStream(new File(OUTPUT,name+".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); }
        StringBuilder tree=new StringBuilder("Native Android View fixture rendered by Robolectric. Not an installed-phone screenshot.\nSynthetic queue/provider data. No actual video decoding, downloading or exporting.\n");
        dump(decor,tree,0);
        try (FileOutputStream out=new FileOutputStream(new File(OUTPUT,name+".txt"))) { out.write(tree.toString().getBytes(StandardCharsets.UTF_8)); }
        bitmap.recycle();
    }
    private static void dump(View view,StringBuilder out,int depth) {
        if (view.getVisibility()!=View.VISIBLE) return; Rect bounds=new Rect(); view.getGlobalVisibleRect(bounds);
        for (int i=0;i<depth;i++) out.append("  "); out.append(view.getClass().getSimpleName()).append(' ').append(bounds.toShortString());
        if (view instanceof TextView) out.append(" text=").append(((TextView)view).getText().toString().replace('\n',' '));
        out.append('\n'); if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) dump(group.getChildAt(i),out,depth+1); }
    }
    private static final class Entry {
        final File file; final String name; boolean readable=true;
        Entry(File file,String name) { this.file=file; this.name=name; }
    }
    public static final class FixtureProvider extends ContentProvider {
        final Map<String,Entry> entries=new HashMap<>();
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "video/mp4"; }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
            Entry entry=entries.get(uri.toString()); if (entry==null || !entry.readable) throw new FileNotFoundException("Synthetic missing original");
            return ParcelFileDescriptor.open(entry.file,ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
            String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor result=new MatrixCursor(columns); Entry entry=entries.get(uri.toString()); if (entry==null) return result;
            Object[] row=new Object[columns.length]; for (int i=0;i<columns.length;i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]=entry.name;
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i]=entry.file.length();
                else if ("_id".equals(columns[i])) row[i]=Long.parseLong(uri.getLastPathSegment());
            } result.addRow(row); return result;
        }
        @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException("Read-only UI fixture"); }
        @Override public int update(Uri uri,ContentValues values,String where,String[] args) { return 0; }
        @Override public int delete(Uri uri,String where,String[] args) { return 0; }
    }
}
