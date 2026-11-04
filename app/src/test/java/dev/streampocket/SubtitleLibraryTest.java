package dev.streampocket;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public final class SubtitleLibraryTest {
    private Context app;
    private LibraryStore store;
    private final List<File> fixtures=new ArrayList<>();

    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("library_rename",0).edit().clear().commit();
        store=new LibraryStore(app);
    }
    @After public void teardown() {
        store.close();app.deleteDatabase("media-library.db");
        for(File file:fixtures)file.delete();
    }

    @Test public void attachmentsBelongToTheExactVideoAndNeverToItsTitle() throws Exception {
        JSONObject one=completed("one",file(".mp4")),two=completed("two",file(".mp4"));
        JSONObject zh=attachment(file(".srt"),"same.zh-Hans.srt","text/plain");
        JSONObject en=attachment(file(".vtt"),"same.en.vtt","text/plain");
        one.put("attachments",new JSONArray().put(zh).put(zh)
                .put(attachment(file(".jpg"),"cover.jpg","image/jpeg"))
                .put(new JSONObject().put("uri","https://example.invalid/remote.srt").put("name","remote.srt"))
                .put(new JSONObject().put("uri",one.optString("uri")).put("name","wrong.srt")));
        two.put("attachments",new JSONArray().put(en));queue(one,two);store.syncCompletedDownloads();
        long first=store.upsertCompletedDownload(one),second=store.upsertCompletedDownload(two);
        assertNotEquals(first,second);assertEquals(1,store.subtitles(first).size());assertEquals(1,store.subtitles(second).size());
        assertEquals(zh.optString("uri"),store.subtitles(first).get(0).optString("uri"));
        assertEquals(en.optString("uri"),store.subtitles(second).get(0).optString("uri"));
        assertEquals("zh-Hans",store.subtitles(first).get(0).optString("language"));
        assertEquals("en",store.subtitles(second).get(0).optString("language"));
    }

    @Test public void lateSidecarsSurviveHistoryDeletionAndReopen() throws Exception {
        JSONObject task=completed("late",file(".mp4"));queue(task);long id=store.upsertCompletedDownload(task);
        assertTrue(store.subtitles(id).isEmpty());
        JSONObject caption=attachment(file(".srt"),"clip.zh-Hans.srt","application/octet-stream").put("preferred",true).put("label","简体中文 · 来源字幕");
        task.put("attachments",new JSONArray().put(caption));queue(task);store.syncCompletedDownloads();
        long subtitleId=store.subtitles(id).get(0).optLong("id");
        TaskStore.remove(app,"late");store.close();store=new LibraryStore(app);
        assertEquals(subtitleId,store.subtitles(id).get(0).optLong("id"));
        assertTrue(store.subtitles(id).get(0).optBoolean("preferred"));
        assertEquals("简体中文 · 来源字幕",store.subtitles(id).get(0).optString("label"));
        assertTrue(store.downloadedFiles().stream().anyMatch(file->caption.optString("uri").equals(file.optString("uri"))));
        assertTrue(store.referencesUri(caption.optString("uri")));
    }

    @Test public void relocationKeepsVideoAndSubtitleIdentityAfterHistoryWasCleared() throws Exception {
        JSONObject task=completed("move",file(".mp4"));JSONObject caption=attachment(file(".srt"),"original.en.srt","text/plain");
        task.put("attachments",new JSONArray().put(caption));queue(task);long id=store.upsertCompletedDownload(task);
        JSONObject original=store.subtitles(id).get(0);TaskStore.remove(app,"move");
        String movedVideo=Uri.fromFile(file(".mp4")).toString(),movedSubtitle=Uri.fromFile(file(".srt")).toString();
        store.relocateUri(task.optString("uri"),movedVideo);store.relocateUri(caption.optString("uri"),movedSubtitle);
        assertEquals(movedVideo,store.video(id).optString("uri"));
        JSONObject moved=store.subtitles(id).get(0);
        assertEquals(original.optLong("id"),moved.optLong("id"));assertEquals(movedSubtitle,moved.optString("uri"));
        assertEquals("en",moved.optString("language"));assertFalse(store.referencesUri(caption.optString("uri")));
        assertFalse(store.referencesUri(task.optString("uri")));assertTrue(store.referencesUri(movedSubtitle));
        assertTrue(store.downloadedFiles().stream().anyMatch(file->movedSubtitle.equals(file.optString("uri"))));
    }

    @Test public void removingCatalogueRowsCleansAssociationsWithoutDeletingFilesOrReimporting() throws Exception {
        File captionFile=file(".srt");JSONObject caption=attachment(captionFile,"clip.en.srt","text/plain");
        JSONObject one=completed("remove-one",file(".mp4")).put("attachments",new JSONArray().put(caption));
        JSONObject two=completed("remove-two",file(".mp4")).put("attachments",new JSONArray().put(attachment(file(".vtt"),"clip.en.vtt","text/vtt")));
        queue(one,two);long first=store.upsertCompletedDownload(one),second=store.upsertCompletedDownload(two);
        store.removeVideoRecord(first);store.removeVideoRecords(Arrays.asList(second));
        assertTrue(TaskStore.removeIfInactive(app,"remove-one"));TaskStore.remove(app,"remove-two");store.syncCompletedDownloads();
        assertTrue(store.subtitles(first).isEmpty());assertTrue(store.subtitles(second).isEmpty());
        assertNull(store.video(first));assertNull(store.video(second));assertTrue(captionFile.exists());
        assertFalse(store.referencesUri(caption.optString("uri")));
        long restored=store.upsertCompletedDownload(one);assertNotEquals(first,restored);assertEquals(1,store.subtitles(restored).size());
    }

    @Test public void missingHistoryDoesNotGuessAnAdjacentOrSameNamedSubtitle() throws Exception {
        File video=file(".mp4"),unrelated=new File(video.getParentFile(),video.getName().replace(".mp4",".en.srt"));
        Files.write(unrelated.toPath(),"1\n00:00:01,000 --> 00:00:02,000\nother video\n".getBytes(StandardCharsets.UTF_8));fixtures.add(unrelated);
        JSONObject task=completed("old",video);queue(task);long id=store.upsertCompletedDownload(task);TaskStore.remove(app,"old");
        assertTrue(store.subtitles(id).isEmpty());assertTrue(unrelated.exists());
    }

    @Test public void aReusedTaskIdWithAnotherPrimaryUriCannotAttachItsSubtitles() throws Exception {
        JSONObject original=completed("reused",file(".mp4"));long id=store.upsertCompletedDownload(original);
        JSONObject other=completed("reused",file(".mp4")).put("attachments",new JSONArray().put(attachment(file(".srt"),"other.en.srt","text/plain")));
        queue(other);store.syncCompletedDownloads();
        assertEquals(original.optString("uri"),store.video(id).optString("uri"));assertTrue(store.subtitles(id).isEmpty());
    }

    @Test public void interruptedSubtitleRelocationMergesStaleBackfillAndCanBeRetried() throws Exception {
        JSONObject caption=attachment(file(".srt"),"move.en.srt","text/plain").put("preferred",true).put("label","英语 · 平台字幕");
        JSONObject task=completed("interrupted",file(".mp4")).put("attachments",new JSONArray().put(caption));
        queue(task);long videoId=store.upsertCompletedDownload(task),trackId=store.subtitles(videoId).get(0).optLong("id");
        String oldUri=caption.optString("uri"),newUri=Uri.fromFile(file(".srt")).toString();
        // Library commit succeeded, but TaskStore still points at the original file.
        store.relocateUri(oldUri,newUri);
        assertEquals(oldUri,TaskStore.get(app,"interrupted").optJSONArray("attachments").getJSONObject(0).optString("uri"));
        store.syncCompletedDownloads();
        // Resume the same journal entry after the stale queue snapshot has been seen again.
        store.relocateUri(oldUri,newUri);TaskStore.relocateUri(app,oldUri,newUri);
        store.relocateUri(oldUri,newUri); // Replaying a recovered entry is also harmless.
        List<JSONObject> restored=store.subtitles(videoId);assertEquals(1,restored.size());
        assertEquals(trackId,restored.get(0).optLong("id"));assertEquals(newUri,restored.get(0).optString("uri"));
        assertTrue(restored.get(0).optBoolean("preferred"));assertEquals("英语 · 平台字幕",restored.get(0).optString("label"));
        assertEquals("en",restored.get(0).optString("language"));assertEquals("application/x-subrip",restored.get(0).optString("mime"));
        assertFalse(store.referencesUri(oldUri));assertFalse(TaskStore.referencesUri(app,oldUri));assertTrue(store.referencesUri(newUri));
    }

    @Test public void queueHistoryIsRetainedWhenSubtitlePreservationCannotCommit() throws Exception {
        JSONObject task=completed("preserve-failure",file(".mp4"));queue(task);long id=store.upsertCompletedDownload(task);
        task.put("attachments",new JSONArray().put(attachment(file(".srt"),"late.en.srt","text/plain")));queue(task);
        try(SQLiteDatabase db=app.openOrCreateDatabase("media-library.db",0,null)) {
            db.execSQL("CREATE TRIGGER block_subtitle_save BEFORE INSERT ON video_subtitle BEGIN SELECT RAISE(ABORT,'fixture subtitle write failure'); END");
        }
        assertFalse(TaskStore.removeIfInactive(app,"preserve-failure"));assertNotNull(TaskStore.get(app,"preserve-failure"));
        TaskStore.remove(app,"preserve-failure");assertNotNull(TaskStore.get(app,"preserve-failure"));
        try(SQLiteDatabase db=app.openOrCreateDatabase("media-library.db",0,null)){db.execSQL("DROP TRIGGER block_subtitle_save");}
        assertTrue(TaskStore.removeIfInactive(app,"preserve-failure"));assertNull(TaskStore.get(app,"preserve-failure"));
        assertEquals(1,store.subtitles(id).size());
    }

    @Test public void historyRemovalPreservesLateSubtitlesWithoutWaitingForTheLongFileCopyLock() throws Exception {
        JSONObject task=completed("copy-in-progress",file(".mp4"));queue(task);long id=store.upsertCompletedDownload(task);
        assertTrue(store.subtitles(id).isEmpty());
        JSONObject caption=attachment(file(".srt"),"late.en.srt","text/plain");
        task.put("attachments",new JSONArray().put(caption));queue(task);
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        Future<?> copy=workers.submit(()->{
            synchronized(DownloadMover.FILE_LOCK) {
                locked.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("Fixture file-copy lock was not released");
                } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }
        });
        try {
            assertTrue("Fixture must hold the long copy lock before deletion starts",locked.await(5,TimeUnit.SECONDS));
            Future<Boolean> removed=workers.submit(()->TaskStore.removeIfInactive(app,"copy-in-progress"));
            assertTrue("History removal must finish while file copying still holds FILE_LOCK",removed.get(5,TimeUnit.SECONDS));
            assertFalse("The copy lock must still be held when deletion completes",copy.isDone());
            assertNull(TaskStore.get(app,"copy-in-progress"));
        } finally {
            release.countDown();workers.shutdown();
            assertTrue("Both bounded fixture workers must finish",workers.awaitTermination(10,TimeUnit.SECONDS));
        }
        copy.get(1,TimeUnit.SECONDS);
        // There is no task left to lazily backfill: the deletion boundary must already have saved it.
        assertEquals(1,store.subtitles(id).size());
        assertEquals(caption.optString("uri"),store.subtitles(id).get(0).optString("uri"));
    }

    @Test public void historyRemovalCanBeTheFirstLibraryAccessAfterVersionOneOrTwoUpgrade() throws Exception {
        for(int version:new int[]{1,2}) {
            store.close();app.deleteDatabase("media-library.db");
            JSONObject task=completed("legacy",file(".mp4"));
            JSONObject caption=attachment(file(".srt"),"legacy.en.srt","text/plain");task.put("attachments",new JSONArray().put(caption));queue(task);
            try(SQLiteDatabase db=app.openOrCreateDatabase("media-library.db",0,null)) {
                db.execSQL("CREATE TABLE video (id INTEGER PRIMARY KEY AUTOINCREMENT,uri TEXT NOT NULL UNIQUE,title TEXT NOT NULL,mime TEXT NOT NULL,source TEXT NOT NULL,file_name TEXT NOT NULL DEFAULT '',duration_ms INTEGER NOT NULL DEFAULT 0,width INTEGER NOT NULL DEFAULT 0,height INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,playback_ms INTEGER NOT NULL DEFAULT 0,author TEXT NOT NULL DEFAULT '',source_url TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL"+(version==2?",favorite INTEGER NOT NULL DEFAULT 0":"")+")");
                db.execSQL("CREATE TABLE imported_task (task_id TEXT PRIMARY KEY,uri TEXT NOT NULL,video_id INTEGER REFERENCES video(id) ON DELETE SET NULL)");
                if(version==2)db.execSQL("CREATE INDEX video_favorite_created ON video(favorite,created_at DESC,id DESC)");
                db.execSQL("INSERT INTO video (id,uri,title,mime,source,playback_ms,created_at) VALUES (41,?,'保留标题','video/mp4','x',4200,100)",new Object[]{task.optString("uri")});
                if(version==2)db.execSQL("UPDATE video SET favorite=1 WHERE id=41");
                db.execSQL("INSERT INTO imported_task (task_id,uri,video_id) VALUES ('legacy',?,41)",new Object[]{task.optString("uri")});
                db.setVersion(version);
            }
            // Do not open the library or read subtitles before deleting the only legacy attachment snapshot.
            if(version==1)TaskStore.remove(app,"legacy");
            else assertTrue(TaskStore.removeIfInactive(app,"legacy"));
            assertNull(TaskStore.get(app,"legacy"));
            store=new LibraryStore(app);assertEquals(1,store.subtitles(41).size());
            assertEquals(caption.optString("uri"),store.subtitles(41).get(0).optString("uri"));
            assertEquals("保留标题",store.video(41).optString("title"));assertEquals(4200,store.video(41).optLong("playbackMs"));
            assertEquals(version==2,store.video(41).optBoolean("favorite"));
            store.close();store=new LibraryStore(app);assertEquals(1,store.subtitles(41).size());
        }
    }

    private File file(String extension) throws Exception {
        File file=File.createTempFile("subtitle-library-",extension,app.getCacheDir());
        Files.write(file.toPath(),"fixture".getBytes(StandardCharsets.UTF_8));fixtures.add(file);return file;
    }
    private JSONObject attachment(File file,String name,String mime) throws Exception {
        return new JSONObject().put("uri",Uri.fromFile(file).toString()).put("name",name).put("mime",mime);
    }
    private JSONObject completed(String id,File video) throws Exception {
        return new JSONObject().put("id",id).put("status","completed").put("uri",Uri.fromFile(video).toString()).put("title","相同标题")
                .put("mime","video/mp4").put("url","https://x.com/fixture/status/123").put("size",100).put("completedAt",100)
                .put("media",new JSONObject().put("duration",60).put("subtitleLanguage","zh-Hans"));
    }
    private void queue(JSONObject... tasks) {
        JSONArray items=new JSONArray();for(JSONObject task:tasks)items.put(task);
        assertTrue(app.getSharedPreferences("tasks",0).edit().putString("items",items.toString()).commit());
    }
}
