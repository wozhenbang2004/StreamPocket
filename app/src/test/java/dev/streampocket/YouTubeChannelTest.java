package dev.streampocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Public video URL and adaptive-format fixtures, without contacting YouTube. */
public final class YouTubeChannelTest {
    @Test public void recognizesSchemeLessShortsAndShortShareLinks() {
        LinkBatch.Result links = LinkBatch.parse("视频 youtu.be/BaW_jenozKc?si=share，短片 youtube.com/shorts/jNQXAC9IVRw");
        assertEquals(2, links.urls.size());
        assertEquals("youtube", SourceScheduler.platform(links.urls.get(0)));
        assertEquals("youtube", SourceScheduler.platform(links.urls.get(1)));
    }

    @Test public void aliasesDeduplicateWithoutMixingDifferentVideos() {
        LinkBatch.Result links = LinkBatch.parse("https://youtu.be/BaW_jenozKc?si=share\n"
                + "https://www.youtube.com/watch?v=BaW_jenozKc&list=PLsample&t=12\n"
                + "https://m.youtube.com/shorts/BaW_jenozKc\nhttps://youtube.com/watch?v=jNQXAC9IVRw");
        assertEquals(2, links.urls.size()); assertEquals(2, links.duplicates);
        assertEquals("https://www.youtube.com/watch?v=BaW_jenozKc", Engine.normalizeUrl(links.urls.get(0)));
    }

    @Test public void normalizesOnlySpecificVideosAndNeverExpandsAPlaylist() {
        for (String link : new String[]{"https://youtu.be/BaW_jenozKc?si=abc", "https://music.youtube.com/watch?v=BaW_jenozKc&list=PLsample",
                "https://www.youtube-nocookie.com/embed/BaW_jenozKc", "https://youtube.com/live/BaW_jenozKc"})
            assertEquals("https://www.youtube.com/watch?v=BaW_jenozKc", Engine.normalizeUrl(link));
        for (String link : new String[]{"https://youtube.com/playlist?list=PLsample", "https://youtube.com/@channel",
                "https://youtu.be/short", "https://youtube.com/watch?v=BaW_jenozKc&v=jNQXAC9IVRw"}) {
            try { Engine.normalizeUrl(link); fail(link); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("YouTube")); }
        }
        assertNotEquals("youtube", SourceScheduler.platform("https://youtube.com.example.org/watch?v=BaW_jenozKc"));
    }

    @Test public void retainsAdaptiveQualitiesWithAudioMergeAndYouTubeSource() throws Exception {
        JSONObject raw = new JSONObject().put("id", "BaW_jenozKc").put("title", "离线 YouTube 样例")
                .put("channel", "样例频道").put("duration", 10).put("formats", new JSONArray()
                        .put(new JSONObject().put("format_id", "137").put("vcodec", "avc1").put("acodec", "none").put("height", 1080))
                        .put(new JSONObject().put("format_id", "136").put("vcodec", "avc1").put("acodec", "none").put("height", 720))
                        .put(new JSONObject().put("format_id", "140").put("vcodec", "none").put("acodec", "mp4a")));
        Engine.Media media = Engine.mediaFromMetadata(raw, "https://youtu.be/BaW_jenozKc").get(0);
        assertEquals("YouTube", media.platform); assertEquals("样例频道", media.uploader);
        assertEquals(3, media.qualities.size()); assertEquals("1080p", media.qualities.get(1).label);
        assertTrue(media.qualities.get(1).selector.contains("+ba/"));
        Engine.Media restored = Engine.Media.fromJson(media.toJson());
        assertEquals("YouTube", restored.platform); assertEquals(media.qualities.get(1).selector, restored.qualities.get(1).selector);
    }

    @Test public void failuresKeepTheLinkAndNeverOfferAnUnsupportedLogin() throws Exception {
        String url = "https://youtu.be/BaW_jenozKc";
        InspectionInbox inbox = new InspectionInbox(); inbox.add(java.util.Collections.singletonList(url));
        InspectionInbox.Job job = inbox.next();
        inbox.fail(job, new Exception("ERROR: Sign in to confirm you're not a bot"));
        assertTrue(job.message.contains("YouTube")); assertTrue(job.message.contains("账号管理"));
        InspectionInbox restored = new InspectionInbox(); restored.restore(inbox.json());
        assertEquals(url, restored.jobs.get(0).url); assertEquals(job.id, restored.jobs.get(0).id);
        assertTrue(restored.retry(restored.jobs.get(0))); assertEquals("queued", restored.jobs.get(0).state);
        assertTrue(CoreRules.inspectionMessage(new Exception("WARNING: No supported JavaScript runtime could be found"), url).contains("更新下载支持"));
        assertTrue(CoreRules.userMessage(new Exception("ERROR: HTTP Error 429"), url).contains("冷却"));
        assertTrue(CoreRules.userMessage(new Exception("ERROR: connection timed out"), url).contains("超时"));
        assertEquals("https://example.org/video?id=1", Engine.normalizeUrl("https://example.org/video?id=1"));
    }

    @Test public void earlierJavaScriptWarningsNeverMaskRateLimitsOrConnectionFailures() {
        String warning="WARNING: [youtube] Signature solving failed: Some formats may be missing\nERROR: ";
        String url="https://youtu.be/BaW_jenozKc";
        for(String error:new String[]{"HTTP Error 429: Too Many Requests","connection timed out","HTTP Error 403: Forbidden"}) {
            Exception failure=new Exception(warning+error);
            assertEquals(CoreRules.userMessage(failure),CoreRules.userMessage(failure,url));
        }
        assertTrue(CoreRules.userMessage(new Exception(warning+"Sign in to confirm your age"),url).contains("年龄确认"));
    }
}
