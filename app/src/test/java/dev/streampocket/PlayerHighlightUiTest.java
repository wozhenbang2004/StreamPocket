package dev.streampocket;

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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.exoplayer.ExoPlayer;
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
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowPopupMenu;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * Real Views + parser + SQLite + navigation, with an explicit source-time interface fixture.
 * The proxy is injected before onStart and is NEVER attached to PlayerView. It supplies
 * source position/duration and records control calls; it cannot decode, prepare or render.
 * Screenshots are labelled UI fixtures and are not proof of playback performance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PlayerHighlightUiTest {
    private static final String AUTHORITY="dev.streampocket.highlight.fixture";
    private static final Uri MEDIA=Uri.parse("content://"+AUTHORITY+"/video/1");
    private static final File OUTPUT=new File(outputDirectory());
    private Context app;
    private ClockFileProvider provider;
    private long videoId;

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication(); app.deleteDatabase("media-library.db");
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        provider=new ClockFileProvider(); ProviderInfo info=new ProviderInfo(); info.authority=AUTHORITY;
        provider.attachInfo(app,info); ShadowContentResolver.registerProviderInternal(AUTHORITY,provider);
        provider.file=new File(app.getCacheDir(),"highlight-ui-clock-fixture.bin");
        try (FileOutputStream out=new FileOutputStream(provider.file)) { out.write("UI CLOCK FIXTURE ONLY; NOT A VIDEO".getBytes(StandardCharsets.UTF_8)); }
        JSONObject task=new JSONObject().put("id","highlight-clock-fixture").put("status","completed")
                .put("title","给日落留下一个名字：保存时间点，也保存一段精彩")
                .put("url","https://x.com/fixture/status/1").put("uri",MEDIA.toString()).put("mime","video/mp4")
                .put("media",new JSONObject().put("duration",100).put("platform","X"));
        try (LibraryStore store=new LibraryStore(app)) { videoId=store.upsertCompletedDownload(task); }
        assertTrue(videoId>0);
    }

    @Test public void pointAndNamedRangeUseOriginalTimeAtTwoTimesSpeedAndPassExactRangeToEditor() throws Exception {
        ClockFixture clock=new ClockFixture(34250,100000);
        try (Session session=clockSession(null,clock)) {
            PlayerActivity activity=session.activity;
            field(activity,"speedButton",TextView.class).performClick(); idle();
            TextView speedChoice=findText(field(activity,"menuPanel",View.class),"2×"); assertNotNull(speedChoice);
            speedChoice.performClick(); idle();
            assertEquals(2f,clock.speed,0f);
            assertEquals("2×",field(activity,"speedButton",TextView.class).getText().toString());
            field(activity,"markerOptionsButton",View.class).performLongClick(); idle();

            field(activity,"bookmarkButton",Button.class).performClick(); awaitWorker(activity);
            List<JSONObject> saved=markers(); assertEquals(1,saved.size());
            assertEquals("A 2× watch speed must not divide/multiply source time",34250,saved.get(0).optLong("positionMs"));
            assertEquals(0,saved.get(0).optLong("endMs"));

            openRangeViaMenu(activity);
            assertEquals(View.VISIBLE,field(activity,"rangePanel",LinearLayout.class).getVisibility());
            clock.positionMs=40123; clickDescription(activity,"将开始时间设为当前播放位置");
            clock.positionMs=45987; clickDescription(activity,"将结束时间设为当前播放位置");
            EditText start=field(activity,"rangeStart",EditText.class),end=field(activity,"rangeEnd",EditText.class);
            assertEquals("00:40.123",start.getText().toString()); assertEquals("00:45.987",end.getText().toString());

            start.setText("00:20.250"); end.setText("00:20.250"); saveRange(activity);
            assertTrue(field(activity,"rangeFeedback",TextView.class).getText().toString().contains("晚于"));
            assertEquals(1,markers().size());
            end.setText("01:40.001"); saveRange(activity);
            assertTrue(field(activity,"rangeFeedback",TextView.class).getText().toString().contains("超过视频时长"));
            assertEquals(1,markers().size());
            start.setText("01:70"); end.setText("01:35"); saveRange(activity);
            assertTrue(field(activity,"rangeFeedback",TextView.class).getText().toString().contains("有效时间"));
            assertEquals(1,markers().size());

            start.setText("00:20.250"); end.setText("00:35.875");
            field(activity,"rangeName",EditText.class).setText("山顶的日落与云海");
            revealRangePanel(activity);
            capture(activity.getWindow().getDecorView(),"08-player-range-input-clock-fixture");
            saveRange(activity);
            assertEquals("Saving the range returns the full canvas",View.GONE,field(activity,"sheetOverlay",View.class).getVisibility());
            saved=markers(); assertEquals(2,saved.size()); JSONObject range=saved.get(0);
            assertEquals(20250,range.optLong("startMs")); assertEquals(35875,range.optLong("endMs"));
            assertEquals("山顶的日落与云海",range.optString("label"));

            field(activity,"markerOptionsButton",View.class).performLongClick(); idle();
            TextView jump=findText(field(activity,"markerList",View.class),"00:20.250 — 00:35.875");
            assertNotNull(jump);((View)jump.getParent()).performClick(); awaitWorker(activity); assertEquals(20250,clock.positionMs);
            try (LibraryStore store=new LibraryStore(app)) { assertEquals(20250,store.video(videoId).optLong("playbackMs")); }
            field(activity,"markerOptionsButton",View.class).performLongClick(); idle();
            clickDescription(activity,"管理 00:20–00:35 的精彩标记");
            clickPopupItem("加入剪辑");
            Intent editor=Shadows.shadowOf(activity).getNextStartedActivity(); assertNotNull(editor);
            assertEquals("dev.streampocket.EditorActivity",editor.getComponent().getClassName());
            assertEquals(videoId,editor.getLongExtra("video_id",0));
            assertEquals(20250,editor.getLongExtra("position_ms",-1));
            assertEquals(20250,editor.getLongExtra("start_ms",-1)); assertEquals(35875,editor.getLongExtra("end_ms",-1));
        }
        assertTrue(clock.released);
    }

    @Test public void unfinishedRangeNameAndMillisecondInputsSurviveActivityStateRestore() throws Exception {
        Bundle savedState=new Bundle();
        try (Session original=clockSession(null,new ClockFixture(50125,100000))) {
            openRangeViaMenu(original.activity);
            field(original.activity,"rangeStart",EditText.class).setText("00:50.250");
            field(original.activity,"rangeEnd",EditText.class).setText("01:02.875");
            field(original.activity,"rangeName",EditText.class).setText("稍后再剪这一段");
            original.activity.onSaveInstanceState(savedState);
        }
        try (Session restored=clockSession(savedState,new ClockFixture(50125,100000))) {
            PlayerActivity activity=restored.activity;
            assertEquals(View.VISIBLE,field(activity,"rangePanel",LinearLayout.class).getVisibility());
            assertEquals("00:50.250",field(activity,"rangeStart",EditText.class).getText().toString());
            assertEquals("01:02.875",field(activity,"rangeEnd",EditText.class).getText().toString());
            assertEquals("稍后再剪这一段",field(activity,"rangeName",EditText.class).getText().toString());
            assertEquals("Draft restoration must not silently save a highlight",0,markers().size());
            revealRangePanel(activity);
            capture(activity.getWindow().getDecorView(),"09-player-restored-range-clock-fixture");
        }
    }

    @Test public void actualMissingFileStateUsesNeutralDisabledButtonsAndKeepsExitAvailable() throws Exception {
        provider.readable=false;
        ActivityController<PlayerActivity> controller=Robolectric.buildActivity(PlayerActivity.class,intent());
        try {
            PlayerActivity activity=controller.create().start().resume().visible().get(); awaitWorker(activity);
            assertNull(field(activity,"player",Object.class));
            Button bookmark=field(activity,"bookmarkButton",Button.class);
            assertFalse(bookmark.isEnabled()); assertEquals(UiTheme.darkPalette().muted,bookmark.getCurrentTextColor());
            assertTrue(bookmark.getMinimumHeight()>=Math.round(44*activity.getResources().getDisplayMetrics().density));
            android.graphics.drawable.Drawable disabledBackground=bookmark.getBackground();
            if(disabledBackground instanceof android.graphics.drawable.RippleDrawable)
                disabledBackground=((android.graphics.drawable.RippleDrawable)disabledBackground).getDrawable(0);
            disabledBackground=disabledBackground.getCurrent();
            assertTrue(disabledBackground instanceof GradientDrawable);
            assertEquals(UiTheme.darkPalette().control,((GradientDrawable)disabledBackground).getColor().getDefaultColor());
            assertTrue(findDescription(activity.getWindow().getDecorView(),"返回视频库").isEnabled());
            assertTrue(field(activity,"status",TextView.class).getText().toString().contains("已有精彩标记仍保留"));
            capture(activity.getWindow().getDecorView(),"10-player-neutral-disabled-missing-fixture");
        } finally { controller.pause().stop().destroy(); idle(); }
    }

    private Session clockSession(Bundle state,ClockFixture fixture) throws Exception {
        ActivityController<PlayerActivity> controller=Robolectric.buildActivity(PlayerActivity.class,intent());
        PlayerActivity activity=controller.create(state).get(); awaitWorker(activity);
        assertNull("Before onStart no native player may be built",field(activity,"player",Object.class));
        assertFalse("The provider supplies an actual readable local descriptor",field(activity,"missing",Boolean.class));
        setField(activity,"player",fixture.proxy());
        invoke(activity,"setMediaActions",new Class<?>[]{boolean.class},true);
        field(activity,"status",TextView.class).setText("界面交互样例 · 仅使用时间接口夹具，未解码视频");
        TextView label=new TextView(activity); label.setText("界面交互样例\n时间接口夹具 · 不含视频解码");
        label.setTextSize(13); label.setTextColor(Color.WHITE); label.setGravity(Gravity.CENTER);
        field(activity,"videoFrame",FrameLayout.class).addView(label,new FrameLayout.LayoutParams(-1,-1));
        controller.start().resume().visible(); idle(); return new Session(controller,activity);
    }
    private Intent intent() { return new Intent(app,PlayerActivity.class).putExtra("video_id",videoId); }
    private List<JSONObject> markers() { try (LibraryStore store=new LibraryStore(app)) { return store.markers(videoId); } }
    private static void saveRange(PlayerActivity activity) throws Exception { field(activity,"saveRangeButton",Button.class).performClick(); awaitWorker(activity); }
    private static void openRangeViaMenu(PlayerActivity activity) throws Exception { field(activity,"markerOptionsButton",View.class).performLongClick(); idle(); TextView range=findText(field(activity,"markerPanel",View.class),"标记精彩片段"); assertNotNull(range); range.performClick(); idle(); }
    private static void clickPopupItem(String title) {
        PocketDialog menu=(PocketDialog)ShadowDialog.getLatestDialog(); assertNotNull(menu);
        for(int i=0;i<menu.getListView().getAdapter().getCount();i++) {
            if(title.equals(menu.getListView().getAdapter().getItem(i).toString())) {
                menu.getListView().performItemClick(null,i,i); idle(); return;
            }
        }
        fail("Missing marker action: "+title);
    }
    private static void clickDescription(PlayerActivity activity,String description) { View control=findDescription(activity.getWindow().getDecorView(),description); assertNotNull(control); control.performClick(); idle(); }
    private static void revealRangePanel(PlayerActivity activity) throws Exception {
        layoutWindow(activity.getWindow().getDecorView());
        field(activity,"details",ScrollView.class).scrollTo(0,field(activity,"rangePanel",LinearLayout.class).getTop()); idle();
    }
    private static void awaitWorker(Object owner) throws Exception { ExecutorService io=field(owner,"io",ExecutorService.class); io.submit(()->{}).get(10,TimeUnit.SECONDS); idle(); io.submit(()->{}).get(10,TimeUnit.SECONDS); idle(); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static <T>T field(Object owner,String name,Class<T> type) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(owner)); }
    private static void setField(Object owner,String name,Object value) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value); }
    private static Object invoke(Object owner,String name,Class<?>[] types,Object... values) throws Exception { Method m=owner.getClass().getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(owner,values); }
    private static TextView findText(View view,String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) { TextView found=findText(group.getChildAt(i),text); if (found!=null) return found; } } return null;
    }
    private static View findDescription(View view,String description) {
        if (description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) { View found=findDescription(group.getChildAt(i),description); if (found!=null) return found; } } return null;
    }
    private static String outputDirectory() {
        File root=new File(System.getProperty("user.dir"));
        while (root!=null && !new File(root,"settings.gradle").isFile() && !new File(root,"settings.gradle.kts").isFile()) root=root.getParentFile();
        return new File(root==null?new File(System.getProperty("user.dir")):root,"docs/evidence/028/regression/021-regression").getAbsolutePath();
    }
    private static void capture(View decor,String name) throws Exception {
        idle(); layoutWindow(decor);
        Bitmap image=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888); image.eraseColor(Color.TRANSPARENT); decor.draw(new Canvas(image));
        if (!OUTPUT.isDirectory() && !OUTPUT.mkdirs()) throw new java.io.IOException("Cannot create native View fixture output");
        try (FileOutputStream out=new FileOutputStream(new File(OUTPUT,name+".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out)); }
        StringBuilder tree=new StringBuilder("Robolectric native Android Views. Explicit source-time proxy fixture; not a native decoder or installed-phone screenshot.\nNo extraction, downloads, hardware playback or export performed.\n");
        dump(decor,tree,0); try (FileOutputStream out=new FileOutputStream(new File(OUTPUT,name+".txt"))) { out.write(tree.toString().getBytes(StandardCharsets.UTF_8)); } image.recycle();
    }
    private static void layoutWindow(View decor) {
        float density=decor.getResources().getDisplayMetrics().density; int width=Math.round(412*density),height=Math.round(915*density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,decor.getMeasuredWidth(),decor.getMeasuredHeight());
    }
    private static void dump(View view,StringBuilder out,int depth) {
        if (view.getVisibility()!=View.VISIBLE) return; Rect bounds=new Rect(); view.getGlobalVisibleRect(bounds);
        for (int i=0;i<depth;i++) out.append("  "); out.append(view.getClass().getSimpleName()).append(' ').append(bounds.toShortString());
        if (view instanceof TextView) out.append(" text=").append(((TextView)view).getText().toString().replace('\n',' ')); out.append('\n');
        if (view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for (int i=0;i<group.getChildCount();i++) dump(group.getChildAt(i),out,depth+1); }
    }
    private static final class Session implements AutoCloseable {
        final ActivityController<PlayerActivity> controller; final PlayerActivity activity;
        Session(ActivityController<PlayerActivity> controller,PlayerActivity activity) { this.controller=controller; this.activity=activity; }
        @Override public void close() { controller.pause().stop().destroy(); idle(); }
    }
    private static final class ClockFixture implements InvocationHandler {
        long positionMs; final long durationMs; float speed=1f; boolean requestedPlay,released;
        ClockFixture(long positionMs,long durationMs) { this.positionMs=positionMs; this.durationMs=durationMs; }
        ExoPlayer proxy() { return (ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class<?>[]{ExoPlayer.class},this); }
        @Override public Object invoke(Object proxy,Method method,Object[] args) {
            assertEquals("All player controls must remain on main",Looper.getMainLooper(),Looper.myLooper());
            switch (method.getName()) {
                case "getCurrentPosition": return positionMs;
                case "getDuration": return durationMs;
                case "setPlaybackSpeed": speed=(Float)args[0]; return null;
                case "seekTo": positionMs=(Long)args[args.length-1]; return null;
                case "getPlayWhenReady": return requestedPlay;
                case "setPlayWhenReady": requestedPlay=(Boolean)args[0]; return null;
                case "pause": requestedPlay=false; return null;
                case "isPlaying": return false;
                case "release": released=true; return null;
                case "toString": return "Explicit source-time UI fixture; no decoder";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy==args[0];
                default: throw new UnsupportedOperationException("Clock-only fixture cannot perform "+method.getName());
            }
        }
    }
    public static final class ClockFileProvider extends ContentProvider {
        File file; boolean readable=true;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "video/mp4"; }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException { if (!readable) throw new FileNotFoundException("Explicit missing-file fixture"); return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY); }
        @Override public Cursor query(Uri uri,String[] projection,String where,String[] args,String order) {
            String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(columns); Object[] row=new Object[columns.length];
            for (int i=0;i<columns.length;i++) { if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]="精彩片段交互样例.mp4"; else if (OpenableColumns.SIZE.equals(columns[i])) row[i]=file==null?0:file.length(); }
            cursor.addRow(row); return cursor;
        }
        @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException("UI fixture"); }
        @Override public int update(Uri uri,ContentValues values,String where,String[] args) { return 0; }
        @Override public int delete(Uri uri,String where,String[] args) { return 0; }
    }
}
