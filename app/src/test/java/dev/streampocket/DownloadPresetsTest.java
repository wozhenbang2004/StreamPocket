package dev.streampocket;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DownloadPresetsTest {
    static Engine.Media video() {
        Engine.Media media = new Engine.Media();
        media.id = "clip"; media.url = "https://www.youtube.com/watch?v=abcdefghijk";
        media.title = "测试视频"; media.hasAudio = true; media.thumbnail = "https://example.com/cover.jpg";
        media.qualities.add(new Engine.Quality("best", "最高可用", "bv*+ba/b"));
        media.qualities.add(new Engine.Quality("720", "720p", "bv*[height=720]+ba/b[height=720]"));
        return media;
    }

    static DownloadPresets.Preset preset(String id, String name, String rule) {
        return new DownloadPresets.Preset(id, name, rule, true, false, SubtitleChoices.PREFER_ORIGINAL,
                Collections.emptyList(), NamingTemplate.DEFAULT);
    }

    @Test public void copyAppliesSnapshotWithoutChangingInspectionResult() throws Exception {
        Engine.Media original = video();
        String before = original.toJson().toString();
        DownloadPresets.Result result = DownloadPresets.copyMediaAndApply(original, preset("phone", "手机观看", "cap-720"));
        assertTrue(result.applied()); assertEquals("cap-720", result.quality.id); assertNotSame(original, result.media);
        assertEquals(before, original.toJson().toString()); assertTrue(result.media.saveCover);
        assertEquals("phone", result.media.presetId); assertEquals("手机观看", result.media.presetName);
        assertEquals(NamingTemplate.DEFAULT, result.media.namingTemplate);
        result.media.title = "改名"; result.quality.selector = "changed";
        assertEquals("测试视频", original.title); assertEquals(2, original.qualities.size());
    }

    @Test public void capabilityMismatchDoesNotSilentlyChangeTheOutputKind() {
        Engine.Media media = video(); media.hasAudio = false;
        assertFalse(DownloadPresets.apply(media, "audio-mp3").applied());
        assertFalse(DownloadPresets.apply(media, "compatible-mp4").applied());
        media.album = true; assertFalse(DownloadPresets.apply(media, "best").applied());
        media.album = false; media.qualities.remove(1);
        assertFalse(DownloadPresets.apply(media, "cap-720").applied());
        assertFalse(DownloadPresets.apply(media, "--exec malicious").applied());
    }

    @Test public void audioAndCompatibilityResolveOnlyToAvailableChoices() {
        Engine.Media media = video();
        assertEquals("mp3", DownloadPresets.apply(media, "audio-mp3").quality.audioFormat);
        Engine.Quality compatible = new Engine.Quality("compatible-mp4", "兼容优先", "h264+aac");
        compatible.compatibleMp4 = true; media.qualities.add(compatible);
        assertTrue(DownloadPresets.apply(media, "compatible-mp4").quality.compatibleMp4);
        assertNotSame(compatible, DownloadPresets.apply(media, "compatible-mp4").quality);
    }

    @Test public void malformedImportCannotIntroduceArbitraryCommandsOrWildcardSubtitles() throws Exception {
        JSONObject json = preset("phone", "手机", "best").toJson();
        json.put("rule", "bv* --exec something");
        try { DownloadPresets.Preset.fromJson(json); fail(); } catch (IllegalArgumentException expected) {}
        json = preset("phone", "手机", "best").toJson().put("saveCover", "false");
        try { DownloadPresets.Preset.fromJson(json); fail(); } catch (IllegalArgumentException expected) {}
        try { new DownloadPresets.Preset("phone", "手机", "best", false, true, "original", Arrays.asList("all", "*"), "{title}"); fail(); }
        catch (IllegalArgumentException expected) {}
    }
}
