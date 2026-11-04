package dev.streampocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class DownloadPreferencesTest {
    static JSONArray subtitle() throws Exception { return new JSONArray().put(new JSONObject().put("url","https://example.invalid/captions.vtt").put("ext","vtt")); }
    static JSONObject metadata() throws Exception {
        return new JSONObject().put("id","fixture").put("title","离线字幕选择样例")
                .put("subtitles",new JSONObject().put("en",subtitle()).put("zh-Hans",subtitle()).put("live_chat",subtitle()).put("fr",new JSONArray()))
                .put("automatic_captions",new JSONObject().put("en",subtitle()).put("ja",subtitle()).put(".*",subtitle()));
    }
    static JSONObject format(String id,String video,String audio) throws Exception {
        return new JSONObject().put("format_id",id).put("vcodec",video).put("acodec",audio).put("ext","mp4").put("height",1080);
    }

    @Test public void actualLanguagesKeepSourceAndExcludeEmptyChatAndPatterns() throws Exception {
        List<SubtitleChoices.Language> languages=SubtitleChoices.fromMetadata(metadata());
        assertEquals(3,languages.size());assertEquals("zh-Hans",languages.get(0).code);
        assertTrue(languages.get(1).original);assertTrue(languages.get(1).automatic);
        assertFalse(languages.get(2).original);assertTrue(languages.get(2).automatic);
        assertTrue(languages.get(2).label().contains("自动"));
    }

    @Test public void selectedLanguagesFollowSourcePolicyWithoutWildcardExpansion() throws Exception {
        List<SubtitleChoices.Language> languages=SubtitleChoices.fromMetadata(metadata());
        List<String> selected=Arrays.asList("ja","zh-Hans","en","en",".*","unknown");
        assertEquals(Arrays.asList("zh-Hans","en"),SubtitleChoices.selected(languages,selected,"",SubtitleChoices.ORIGINAL));
        assertEquals(Arrays.asList("ja","en"),SubtitleChoices.selected(languages,selected,"",SubtitleChoices.AUTOMATIC));
        assertEquals(Arrays.asList("ja","zh-Hans","en"),SubtitleChoices.selected(languages,selected,"",SubtitleChoices.PREFER_ORIGINAL));
    }

    @Test public void legacySingleLanguageRemainsUsableButInvalidOptionsAreNotCommands() {
        assertEquals(Collections.singletonList("zh-Hans"),SubtitleChoices.selected(Collections.emptyList(),Collections.emptyList(),"zh-Hans","old-value"));
        assertTrue(SubtitleChoices.selected(Collections.emptyList(),Collections.singletonList("all"),"zh-Hans","old-value").isEmpty());
        assertEquals(SubtitleChoices.PREFER_ORIGINAL,SubtitleChoices.mode("--all-subs"));
    }

    @Test public void compatiblePresetRequiresActualH264AndAacStreams() throws Exception {
        JSONObject data=new JSONObject().put("formats",new JSONArray().put(format("v","avc1.640028","none")).put(format("a","none","aac")));
        assertFalse(CompatibleVideo.selector(data,true).isEmpty());
        data.getJSONArray("formats").put(1,format("a","none","opus"));
        assertEquals("",CompatibleVideo.selector(data,true));
        assertFalse(CompatibleVideo.selector(new JSONObject().put("formats",new JSONArray().put(format("muxed","h264","mp4a.40.2"))),true).isEmpty());
    }

    @Test public void av1HevcUnknownAndDrmNeverPretendToBeCompatible() throws Exception {
        for(String codec:Arrays.asList("av01.0.08M.08","vp9","hev1","")) {
            JSONObject data=new JSONObject().put("formats",new JSONArray().put(format("v",codec,"aac")));
            assertEquals(codec,"",CompatibleVideo.selector(data,true));
        }
        assertEquals("",CompatibleVideo.selector(new JSONObject().put("formats",new JSONArray().put(format("v","h264","aac").put("has_drm",true))),true));
    }

    @Test public void silentH264IsOfferedWithoutInventingAnAudioTrack() throws Exception {
        JSONObject data=new JSONObject().put("formats",new JSONArray().put(format("v","h264","none")));
        assertTrue(CompatibleVideo.selector(data,false).contains("[acodec=none]"));
        assertEquals("",CompatibleVideo.selector(data,true));
    }
}
