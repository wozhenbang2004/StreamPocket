package dev.streampocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
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
import static org.junit.Assert.*;

/** Real task preference / native View integration, with explicitly synthetic records and no network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TaskQueueUiTest {
    private Context app;
    @Before public void clean() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("tasks", 0).edit().clear().commit();
        TaskStore.init(app);
    }
    @After public void removeFixtures() { app.getSharedPreferences("tasks", 0).edit().clear().commit(); }

    @Test @Config(qualifiers="w320dp-h800dp-xhdpi")
    public void fourDestinationLabelsStayReadableInANarrowPhone() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity activity=controller.create(initialState()).start().resume().visible().get();
            View decor=activity.getWindow().getDecorView(); int width=Math.round(320*decor.getResources().getDisplayMetrics().density);
            int height=Math.round(800*decor.getResources().getDisplayMetrics().density);
            decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            decor.layout(0,0,width,height);
            Field field=MainActivity.class.getDeclaredField("navigation"); field.setAccessible(true); View navigation=(View)field.get(activity);
            for(String label:new String[]{"新建","下载","视频库","设置"}) {
                TextView button=findText(navigation,label); assertNotNull(button); assertEquals(1,button.getLineCount());
                assertTrue("Destination text must fit without clipping: "+label,
                    button.getPaint().measureText(label)<=button.getWidth()-button.getPaddingLeft()-button.getPaddingRight());
            }
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void acceptedRetryPersistsAfterWaitersAndClearsOldRateAndPhase() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject waiting = task("waiting-fixture", "样例 · 已在等待", "queued", 20, now - 2);
        JSONObject retry = task("retry-fixture", "样例 · 较早创建后重试", "failed", 1, now - 3)
                .put("phase", "audio").put("progress", 92).put("stageProgress", 20).put("speedBytesPerSecond", 3145728)
                .put("etaSeconds", 15).put("completedStreams", 1).put("transferComplete", true).put("userError", "合成错误样例");
        JSONObject complete = task("completed-fixture", "样例 · 已保存", "completed", 5, 5)
                .put("uri", "content://media/external/downloads/999991").put("progress", 100);
        seed(new JSONArray().put(retry).put(complete).put(waiting));
        assertTrue(TaskStore.markQueued(app, "retry-fixture"));
        JSONObject accepted = TaskStore.get(app, "retry-fixture");
        assertTrue(accepted.getLong("queuedAt") > waiting.getLong("queuedAt"));
        assertEquals("queued", accepted.getString("phase"));
        assertEquals(-1, accepted.getDouble("progress"), 0);
        assertEquals(-1, accepted.getDouble("stageProgress"), 0);
        assertEquals(-1, accepted.getDouble("speedBytesPerSecond"), 0);
        assertEquals(-1, accepted.getLong("etaSeconds"));
        assertEquals(0, accepted.getInt("completedStreams"));
        assertFalse(accepted.getBoolean("transferComplete"));
        assertEquals("", accepted.getString("userError"));
        assertEquals("waiting-fixture", TaskStore.all(app).get(0).getString("id"));
        assertEquals("retry-fixture", TaskStore.all(app).get(1).getString("id"));
        SharedPreferences preferences = app.getSharedPreferences("tasks", 0);
        JSONArray persisted = new JSONArray(preferences.getString("items", "[]"));
        assertEquals(accepted.getLong("queuedAt"), byId(persisted, "retry-fixture").getLong("queuedAt"));
        assertEquals(accepted.getLong("queuedAt"), preferences.getLong("lastQueuedAt", -1));
        assertFalse("A committed output cannot be re-enqueued", TaskStore.markQueued(app, "completed-fixture"));
        assertEquals("completed", TaskStore.get(app, "completed-fixture").getString("status"));
        assertEquals(complete.getString("uri"), TaskStore.get(app, "completed-fixture").getString("uri"));
    }

    @Test public void nativeQueueShowsActiveFirstThenActualFifoPositionsWithoutWaitingBars() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity activity = controller.create(initialState()).start().resume().visible().get();
            JSONObject active = transferring("active-fixture", "样例 · 正在下载", false).put("createdAt", 1).put("queuedAt", 10);
            JSONObject first = task("first-fixture", "样例 · 先加入", "queued", 30, 100);
            JSONObject second = task("second-fixture", "样例 · 后加入", "queued", 20, 200);
            JSONObject history = task("history-fixture", "样例 · 历史记录", "completed", 50, 50).put("completedAt", 300).put("progress", 100);
            seed(new JSONArray().put(history).put(second).put(active).put(first));
            showQueue(activity);
            View decor = activity.getWindow().getDecorView();
            List<String> text = textValues(decor);
            assertTrue(text.indexOf("样例 · 正在下载") < text.indexOf("样例 · 先加入"));
            assertTrue(text.indexOf("样例 · 先加入") < text.indexOf("样例 · 后加入"));
            assertTrue(text.indexOf("样例 · 后加入") < text.indexOf("样例 · 历史记录"));
            assertNotNull(findText(decor, "等待下载 · 第 1 位"));
            assertNotNull(findText(decor, "等待下载 · 第 2 位"));
            assertEquals("Only the transferring task has a progress bar", 1, bars(decor));
            assertEquals(0, bars((View) findText(decor, "样例 · 先加入").getParent()));
            assertEquals(0, bars((View) findText(decor, "样例 · 后加入").getParent()));
            assertTrue(joinedText(decor).contains("速度测算中"));
            capture(decor, "020-queue-fifo-fixture");
            seed(new JSONArray().put(second).put(first)); showQueue(activity);
            assertEquals("Waiting alone must not look like bytes are downloading", 0, bars(decor));
            capture(decor, "020-queue-waiting-only-fixture");
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void queueRateUsesActualProgressModelAndUnknownSpeedStaysExplicit() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity activity = controller.create(initialState()).start().resume().visible().get();
            seed(new JSONArray().put(transferring("speed-fixture", "样例 · 实时下载速度", false))); showQueue(activity);
            View decor = activity.getWindow().getDecorView();
            assertTrue(joinedText(decor).contains("速度测算中"));
            assertFalse("Unknown rate must not be rendered as zero", joinedText(decor).contains("0 KB/s"));
            seed(new JSONArray().put(transferring("speed-fixture", "样例 · 实时下载速度", true))); showQueue(activity);
            assertTrue(joinedText(decor).contains("2.4 MB/s"));
            assertTrue(joinedText(decor).contains("本阶段约 30 秒"));
            assertFalse(joinedText(decor).contains("速度测算中"));
            capture(decor, "020-queue-live-speed-fixture");
        } finally { controller.pause().stop().destroy(); }
    }

    private void seed(JSONArray tasks) { app.getSharedPreferences("tasks", 0).edit().putString("items", tasks.toString()).commit(); }
    private static JSONObject task(String id, String title, String status, long created, long queued) throws Exception {
        return new JSONObject().put("id", id).put("title", title).put("status", status).put("createdAt", created).put("queuedAt", queued)
                .put("url", "https://example.invalid/explicit-ui-fixture").put("progress", -1).put("detail", "界面测试样例，未执行下载")
                .put("userError", "").put("uri", "");
    }
    private static JSONObject transferring(String id, String title, boolean knownRate) throws Exception {
        DownloadProgress model = new DownloadProgress();
        model.accept(DownloadProgress.PLAN_PREFIX + new JSONObject().put("single", new JSONObject().put("format_id", "v")
                .put("vcodec", "h264").put("acodec", "none").put("filesize", 100 * 1048576)));
        JSONObject progress = new JSONObject().put("status", "downloading").put("downloaded_bytes", 28 * 1048576).put("total_bytes", 100 * 1048576);
        if (knownRate) progress.put("speed", 2.4 * 1048576).put("eta", 30);
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + new JSONObject().put("info", new JSONObject().put("format_id", "v")
                .put("vcodec", "h264").put("acodec", "none")).put("progress", progress));
        JSONObject task = task(id, title, "downloading", 1, 1), state = model.snapshot().toJson();
        Iterator<String> keys = state.keys(); while (keys.hasNext()) { String key = keys.next(); task.put(key, state.get(key)); }
        return task;
    }
    private static JSONObject byId(JSONArray values, String id) throws Exception {
        for (int i = 0; i < values.length(); i++) if (values.getJSONObject(i).optString("id").equals(id)) return values.getJSONObject(i);
        throw new AssertionError("Missing fixture " + id);
    }
    private static Bundle initialState() { Bundle state = new Bundle(); state.putString("tab", "new"); state.putString("draft", ""); return state; }
    private static void showQueue(MainActivity activity) throws Exception {
        Method tab = MainActivity.class.getDeclaredMethod("showTab", String.class); tab.setAccessible(true); tab.invoke(activity, "queue");
        Field worker = MainActivity.class.getDeclaredField("io"); worker.setAccessible(true);
        ((ExecutorService) worker.get(activity)).submit(() -> {}).get(15, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = findText(((ViewGroup) view).getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }
    private static List<String> textValues(View view) {
        List<String> result = new ArrayList<>(); if (view instanceof TextView) result.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.addAll(textValues(((ViewGroup) view).getChildAt(i)));
        return result;
    }
    private static String joinedText(View view) { StringBuilder text = new StringBuilder(); for (String value : textValues(view)) text.append(value).append('\n'); return text.toString(); }
    private static int bars(View view) {
        int count = view instanceof ProgressBar ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += bars(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private static void capture(View decor, String name) throws Exception {
        int width = Math.round(412 * decor.getResources().getDisplayMetrics().density);
        int height = Math.round(915 * decor.getResources().getDisplayMetrics().density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
        File output = new File("docs/design/assets/020-queue"); if (!output.isDirectory() && !output.mkdirs()) throw new java.io.IOException("Cannot create fixture render directory");
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); decor.draw(new Canvas(bitmap));
        try (FileOutputStream file = new FileOutputStream(new File(output, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, file)); }
        finally { bitmap.recycle(); }
    }
}
