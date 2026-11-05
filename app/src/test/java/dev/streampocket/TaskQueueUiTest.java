package dev.streampocket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
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

    @Test public void filterClicksRefreshRowsWithoutDetachingTabsOrCancellingTheirMotion() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        SharedPreferences config=app.getSharedPreferences("config",0);
        boolean hadMotionPreference=config.contains("motion_enabled"),previousMotion=config.getBoolean("motion_enabled",true);
        config.edit().putBoolean("motion_enabled",true).commit();
        try {
            MainActivity activity=controller.create(initialState()).start().resume().visible().get();
            seed(new JSONArray().put(task("filter-waiting","样例 · 等待筛选","queued",1,1))
                    .put(task("filter-completed","样例 · 已完成筛选","completed",2,2).put("progress",100))
                    .put(task("filter-failed","样例 · 失败筛选","failed",3,3)));
            showQueue(activity);
            View decor=activity.getWindow().getDecorView();
            int width=Math.round(decor.getResources().getConfiguration().screenWidthDp*decor.getResources().getDisplayMetrics().density);
            int height=Math.round(decor.getResources().getConfiguration().screenHeightDp*decor.getResources().getDisplayMetrics().density);
            Runnable layout=()->{decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));decor.layout(0,0,width,height);};
            layout.run();
            PocketTabs tabs=(PocketTabs)field(activity,"queueTabs");ViewGroup body=(ViewGroup)field(activity,"body");
            View scroll=body.getChildAt(0);Object tabParent=tabs.getParent();
            assertTrue(tabs.isAttachedToWindow());assertTrue("The fixture must exercise an actual selection animation",UiTheme.motionEnabled(activity));
            for(String title:new String[]{"样例 · 等待筛选","样例 · 已完成筛选","样例 · 失败筛选"})assertNotNull(findText(decor,title));
            int[] attachments={0},detachments={0};
            tabs.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(View view){attachments[0]++;}
                @Override public void onViewDetachedFromWindow(View view){detachments[0]++;}
            });
            TextView completed=findText(tabs,"已完成");assertNotNull(completed);assertTrue(completed.performClick());
            assertEquals("completed",field(activity,"queueFilter"));assertTrue(completed.isSelected());
            assertSame(tabs,field(activity,"queueTabs"));assertSame(tabParent,tabs.getParent());assertSame(scroll,body.getChildAt(0));
            assertTrue(tabs.isAttachedToWindow());assertEquals(0,detachments[0]);assertEquals(0,attachments[0]);
            assertNotNull(findText(decor,"样例 · 已完成筛选"));assertNull(findText(decor,"样例 · 等待筛选"));assertNull(findText(decor,"样例 · 失败筛选"));
            Field movement=PocketTabs.class.getDeclaredField("movement");movement.setAccessible(true);
            ValueAnimator animation=(ValueAnimator)movement.get(tabs);
            assertNotNull("The filter callback must not discard the in-flight selection",animation);assertTrue(animation.isStarted());
            View face=tabs.getChildAt(0);int beforeLeft=face.getLeft(),beforeRight=face.getRight();
            assertTrue("Selection must move toward the new filter instead of teleporting",beforeRight<=completed.getLeft());
            tabs.requestLayout();layout.run();
            assertSame("Refreshing the host layout must retain the running animation",animation,movement.get(tabs));assertTrue(animation.isStarted());
            assertEquals(beforeLeft,face.getLeft());assertEquals(beforeRight,face.getRight());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(260));
            assertFalse(animation.isStarted());assertTrue(tabs.isAttachedToWindow());assertEquals(0,detachments[0]);assertEquals(0,attachments[0]);
            assertEquals((completed.getLeft()+completed.getRight())/2f,(face.getLeft()+face.getRight())/2f,1f);
            TextView failed=findText(tabs,"失败");assertNotNull(failed);assertTrue(failed.performClick());
            assertEquals("failed",field(activity,"queueFilter"));assertNotNull(findText(decor,"样例 · 失败筛选"));
            assertNull(findText(decor,"样例 · 等待筛选"));assertNull(findText(decor,"样例 · 已完成筛选"));
            assertSame(tabs,field(activity,"queueTabs"));assertSame(tabParent,tabs.getParent());assertSame(scroll,body.getChildAt(0));
            assertTrue(tabs.isAttachedToWindow());assertEquals(0,detachments[0]);assertEquals(0,attachments[0]);
        } finally {
            controller.pause().stop().destroy();
            if(hadMotionPreference)config.edit().putBoolean("motion_enabled",previousMotion).commit();else config.edit().remove("motion_enabled").commit();
        }
    }

    @Test public void globalHoldShowsWaitingOrderManualPausesAndARealStoppingState() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity activity=controller.create(initialState()).start().resume().visible().get();
            seed(new JSONArray().put(task("a","样例 · 暂停队列后仍保留顺序","paused",1,1).put("pauseOrigin","queue"))
                    .put(task("b","样例 · 后来新加入的下载","paused",2,2).put("pauseOrigin","queue"))
                    .put(task("manual","样例 · 我单独暂停的任务","paused",3,3).put("pauseOrigin","manual")));
            app.getSharedPreferences("tasks",0).edit().putBoolean("queuePaused",true).commit();showQueue(activity);
            View decor=activity.getWindow().getDecorView();assertNotNull(findText(decor,"队列已暂停"));assertTrue(findText(decor,"继续队列").isEnabled());
            assertNotNull(findText(decor,"等待继续 · 2 项"));assertNotNull(findText(decor,"单独暂停 · 1 项"));
            assertNotNull(findText(decor,"等待继续 · 第 1 位"));assertNotNull(findText(decor,"等待继续 · 第 2 位"));assertEquals(0,bars(decor));
            capture(decor,"050-queue-held-and-manual-fixture");
            TaskStore.update(app,"a",new JSONObject().put("status","pausing").put("pauseRequested",true));showQueue(activity);
            assertFalse(findText(decor,"暂停中…").isEnabled());assertNull(findText(decor,"继续队列"));
            assertNotNull(findText(decor,"当前任务停止后可继续；已下载的数据保留"));capture(decor,"050-queue-stopping-fixture");
        } finally { controller.pause().stop().destroy(); }
    }

    @Test @Config(qualifiers="w320dp-h640dp-xhdpi")
    public void multiSelectionKeepsStableIdsDuringRefreshAndFiltersEachBatchAction() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","dark").commit();
        try {
            MainActivity activity=controller.create(initialState()).start().resume().visible().get();
            seed(new JSONArray().put(task("waiting","样例 · 一个较长的等待标题，在窄屏选择模式中仍可读","queued",1,1))
                    .put(task("paused","样例 · 手动暂停","paused",2,2)).put(task("failed","样例 · 网络失败","failed",3,3))
                    .put(task("interrupted","样例 · 系统中断","interrupted",4,4)).put(task("cancelled","样例 · 已主动取消","cancelled",5,5)));
            showQueue(activity);findText(activity.getWindow().getDecorView(),"选择").performClick();findText(activity.getWindow().getDecorView(),"全选").performClick();
            View decor=activity.getWindow().getDecorView();assertNotNull(findText(decor,"已选 5 项"));assertTrue(findText(decor,"操作").isEnabled());
            @SuppressWarnings("unchecked") Set<String> selected=(Set<String>)field(activity,"queueSelection");
            assertEquals(new LinkedHashSet<>(Arrays.asList("waiting","paused","failed","interrupted","cancelled")),selected);
            assertEquals(Arrays.asList("waiting"),eligible(activity,DownloadService.PAUSE,selected));assertEquals(Arrays.asList("paused"),eligible(activity,DownloadService.RESUME,selected));
            assertEquals(Arrays.asList("failed","interrupted"),eligible(activity,DownloadService.RETRY_FAILED,selected));
            TaskStore.update(app,"waiting",new JSONObject().put("status","downloading").put("phase","video"));
            invoke(activity,"refreshQueue",new Class<?>[]{boolean.class},true);assertEquals(5,selected.size());
            capture(decor,"050-queue-selection-narrow-dark-fixture");
            Bundle saved=new Bundle();controller.saveInstanceState(saved);assertTrue(saved.getBoolean("queue_selecting"));assertEquals(5,saved.getStringArrayList("queue_selection").size());
            activity.onBackPressed();assertNotNull(findText(decor,"选择"));assertNull(findText(decor,"已选 5 项"));assertTrue(selected.isEmpty());
            assertNull("Selecting, refreshing and leaving selection must not start downloads",Shadows.shadowOf(activity).getNextStartedService());
        } finally { controller.pause().stop().destroy();app.getSharedPreferences("config",0).edit().remove("appearance_theme").commit(); }
    }

    @Test public void failedBatchShortcutExcludesCancellationAndPreservesExistingTaskIds() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity activity=controller.create(initialState()).start().resume().visible().get();
            seed(new JSONArray().put(task("failed","样例 · 失败","failed",1,1)).put(task("interrupted","样例 · 中断","interrupted",2,2))
                    .put(task("cancelled","样例 · 取消","cancelled",3,3)));
            setField(activity,"queueFilter","failed");showQueue(activity);
            TextView retry=findText(activity.getWindow().getDecorView(),"重试失败与中断 · 2 项");assertNotNull(retry);retry.performClick();
            Intent command=Shadows.shadowOf(activity).getNextStartedService();assertNotNull(command);assertEquals(DownloadService.RETRY_FAILED,command.getAction());
            assertEquals(Arrays.asList("failed","interrupted"),command.getStringArrayListExtra("ids"));assertEquals(3,TaskStore.all(app).size());
            assertEquals("cancelled",TaskStore.get(app,"cancelled").getString("status"));
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void bulkRecordRemovalHasOneUndoAndDoesNotTouchFilesOrActiveWork() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        File savedFile=new File(app.getFilesDir(),"explicit-queue-removal-fixture.mp4");
        try {
            try(FileOutputStream out=new FileOutputStream(savedFile)){out.write(new byte[]{1,2,3});}
            MainActivity activity=controller.create(initialState()).start().resume().visible().get();
            seed(new JSONArray().put(task("saved","样例 · 已保存文件","completed",1,1).put("uri",savedFile.toURI().toString()))
                    .put(task("failed","样例 · 失败记录","failed",2,2)).put(task("active","样例 · 下载中","downloading",3,3)));
            showQueue(activity);invoke(activity,"removeTasksWithUndo",new Class<?>[]{List.class},Arrays.asList("saved","failed","active"));
            View decor=activity.getWindow().getDecorView();assertNotNull(findText(decor,"5秒后移除 2 条记录，文件保留"));assertNull(findText(decor,"样例 · 已保存文件"));
            assertNotNull(TaskStore.get(app,"saved"));findText(decor,"撤销").performClick();assertNotNull(findText(decor,"样例 · 已保存文件"));
            invoke(activity,"removeTasksWithUndo",new Class<?>[]{List.class},Arrays.asList("saved","failed","active"));
            invoke(activity,"commitPendingRemovals",new Class<?>[]{boolean.class},true);
            assertNull(TaskStore.get(app,"saved"));assertNull(TaskStore.get(app,"failed"));assertNotNull(TaskStore.get(app,"active"));assertTrue(savedFile.isFile());
        } finally { controller.pause().stop().destroy();savedFile.delete(); }
    }

    @Test public void continueQueueCommitsHiddenRemovalsBeforeAnyTaskCanRestart() throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity activity=controller.create(initialState()).start().resume().visible().get();
            seed(new JSONArray().put(task("removed","样例 · 不应重新开始","paused",1,1).put("pauseOrigin","queue"))
                    .put(task("kept","样例 · 保留等待项","paused",2,2).put("pauseOrigin","queue")));
            app.getSharedPreferences("tasks",0).edit().putBoolean("queuePaused",true).commit();showQueue(activity);
            invoke(activity,"removeTasksWithUndo",new Class<?>[]{List.class},Arrays.asList("removed"));
            assertNull(findText(activity.getWindow().getDecorView(),"样例 · 不应重新开始"));assertNotNull(TaskStore.get(app,"removed"));
            findText(activity.getWindow().getDecorView(),"继续队列").performClick();
            assertNull("The hidden row is committed before foreground service admission",TaskStore.get(app,"removed"));
            assertNull(findText(activity.getWindow().getDecorView(),"撤销"));
            Intent command=Shadows.shadowOf(activity).getNextStartedService();assertNotNull(command);assertEquals(DownloadService.RESUME_QUEUE,command.getAction());
            assertEquals(Arrays.asList("kept"),TaskStore.resumeQueue(app,null));assertEquals("queued",TaskStore.get(app,"kept").getString("status"));
        } finally { controller.pause().stop().destroy(); }
    }

    @SuppressWarnings("unchecked") private static List<String> eligible(MainActivity activity,String action,Set<String> selected) throws Exception {
        return (List<String>)invoke(activity,"eligibleQueueIds",new Class<?>[]{String.class,Set.class},action,selected);
    }
    private static Object invoke(MainActivity activity,String name,Class<?>[] types,Object...args) throws Exception { Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(activity,args); }
    private static Object field(MainActivity activity,String name) throws Exception { Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity); }
    private static void setField(MainActivity activity,String name,Object value) throws Exception { Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(activity,value); }

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
        int width = Math.round(decor.getResources().getConfiguration().screenWidthDp * decor.getResources().getDisplayMetrics().density);
        int height = Math.round(decor.getResources().getConfiguration().screenHeightDp * decor.getResources().getDisplayMetrics().density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
        File output = new File(name.startsWith("050-")?"build/reports/view-fixtures/050-queue":"docs/design/assets/020-queue"); if (!output.isDirectory() && !output.mkdirs()) throw new java.io.IOException("Cannot create fixture render directory");
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); decor.draw(new Canvas(bitmap));
        try (FileOutputStream file = new FileOutputStream(new File(output, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, file)); }
        finally { bitmap.recycle(); }
    }
}
