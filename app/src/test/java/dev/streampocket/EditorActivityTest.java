package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
import static org.junit.Assert.*;

/** Real View/SQLite fixtures only: no codec, media export or installed-phone claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class EditorActivityTest {
    private Context app;
    private LibraryStore store;
    private File fixture;
    private ActivityController<EditorActivity> controller;

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication(); app.deleteDatabase("media-library.db"); store=new LibraryStore(app);
        fixture=File.createTempFile("editor-view-fixture-",".mp4",app.getCacheDir());
        try (FileOutputStream output=new FileOutputStream(fixture)) { output.write(new byte[]{0,0,0,0}); }
    }
    @After public void cleanup() throws Exception {
        if (controller!=null) {
            EditorActivity activity=controller.get(); ExecutorService worker=field(activity,"io",ExecutorService.class);
            controller.stop().destroy(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
        }
        store.close(); fixture.delete(); app.deleteDatabase("media-library.db");
    }
    @Test public void validatesOriginalMillisecondRangesAndRejectsAmbiguousTimes() {
        assertEquals(12_345,EditorActivity.parseTime("12.345"));
        assertEquals(72_345,EditorActivity.parseTime("1:12.345"));
        assertEquals(3_672_345,EditorActivity.parseTime("1:01:12.345"));
        assertArrayEquals(new long[]{12_789,24_567},EditorActivity.validatedRange("00:12.789","24.567",100_000));
        for (String invalid:new String[]{"","-1","NaN","0:60","1:2:3:4","1.2345","999999999999999999999999"}) {
            try { EditorActivity.parseTime(invalid); fail("Accepted invalid time: "+invalid); } catch (IllegalArgumentException expected) {}
        }
        rejectsRange("25","24",100_000); rejectsRange("10","10",100_000); rejectsRange("0","100.001",100_000); rejectsRange("0","1",0);
    }
    @Test public void incomingRangeStaysExactAndAddsOnlyValidatedClipToExistingDraft() throws Exception {
        long video=store.addExportedVideo("视图夹具：较长的中文原片标题，用于检查截取页面的换行与主次",""+Uri.fromFile(fixture),"video/mp4",4,100_000);
        long project=store.createProject("精彩片段组合");
        Intent intent=new Intent(app,EditorActivity.class).putExtra("video_id",video).putExtra("project_id",project)
                .putExtra("start_ms",12_789L).putExtra("end_ms",24_567L).putExtra("position_ms",12_789L);
        // Start without resume so a known-invalid media fixture never starts a decoder.
        controller=Robolectric.buildActivity(EditorActivity.class,intent).create().start();
        EditorActivity activity=controller.get(); drain(activity);
        assertEquals("00:12.789",field(activity,"startInput",EditText.class).getText().toString());
        assertEquals("00:24.567",field(activity,"endInput",EditText.class).getText().toString());
        assertTrue(store.clips(project).isEmpty()); assertFalse(ExportService.isRunning(project));
        assertWhite(activity); capture(activity,"20-editor-range");
        field(activity,"startInput",EditText.class).setText("25"); field(activity,"endInput",EditText.class).setText("24");
        field(activity,"addButton",Button.class).performClick(); drain(activity);
        assertTrue(field(activity,"feedback",TextView.class).getText().toString().contains("终点必须晚于起点")); assertTrue(store.clips(project).isEmpty());
        field(activity,"startInput",EditText.class).setText("12.789"); field(activity,"endInput",EditText.class).setText("24.567");
        field(activity,"addButton",Button.class).performClick(); drain(activity);
        assertEquals(1,store.clips(project).size()); assertEquals(12_789,store.clips(project).get(0).optLong("startMs")); assertEquals(24_567,store.clips(project).get(0).optLong("endMs"));
        assertTrue(field(activity,"exportButton",Button.class).isEnabled()); assertFalse(ExportService.isRunning(project));
        assertWhite(activity); capture(activity,"21-editor-draft");
    }
    @Test public void rotationRetainsUnaddedTypedRangeWithoutCreatingAClip() throws Exception {
        long video=store.addExportedVideo("旋转状态夹具",Uri.fromFile(fixture).toString(),"video/mp4",4,100_000);
        Intent intent=new Intent(app,EditorActivity.class).putExtra("video_id",video).putExtra("position_ms",33_001L);
        controller=Robolectric.buildActivity(EditorActivity.class,intent).create().start(); EditorActivity activity=controller.get(); drain(activity);
        assertEquals("00:33.001",field(activity,"startInput",EditText.class).getText().toString());
        field(activity,"startInput",EditText.class).setText("33.125"); field(activity,"endInput",EditText.class).setText("50.250");
        Bundle state=new Bundle(); controller.saveInstanceState(state); ExecutorService oldWorker=field(activity,"io",ExecutorService.class);
        controller.stop().destroy(); assertTrue(oldWorker.awaitTermination(5,TimeUnit.SECONDS));
        controller=Robolectric.buildActivity(EditorActivity.class,intent).create(state).start(); activity=controller.get(); drain(activity);
        assertEquals("33.125",field(activity,"startInput",EditText.class).getText().toString());
        assertEquals("50.250",field(activity,"endInput",EditText.class).getText().toString()); assertTrue(store.projects().isEmpty());
    }
    @Test public void measuredDurationPersistsBeforeAddingPastRoundedDownloadDuration() throws Exception {
        long video=store.addExportedVideo("毫秒时长夹具",Uri.fromFile(fixture).toString(),"video/mp4",4,100_000);
        long project=store.createProject("保留最后八百毫秒");
        Intent intent=new Intent(app,EditorActivity.class).putExtra("video_id",video).putExtra("project_id",project);
        controller=Robolectric.buildActivity(EditorActivity.class,intent).create().start();
        EditorActivity activity=controller.get(); drain(activity);
        assertEquals(100_000,store.video(video).optLong("durationMs"));
        // Exercise the production STATE_READY handler without pretending the
        // invalid view fixture was decoded by a device codec.
        activity.onMeasuredDuration(100_800);
        field(activity,"startInput",EditText.class).setText("100.000");
        field(activity,"endInput",EditText.class).setText("100.500");
        field(activity,"addButton",Button.class).performClick(); drain(activity);
        assertEquals(100_800,store.video(video).optLong("durationMs"));
        assertEquals(1,store.clips(project).size());
        assertEquals(100_500,store.clips(project).get(0).optLong("endMs"));
    }
    private static void rejectsRange(String start,String end,long duration) {
        try { EditorActivity.validatedRange(start,end,duration); fail("Accepted invalid range"); } catch (IllegalArgumentException expected) {}
    }
    private static void drain(EditorActivity activity) throws Exception {
        ExecutorService worker=field(activity,"io",ExecutorService.class);
        for (int i=0;i<4;i++) { worker.submit(()->{}).get(5,TimeUnit.SECONDS); Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    }
    private static <T> T field(Object target,String name,Class<T> type) throws Exception { Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
    private static void assertWhite(EditorActivity activity) throws Exception {
        View root=field(activity,"root",View.class); assertTrue(root.getBackground() instanceof ColorDrawable); assertEquals(UiTheme.get(activity).bg,((ColorDrawable)root.getBackground()).getColor());
    }
    private static void capture(EditorActivity activity,String name) throws Exception {
        View decor=activity.getWindow().getDecorView(); float density=decor.getResources().getDisplayMetrics().density;
        int width=Math.round(412*density),height=Math.round(915*density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); decor.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); decor.draw(new Canvas(bitmap));
        File directory=new File(System.getProperty("streampocket.ui.output","build/ui-render")); if (!directory.isDirectory()&&!directory.mkdirs()) throw new java.io.IOException("Cannot create UI output directory");
        try (FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)); } bitmap.recycle();
    }
}
