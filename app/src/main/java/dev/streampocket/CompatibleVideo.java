package dev.streampocket;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Offer a copy/remux preset only when metadata contains compatible source streams. */
final class CompatibleVideo {
    private static final String AVC = "[vcodec~='^(avc1|h264)']";
    private static final String AAC = "[acodec~='^(mp4a|aac)']";

    static String selector(JSONObject metadata, boolean hasAudio) {
        boolean silentVideo = false, separateAudio = false, combined = false;
        JSONArray formats = metadata.optJSONArray("formats");
        if (formats == null || formats.length() == 0) formats = new JSONArray().put(metadata);
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || format.optBoolean("has_drm")) continue;
            String video = format.optString("vcodec").toLowerCase(Locale.ROOT);
            String audio = format.optString("acodec").toLowerCase(Locale.ROOT);
            boolean avc = video.startsWith("avc1") || video.startsWith("h264");
            boolean aac = audio.startsWith("mp4a") || audio.startsWith("aac");
            if (avc && "none".equals(audio)) silentVideo = true;
            if ("none".equals(video) && aac) separateAudio = true;
            if (avc && aac) combined = true;
        }
        if (hasAudio) {
            if (!combined && !(silentVideo && separateAudio)) return "";
            return "bv" + AVC + "+ba" + AAC + "/b" + AVC + AAC;
        }
        return silentVideo ? "bv" + AVC + "[acodec=none]" : "";
    }

    private CompatibleVideo() {}
}
