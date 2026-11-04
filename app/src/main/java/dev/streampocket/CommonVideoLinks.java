package dev.streampocket;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Common source URL rules. Extraction remains in yt-dlp; identity never replaces the request URL. */
public final class CommonVideoLinks {
    private static final Pattern REDDIT_POST = Pattern.compile("/(?:r/[^/]+/)?comments/([A-Za-z0-9]+)(?:/.*)?");
    private static final Pattern VIMEO_VIDEO = Pattern.compile("/(?:video/)?([0-9]+)(?:/([A-Za-z0-9]+))?/?");
    private static final Pattern TWITCH_VOD = Pattern.compile("/(?:videos|[^/]+/v(?:ideo)?)/([0-9]+)/?");
    private static final Pattern TWITCH_CLIP = Pattern.compile("/[^/]+/clip/([A-Za-z0-9_-]+)/?");
    private static final String TWITCH_SINGLE = "请复制具体的 Twitch 剪辑或已结束回放链接；暂不下载频道、合集或正在直播的内容。";
    private CommonVideoLinks() {}

    /** Keep authorization/content parameters byte-for-byte, including Vimeo h and Weibo fid/layerid. */
    public static String normalizeVideo(String value) {
        String normalized = CoreRules.normalizeUrl(value);
        VideoPlatform platform = VideoPlatform.detect(normalized);
        if (!isCommon(platform)) return normalized;
        try {
            URI uri = new URI(normalized);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (platform == VideoPlatform.REDDIT && (host.equals("reddit.com") || host.equals("old.reddit.com")
                    || host.equals("np.reddit.com") || host.equals("m.reddit.com"))) host = "www.reddit.com";
            else if (platform == VideoPlatform.WEIBO && host.equals("www.weibo.com")) host = "weibo.com";
            else return normalized;
            return uri.getScheme() + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                    + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception invalid) { return normalized; }
    }

    /** Empty means the existing platform/generic identity rules still own this URL. */
    public static String identity(String value) {
        VideoPlatform platform = VideoPlatform.detect(value);
        if (!isCommon(platform)) return "";
        try {
            URI uri = new URI(normalizeVideo(value));
            String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getRawPath();
            String query = identityQuery(uri.getRawQuery());
            String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
            if (platform == VideoPlatform.REDDIT && host.equals("www.reddit.com")) {
                Matcher post = REDDIT_POST.matcher(path);
                if (post.matches()) return "reddit:post:" + post.group(1).toLowerCase(Locale.ROOT) + port + query;
            }
            if (platform == VideoPlatform.VIMEO && (host.equals("vimeo.com") || host.equals("www.vimeo.com") || host.equals("player.vimeo.com"))) {
                Matcher video = VIMEO_VIDEO.matcher(path);
                if (video.matches()) return "vimeo:video:" + video.group(1)
                        + (video.group(2) == null ? "" : "/" + video.group(2)) + port + query;
            }
            if (platform == VideoPlatform.TWITCH) {
                Matcher vod = TWITCH_VOD.matcher(path), clip = TWITCH_CLIP.matcher(path);
                if (isTwitchMainHost(host) && vod.matches()) return "twitch:vod:" + vod.group(1) + port + query;
                if (isTwitchMainHost(host) && clip.matches()) return "twitch:clip:" + clip.group(1) + port + query;
                if (host.equals("clips.twitch.tv") && path.matches("/[A-Za-z0-9_-]+/?") && !path.matches("/embed/?"))
                    return "twitch:clip:" + path.replaceFirst("^/", "").replaceFirst("/$", "") + port + query;
            }
            return platform.id + ":url:" + uri.getScheme() + "://" + host + port + path + query;
        } catch (Exception invalid) { return value; }
    }

    /** URL preflight is supplemented by the engine's is_live/is_upcoming metadata check. */
    public static void validateSingleVideoUrl(String value) {
        if (VideoPlatform.detect(value) != VideoPlatform.TWITCH) return;
        try {
            URI uri = new URI(value);
            String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getPath();
            // Twitch embeds select a channel before a video when both are present.
            if (hasQuery(uri, "channel")) throw new IllegalArgumentException(TWITCH_SINGLE);
            if (host.equals("clips.twitch.tv")) {
                if (path.matches("/[A-Za-z0-9_-]+/?") && !path.matches("/embed/?")) return;
                if (path.matches("/embed/?") && queryMatches(uri, "clip", "[A-Za-z0-9_-]+")) return;
            }
            if (host.equals("player.twitch.tv") && (path.isEmpty() || path.equals("/") || path.equals("/embed"))) {
                if (queryMatches(uri, "video", "v?[0-9]+") || queryMatches(uri, "vodID", "[0-9]+")) return;
            }
            if (isTwitchMainHost(host)) {
                if (TWITCH_VOD.matcher(path).matches() || TWITCH_CLIP.matcher(path).matches()) return;
                if (path.matches("/[^/]+/schedule/?") && queryMatches(uri, "vodID", "[0-9]+")) return;
            }
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { /* Give a useful single-video instruction below. */ }
        throw new IllegalArgumentException(TWITCH_SINGLE);
    }

    private static boolean isCommon(VideoPlatform platform) {
        return platform == VideoPlatform.WEIBO || platform == VideoPlatform.XIGUA || platform == VideoPlatform.ACFUN
                || platform == VideoPlatform.REDDIT || platform == VideoPlatform.VIMEO || platform == VideoPlatform.TWITCH;
    }

    private static String identityQuery(String query) {
        if (query == null || query.isEmpty()) return "";
        StringBuilder kept = new StringBuilder();
        int fields = 0;
        for (String part : query.split("&", -1)) {
            String key = part.split("=", 2)[0];
            // Only explicit marketing fields are ignored for comparison. Repeated/content/signed fields stay ordered.
            if (!key.toLowerCase(Locale.ROOT).startsWith("utm_")) {
                if (fields++ > 0) kept.append('&');
                kept.append(part);
            }
        }
        return fields == 0 ? "" : "?" + kept;
    }

    private static boolean hasQuery(URI uri, String name) {
        return queryValue(uri, name) != null;
    }

    private static boolean queryMatches(URI uri, String name, String pattern) {
        String value = queryValue(uri, name);
        return value != null && value.matches(pattern);
    }

    private static boolean isTwitchMainHost(String host) {
        return host.equals("twitch.tv") || host.equals("www.twitch.tv") || host.equals("m.twitch.tv") || host.equals("go.twitch.tv");
    }

    private static String queryValue(URI uri, String name) {
        String found = null;
        if (uri.getRawQuery() == null) return null;
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            try {
                if (!name.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name()))) continue;
                String next = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()) : "";
                if (found != null && !found.equals(next)) return "";
                found = next;
            } catch (Exception malformed) { return ""; }
        }
        return found;
    }
}
