package dev.streampocket;

import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public single-post links only. Recognition never requests the website. */
final class HeyboxLinks {
    private static final Pattern POST = Pattern.compile("/(?:app/bbs/link|a)/([A-Za-z0-9]{1,64})/?");
    private HeyboxLinks() {}

    static String postId(String url) {
        if (VideoPlatform.detect(url) != VideoPlatform.HEYBOX) return "";
        try {
            URI uri = new URI(url);Matcher path = POST.matcher(uri.getPath());
            String id = path.matches() ? path.group(1) : "";
            boolean seen = false;
            for (String part : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
                String[] pair = part.split("=", 2);
                if (!URLDecoder.decode(pair[0], "UTF-8").equalsIgnoreCase("link_id")) continue;
                if (seen || pair.length != 2 || !pair[0].equals("link_id")) return "";
                seen = true;String queryId = URLDecoder.decode(pair[1], "UTF-8");
                if (!id.isEmpty() && !id.equals(queryId)) return "";
                id = queryId;
            }
            if (!path.matches() && !uri.getPath().matches("/(?:bbs/post_share|(?:v3/)?bbs/app/(?:api/web/share|link/tree))/?")) return "";
            return id.matches("[A-Za-z0-9]{1,64}") ? id : "";
        } catch (Exception invalid) { return ""; }
    }

    static String normalizeVideo(String url) {
        if (VideoPlatform.detect(url) != VideoPlatform.HEYBOX) return url;
        String id = postId(url);
        if (id.isEmpty()) throw new IllegalArgumentException("请粘贴小黑盒单个视频帖的分享链接，暂不处理首页、游戏页或整批帖子。");
        StringBuilder query = new StringBuilder();String raw = URI.create(url).getRawQuery();
        if (raw != null) for (String part : raw.split("&")) {
            if (part.startsWith("link_id=") || part.isEmpty()) continue;
            query.append(query.length() == 0 ? '?' : '&').append(part);
        }
        return "https://www.xiaoheihe.cn/app/bbs/link/" + id + query;
    }
}
