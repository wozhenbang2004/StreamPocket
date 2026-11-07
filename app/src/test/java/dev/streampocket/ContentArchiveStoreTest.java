package dev.streampocket;

import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, manifest = Config.NONE)
public final class ContentArchiveStoreTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(ContentArchiveStore.DATABASE);
        context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private Engine.Media media() {
        Engine.Media media = DownloadPresetsTest.video(); media.extractorKey = "Youtube"; return media;
    }

    private JSONObject completed(String id, Engine.Media media, Engine.Quality quality) throws Exception {
        return new JSONObject().put("id", id).put("status", "completed").put("media", media.toJson())
                .put("quality", quality.toJson()).put("title", media.title).put("uri", "content://downloads/" + id)
                .put("mime", "video/mp4").put("size", 12345).put("completedAt", 100);
    }

    @Test public void stableSourceIdSurvivesUrlChangesAndPlaylistPositions() throws Exception {
        Engine.Media first = media(), second = Engine.Media.fromJson(first.toJson());
        first.url = "https://www.youtube.com/watch?v=abcdefghijk&token=one";
        second.url = "https://youtu.be/abcdefghijk?token=two"; second.playlistIndex = 12;
        assertEquals(ContentArchiveStore.contentKey(first), ContentArchiveStore.contentKey(second));
        second.extractorKey = "Vimeo";
        assertNotEquals(ContentArchiveStore.contentKey(first), ContentArchiveStore.contentKey(second));
        second = Engine.Media.fromJson(first.toJson()); second.id = "another-child";
        assertNotEquals(ContentArchiveStore.contentKey(first), ContentArchiveStore.contentKey(second));
    }

    @Test public void parentIdsDistinguishBiliPartsButFirstPartAliasesRemainOneContent() throws Exception {
        Engine.Media first = media(); first.extractorKey = "BiliBili"; first.id = "BV1234567";
        first.url = "https://www.bilibili.com/video/BV1234567";
        Engine.Media second = Engine.Media.fromJson(first.toJson()); second.playlistIndex = 1;
        assertEquals(ContentArchiveStore.contentKey(first), ContentArchiveStore.contentKey(second));
        second.playlistIndex = 2;
        assertNotEquals(ContentArchiveStore.contentKey(first), ContentArchiveStore.contentKey(second));
    }

    @Test public void onlyCommittedResultsEnterArchiveAndTaskDeletionCannotEraseThem() throws Exception {
        Engine.Media media = media(); Engine.Quality quality = media.qualities.get(0);
        JSONObject task = completed("one", media, quality).put("status", "failed");
        assertFalse(ContentArchiveStore.recordCompleted(context, task));
        task.put("status", "completed").put("uri", "https://example.com/not-local");
        assertFalse(ContentArchiveStore.recordCompleted(context, task));
        task.put("uri", "content://downloads/one"); assertTrue(ContentArchiveStore.recordCompleted(context, task));
        assertTrue(ContentArchiveStore.recordCompleted(context, task));
        assertEquals(1, ContentArchiveStore.find(context, media, quality).size());
        context.getSharedPreferences("tasks", Context.MODE_PRIVATE).edit().clear().commit();
        assertEquals(1, ContentArchiveStore.find(context, media, quality).size());
        // No file need be readable: this is persistent download history, not an existence test.
        assertEquals("content://downloads/one", ContentArchiveStore.find(context, media, quality).get(0).getString("uri"));
    }

    @Test public void audioVideoImagesAndDirectFilesHaveSeparateVariantsAndAreAllRecorded() throws Exception {
        Engine.Media media = media(); Engine.Quality video = media.qualities.get(0), audio = Engine.Quality.audio("m4a");
        assertTrue(ContentArchiveStore.recordCompleted(context, completed("v", media, video)));
        assertTrue(ContentArchiveStore.recordCompleted(context, completed("a", media, audio).put("mime", "audio/mp4")));
        assertEquals(2, ContentArchiveStore.find(context, media, audio).size());
        assertTrue(ContentArchiveStore.find(context, media, audio).get(0).getBoolean("sameVariant"));
        assertEquals("audio:m4a", ContentArchiveStore.find(context, media, audio).get(0).getString("variantKey"));
        Engine.Media images = Engine.Media.fromJson(media.toJson()); images.album = true;
        assertTrue(ContentArchiveStore.recordCompleted(context, completed("images", images, video).put("mime", "application/zip")));
        assertEquals("images:zip", ContentArchiveStore.variantKey(images, video));
        Engine.Media file = new Engine.Media(); file.kind = "direct-file"; file.extractorKey = "direct-file";
        file.url = "https://example.com/package.zip?signature=one"; file.title = "package.zip";
        file.file = new DirectFileSpec(file.url, file.title, "application/zip", 12345);
        Engine.Quality original = new Engine.Quality("original-file", "原文件", "");
        assertTrue(ContentArchiveStore.recordCompleted(context, completed("file", file, original).put("mime", "application/zip")));
        assertEquals(1, ContentArchiveStore.find(context, file, original).size());
        Engine.Media otherFile = Engine.Media.fromJson(file.toJson()); otherFile.url += "&different=1";
        assertNotEquals(ContentArchiveStore.contentKey(file), ContentArchiveStore.contentKey(otherFile));
    }
}
