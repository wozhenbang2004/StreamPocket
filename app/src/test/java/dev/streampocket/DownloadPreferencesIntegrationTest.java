package dev.streampocket;

import com.yausername.youtubedl_android.YoutubeDLRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class DownloadPreferencesIntegrationTest {
    @Test public void multipleLanguagesAndSourceSurviveQueueAndInboxSerialization() throws Exception {
        Engine.Media media=Engine.mediaFromMetadata(DownloadPreferencesTest.metadata(),"https://vimeo.com/76979871").get(0);
        media.subtitleLanguages=Arrays.asList("en","ja");media.subtitleMode=SubtitleChoices.AUTOMATIC;media.saveSubtitles=true;
        Engine.Media restored=Engine.Media.fromJson(media.toJson());
        assertEquals(Arrays.asList("en","ja"),restored.selectedSubtitles());
        assertEquals(SubtitleChoices.AUTOMATIC,restored.subtitleMode);
        YoutubeDLRequest request=new YoutubeDLRequest("https://example.invalid/video");restored.configureSubtitles(request);
        List<String> args=request.buildCommand();
        assertTrue(args.contains("--write-auto-subs"));assertFalse(args.contains("--write-subs"));
        assertEquals("en,ja",args.get(args.indexOf("--sub-langs")+1));
    }

    @Test public void manualOnlyAndDisabledModesNeverRequestAutomaticCaptions() throws Exception {
        Engine.Media media=Engine.mediaFromMetadata(DownloadPreferencesTest.metadata(),"https://vimeo.com/76979871").get(0);
        media.subtitleMode=SubtitleChoices.ORIGINAL;media.saveSubtitles=true;
        YoutubeDLRequest request=new YoutubeDLRequest("https://example.invalid/video");media.configureSubtitles(request);
        assertTrue(request.buildCommand().contains("--write-subs"));assertFalse(request.buildCommand().contains("--write-auto-subs"));
        media.saveSubtitles=false;request=new YoutubeDLRequest("https://example.invalid/video");media.configureSubtitles(request);
        assertFalse(request.buildCommand().contains("--sub-langs"));
    }

    @Test public void legacyTasksKeepTheirSingleLanguageAndBothSourceKinds() throws Exception {
        Engine.Media old=Engine.Media.fromJson(new JSONObject().put("subtitleAvailable",true).put("saveSubtitles",true).put("subtitleLanguage","en"));
        YoutubeDLRequest request=new YoutubeDLRequest("https://example.invalid/video");old.configureSubtitles(request);
        assertTrue(request.buildCommand().contains("--write-subs"));assertTrue(request.buildCommand().contains("--write-auto-subs"));
        assertEquals("en",request.buildCommand().get(request.buildCommand().indexOf("--sub-langs")+1));
    }

    @Test public void compatibilityRemuxesOnlyAndKeepsExistingBestAndHeightOrder() throws Exception {
        JSONObject metadata=DownloadPreferencesTest.metadata().put("formats",new JSONArray()
                .put(DownloadPreferencesTest.format("v","avc1.640028","none")).put(DownloadPreferencesTest.format("a","none","mp4a.40.2")));
        Engine.Media media=Engine.mediaFromMetadata(metadata,"https://vimeo.com/76979871").get(0);
        assertEquals("best",media.qualities.get(0).id);assertEquals("1080",media.qualities.get(1).id);
        Engine.Quality compatible=null;for(Engine.Quality quality:media.qualities)if(quality.compatibleMp4)compatible=quality;
        assertNotNull(compatible);compatible=Engine.Quality.fromJson(compatible.toJson());
        YoutubeDLRequest request=new YoutubeDLRequest("https://example.invalid/video");compatible.configure(request);
        List<String> args=request.buildCommand();assertTrue(args.contains("--remux-video"));assertFalse(args.contains("--recode-video"));
        assertEquals("mp4",args.get(args.indexOf("--merge-output-format")+1));
        assertFalse(Engine.Quality.fromJson(new JSONObject()).compatibleMp4);
    }
}
