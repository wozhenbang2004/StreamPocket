package dev.streampocket;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Isolated catalogue/legacy-queue fixtures; no accounts, downloads or real user files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class LibraryStoreTest {
    private Context app;
    private LibraryStore store;
    private File fixture;

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("media-library.db"); app.getSharedPreferences("tasks", 0).edit().clear().commit();
        fixture = File.createTempFile("library-fixture-", ".mp4", app.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(fixture)) { out.write(new byte[]{0, 0, 0, 0}); }
        store = new LibraryStore(app);
        Field recovery = LibraryStore.class.getDeclaredField("exportsRecovered"); recovery.setAccessible(true); recovery.setBoolean(null, false);
    }
    @After public void teardown() { store.close(); fixture.delete(); app.deleteDatabase("media-library.db"); }
    private JSONObject completed(String taskId, String uri, String title) throws Exception {
        return new JSONObject().put("id", taskId).put("uri", uri).put("title", title).put("mime", "video/mp4").put("status", "completed")
                .put("size", 1024).put("url", "https://x.com/fixture/status/123").put("completedAt", 123456)
                .put("media", new JSONObject().put("duration", 100).put("uploader", "fixture-author"));
    }
    private JSONObject completed(String taskId) throws Exception { return completed(taskId, Uri.fromFile(fixture).toString(), "本地测试视频"); }

    @Test public void nextVideoUsesStableOrderingFiltersAndCrossesLoadedPageBoundaries()throws Exception{
        for(int n=0;n<65;n++)store.upsertCompletedDownload(completed("next-"+n,Uri.fromFile(new File(app.getCacheDir(),"next-"+n+".mp4")).toString(),"连续播放样例 "+n).put("completedAt",1000+n/2).put("url",n%3==0?"https://b23.tv/fixture":"https://x.com/u/status/"+(100+n)));
        List<JSONObject> page=store.listVideos("","",0,30,0),rest=store.listVideos("","",0,30,30);assertEquals(rest.get(0).optLong("id"),store.nextVideoId(page.get(29).optLong("id"),"",null,0,"date"));
        List<JSONObject> sorted=store.listVideos("","",0,100,0,"source");for(int n=0;n<sorted.size()-1;n++)assertEquals(sorted.get(n+1).optLong("id"),store.nextVideoId(sorted.get(n).optLong("id"),"",null,0,"source"));assertEquals(0,store.nextVideoId(sorted.get(sorted.size()-1).optLong("id"),"",null,0,"source"));
        List<JSONObject> filtered=store.listVideos("样例","x",0,100,0);assertEquals(filtered.get(1).optLong("id"),store.nextVideoId(filtered.get(0).optLong("id"),"样例",java.util.Collections.singletonList("x"),0,"date"));
        long group=store.createCollection("连续播放选集");long first=page.get(0).optLong("id"),last=rest.get(5).optLong("id");store.setCollection(first,group,true);store.setCollection(last,group,true);
        assertEquals(last,store.nextVideoId(first,"",null,group,"date"));assertEquals(0,store.nextVideoId(last,"",null,group,"date"));assertEquals(0,store.nextVideoId(first,"不存在的标题",null,0,"date"));
    }

    @Test public void removedClipCanBeRestoredAtItsPositionWithTheSameIdentity()throws Exception{
        long video=store.upsertCompletedDownload(completed("undo"));long project=store.createProject("撤销样例");long first=store.addClip(project,video,1000,2000),second=store.addClip(project,video,3000,5000);
        JSONObject saved=store.clips(project).get(0);store.removeClip(first);store.restoreClip(saved);store.restoreClip(saved);
        java.util.List<JSONObject> restored=store.clips(project);assertEquals(2,restored.size());assertEquals(first,restored.get(0).optLong("id"));assertEquals(second,restored.get(1).optLong("id"));assertEquals(1000,restored.get(0).optLong("startMs"));assertEquals(2000,restored.get(0).optLong("endMs"));
        store.removeClip(first);store.removeVideoRecord(video);store.restoreClip(saved);assertEquals(0,store.clips(project).get(0).optLong("videoId"));assertEquals(saved.optString("uri"),store.clips(project).get(0).optString("uri"));
    }
    @Test public void existingQueueImportsOnlyCompletedAndDeduplicatesTaskAndUri() throws Exception {
        JSONObject done = completed("done");
        JSONObject duplicateUri = completed("duplicate");
        JSONObject unfinished = completed("unfinished", "content://fixture/not-complete", "未完成").put("status", "merging");
        app.getSharedPreferences("tasks", 0).edit().putString("items", new JSONArray().put(done).put(duplicateUri).put(unfinished).toString()).commit();
        store.syncCompletedDownloads(); store.syncCompletedDownloads();
        List<JSONObject> videos = store.listVideos("", "", 0, 30, 0); assertEquals(1, videos.size());
        long id = videos.get(0).optLong("id"); assertEquals(id, store.upsertCompletedDownload(done));
        assertEquals(100_000, videos.get(0).optLong("durationMs")); assertEquals("x", videos.get(0).optString("source"));
        TaskStore.remove(app, "done"); TaskStore.remove(app, "duplicate");
        assertNotNull("Queue history deletion must not erase the library", store.video(id));
    }

    @Test public void removalKeepsFileAndLedgerWhileExplicitReimportRestoresRecord() throws Exception {
        JSONObject task = completed("one");
        app.getSharedPreferences("tasks", 0).edit().putString("items", new JSONArray().put(task).toString()).commit();
        long id = store.upsertCompletedDownload(task); store.addMarker(id, 10_000, "片段");
        long project = store.createProject("保留草稿"); store.addClip(project, id, 10_000, 20_000);
        store.removeVideoRecord(id); store.syncCompletedDownloads();
        assertTrue(fixture.exists()); assertTrue(store.listVideos("", "", 0, 30, 0).isEmpty()); assertTrue(store.markers(id).isEmpty());
        assertEquals(Uri.fromFile(fixture).toString(), store.clips(project).get(0).optString("uri"));
        assertEquals(0, store.clips(project).get(0).optLong("videoId"));
        long restored = store.upsertCompletedDownload(task); assertTrue(restored > id); assertNotNull(store.video(restored));
    }

    @Test public void markerTimesPersistInOriginalMillisecondsAndCascadeWithVideo() throws Exception {
        long id = store.upsertCompletedDownload(completed("one"));
        long later = store.addMarker(id, 77_123, "高潮"); long earlier = store.addMarker(id, 5_001, "");
        store.renameMarker(earlier, "  开场  "); store.savePlayback(id, 21_000); store.close(); store = new LibraryStore(app);
        List<JSONObject> marks = store.markers(id); assertEquals(2, marks.size());
        assertEquals(5_001, marks.get(0).optLong("positionMs")); assertEquals("开场", marks.get(0).optString("label"));
        assertEquals(77_123, marks.get(1).optLong("positionMs")); assertEquals(21_000, store.video(id).optLong("playbackMs"));
        store.deleteMarker(later); assertEquals(1, store.markers(id).size());
        expectInvalid(() -> store.addMarker(id, -1, "")); expectInvalid(() -> store.addMarker(id, 100_001, ""));
    }

    @Test public void rangeHighlightsPersistBothBoundariesAndKeepPointCompatibility() throws Exception {
        long video = store.upsertCompletedDownload(completed("ranges"));
        long range = store.addRangeMarker(video, 15_123, 29_876, "精彩片段"); store.addMarker(video, 1000, "位置标记");
        expectInvalid(() -> store.addRangeMarker(video, -1, 1000, ""));
        expectInvalid(() -> store.addRangeMarker(video, 5000, 5000, ""));
        expectInvalid(() -> store.addRangeMarker(video, 5000, 4000, ""));
        expectInvalid(() -> store.addRangeMarker(video, 90_000, 100_001, ""));
        store.close(); store = new LibraryStore(app);
        List<JSONObject> markers = store.markers(video); assertEquals(2, markers.size());
        assertEquals(0, markers.get(0).optLong("endMs"));
        assertEquals(range, markers.get(1).optLong("id")); assertEquals(15_123, markers.get(1).optLong("positionMs"));
        assertEquals(15_123, markers.get(1).optLong("startMs")); assertEquals(29_876, markers.get(1).optLong("endMs"));
        long unknown = store.addExportedVideo("未知时长", "content://fixture/unknown-duration", "video/mp4", 0, 0);
        expectInvalid(() -> store.addRangeMarker(unknown, 0, 1000, ""));
    }

    @Test public void collectionsAndLiteralSearchKeepSourceAndGroupingIndependent() throws Exception {
        long id = store.upsertCompletedDownload(completed("one", Uri.fromFile(fixture).toString(), "100%_真实 ' 标题"));
        long group = store.createCollection("收藏"); assertEquals(group, store.createCollection(" 收藏 "));
        store.setCollection(id, group, true); store.setCollection(id, group, true);
        assertEquals(1, store.collections().get(0).optLong("count"));
        assertEquals(1, store.listVideos("%_", "x", group, 30, 0).size());
        assertTrue(store.listVideos("%_", "bilibili", group, 30, 0).isEmpty());
        assertTrue(store.listVideos("' OR 1=1 --", "", 0, 30, 0).isEmpty());
        store.setCollection(id, group, false); assertTrue(store.listVideos("", "", group, 30, 0).isEmpty());
        expectInvalid(() -> store.createCollection("  "));
    }

    @Test public void clipRangesAndOrderingRemainValidAcrossMoveRemoveAndReopen() throws Exception {
        long video = store.upsertCompletedDownload(completed("one")); long project = store.createProject("精彩合集");
        long a = store.addClip(project, video, 0, 10_000), b = store.addClip(project, video, 20_000, 30_000), c = store.addClip(project, video, 50_000, 65_000);
        store.moveClip(project, c, -2); assertOrder(project, c, a, b);
        store.moveClip(project, c, Integer.MAX_VALUE); assertOrder(project, a, b, c);
        store.removeClip(b); assertOrder(project, a, c);
        long other = store.createProject("另一草稿"); store.moveClip(other, c, -1); assertOrder(project, a, c);
        store.close(); store = new LibraryStore(app); assertOrder(project, a, c);
        assertEquals(15_000, store.clips(project).get(1).optLong("durationMs"));
        expectInvalid(() -> store.addClip(project, video, -1, 100)); expectInvalid(() -> store.addClip(project, video, 1000, 1000));
        expectInvalid(() -> store.addClip(project, video, 1, 100_001));
    }

    @Test public void discoveredMetadataClampsPlaybackAndValidatesFutureClipBounds() throws Exception {
        long id = store.upsertCompletedDownload(completed("one")); store.savePlayback(id, 95_000);
        store.updateMetadata(id, 80_000, 1920, 1080);
        JSONObject video = store.video(id); assertEquals(80_000, video.optLong("durationMs")); assertEquals(80_000, video.optLong("playbackMs")); assertEquals(1920, video.optInt("width"));
        long project = store.createProject("范围"); expectInvalid(() -> store.addClip(project, id, 0, 80_001));
        store.savePlayback(id, -10); assertEquals(0, store.video(id).optLong("playbackMs"));
    }

    @Test public void missingFilesKeepRecordsMarkersAndDrafts() throws Exception {
        long id = store.upsertCompletedDownload(completed("one")); long project = store.createProject("草稿");
        store.addMarker(id, 100, "保留"); store.addClip(project, id, 0, 1000); fixture.delete();
        assertTrue(store.video(id).optBoolean("missing")); assertEquals(1, store.markers(id).size()); assertEquals(1, store.clips(project).size());
        assertEquals(1, store.listVideos("", "", 0, 10, 0).size());
    }

    @Test public void recoveryRunsOnceAndNeverInterruptsNewLiveExport() throws Exception {
        long project = store.createProject("导出"); store.updateProjectExport(project, "exporting", 25, "旧进程", null);
        store.recoverInterruptedExports(); assertEquals("interrupted", store.project(project).optString("exportState"));
        store.updateProjectExport(project, "exporting", 40, "当前进程", null);
        try (LibraryStore second = new LibraryStore(app)) { second.recoverInterruptedExports(); assertEquals("exporting", second.project(project).optString("exportState")); }
        store.updateProjectExport(project, "completed", 999, "完成", "content://fixture/export");
        assertEquals(100, store.project(project).optInt("exportProgress"));
        long output = store.addExportedVideo("成片", "content://fixture/export", "video/mp4", 123, 30_000);
        assertEquals(output, store.addExportedVideo("成片", "content://fixture/export", "video/mp4", 123, 30_000));
        assertEquals("export", store.video(output).optString("source")); assertEquals(1, store.listVideos("", "export", 0, 10, 0).size());
    }

    @Test public void recoveryAlsoFinishesAnAbandonedCancellationWithoutChangingCompletedExports() {
        long abandoned = store.createProject("上次取消中"), completed = store.createProject("已有成片");
        store.updateProjectExport(abandoned, "cancelling", -1, "正在取消", null);
        store.updateProjectExport(completed, "completed", 100, "已保存", "content://fixture/completed");
        store.recoverInterruptedExports();
        assertEquals("interrupted", store.project(abandoned).optString("exportState"));
        assertEquals("completed", store.project(completed).optString("exportState"));
        assertEquals("content://fixture/completed", store.project(completed).optString("outputUri"));
    }

    private void assertOrder(long project, long... expected) {
        List<JSONObject> clips = store.clips(project); assertEquals(expected.length, clips.size());
        for (int i = 0; i < expected.length; i++) { assertEquals(expected[i], clips.get(i).optLong("id")); assertEquals(i, clips.get(i).optInt("position")); }
    }
    private static void expectInvalid(Runnable action) { try { action.run(); fail("Invalid bounds should be rejected"); } catch (IllegalArgumentException expected) {} }
}
