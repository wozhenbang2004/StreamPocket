package dev.streampocket;

import com.yausername.youtubedl_android.YoutubeDLRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AudioDownloadTest {
    @Test public void audioChoicesRequireASourceAudioTrackAndSurviveRestoration() throws Exception {
        JSONObject source = new JSONObject().put("id", "fixture").put("formats", new JSONArray()
                .put(new JSONObject().put("format_id", "v").put("vcodec", "avc1").put("acodec", "none").put("height", 1080).put("fps", 60)));
        Engine.Media silent = Engine.mediaFromMetadata(source, "https://www.instagram.com/reel/fixture/").get(0);
        assertEquals(silent.qualities.size(), silent.downloadChoices().size());
        source.getJSONArray("formats").put(new JSONObject().put("format_id", "a").put("vcodec", "none").put("acodec", "aac"));
        Engine.Media media = Engine.Media.fromJson(Engine.mediaFromMetadata(source, "https://www.instagram.com/reel/fixture/").get(0).toJson());
        assertEquals(media.qualities.size() + 2, media.downloadChoices().size());
        assertEquals("源站最高 60 fps", media.qualities.get(1).details);
        for (Engine.Quality option : media.downloadChoices()) if (option.audioOnly()) {
            Engine.Quality restored = Engine.Quality.fromJson(option.toJson());
            assertEquals(option.audioFormat, restored.audioFormat);
            YoutubeDLRequest request = new YoutubeDLRequest("https://example.org/video");
            restored.configure(request);
            String args = request.buildCommand().toString();
            assertTrue(args, args.contains("--extract-audio"));
            assertTrue(args, args.contains("--audio-format"));
            assertTrue(args, args.contains(option.audioFormat));
            assertFalse(args, args.contains("--merge-output-format"));
        }
    }

    @Test public void oldQueueChoicesKeepTheExistingVideoRequest() throws Exception {
        Engine.Quality choice = Engine.Quality.fromJson(new JSONObject().put("selector", "bv*+ba/b"));
        assertFalse(choice.audioOnly());
        YoutubeDLRequest request = new YoutubeDLRequest("https://example.org/video");
        choice.configure(request);
        assertTrue(request.buildCommand().toString().contains("--merge-output-format"));
        assertFalse(request.buildCommand().toString().contains("--extract-audio"));
    }
}
