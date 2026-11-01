package dev.streampocket;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One public video per request, regardless of YouTube share URL or playlist context. */
final class YouTubeLinks {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VIDEO_PATH = Pattern.compile("/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})/?");
    private YouTubeLinks() {}

    static boolean matches(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com")
                    || host.equals("music.youtube.com") || host.equals("youtu.be") || host.equals("www.youtu.be")
                    || host.equals("youtube-nocookie.com") || host.equals("www.youtube-nocookie.com");
        } catch (Exception invalid) { return false; }
    }

    static String normalizeVideo(String value) {
        if (!matches(value)) return value;
        String id = videoId(value);
        if (id.isEmpty()) throw new IllegalArgumentException("请复制具体的 YouTube 视频或 Shorts 链接；目前不整批解析频道或播放列表。");
        return "https://www.youtube.com/watch?v=" + id;
    }

    static String videoId(String value) {
        if (!matches(value)) return "";
        try {
            URI uri = new URI(value);
            String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getPath();
            if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
                String id = path.replaceFirst("^/", "").replaceFirst("/$", "");
                return ID.matcher(id).matches() ? id : "";
            }
            if ("/watch".equals(path) || "/watch/".equals(path)) {
                String found = "", query = uri.getRawQuery();
                if (query != null) for (String part : query.split("&")) {
                    String[] pair = part.split("=", 2);
                    if (!"v".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name()))) continue;
                    String id = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()) : "";
                    if (!ID.matcher(id).matches() || (!found.isEmpty() && !found.equals(id))) return "";
                    found = id;
                }
                return found;
            }
            Matcher video = VIDEO_PATH.matcher(path == null ? "" : path);
            return video.matches() ? video.group(1) : "";
        } catch (Exception invalid) { return ""; }
    }

    static String failureMessage(Throwable failure) {
        String text = CoreRules.cleanLog(failure == null ? "" : failure.toString()).toLowerCase(Locale.ROOT);
        SourceFailure.Kind kind = SourceFailure.kind(failure);
        // Parser warnings must never mask a definite transfer/access error below them.
        if (kind != SourceFailure.Kind.OTHER && kind != SourceFailure.Kind.NO_FORMATS
                && kind != SourceFailure.Kind.AUTH && kind != SourceFailure.Kind.CHALLENGE) return "";
        if (text.contains("具体的 youtube")) return "请复制具体的 YouTube 视频或 Shorts 链接；目前不整批解析频道或播放列表。";
        if (kind == SourceFailure.Kind.AUTH || kind == SourceFailure.Kind.CHALLENGE
                || text.contains("confirm your age") || text.contains("age-restricted"))
            return "YouTube 要求登录、年龄确认或访问验证，当前无法取得这个视频。本版支持无需登录即可访问的视频；请先在 YouTube 确认内容的可访问状态。记录已保留。";
        if (text.contains("javascript runtime") || text.contains("signature solving failed") || text.contains("challenge solver"))
            return "YouTube 解析组件未能完成视频解析。请先更新解析器；若仍失败，请更新应用后重试。链接和错误详情已保留。";
        if (kind == SourceFailure.Kind.NO_FORMATS)
            return "YouTube 没有向当前访问状态提供可下载的画质。请先在 YouTube 确认视频可播放，再更新解析器后重试；失败链接已保留。";
        if (text.contains("not available in your country") || text.contains("geo-restricted"))
            return "这个 YouTube 视频在当前地区不可用。请在 YouTube 确认可访问状态，已有记录保留。";
        return "";
    }
}
