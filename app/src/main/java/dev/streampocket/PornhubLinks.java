package dev.streampocket;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;

/** Single-video routing only; does not request pages or change access/verification cookies. */
final class PornhubLinks {
    private PornhubLinks() {}

    static String videoId(String url) {
        if (VideoPlatform.detect(url) != VideoPlatform.PORNHUB) return "";
        try {
            URI uri = new URI(url);
            String path = uri.getPath(), id = "";
            boolean seenKey = false;
            for (String part : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
                String[] pair = part.split("=", 2);
                if (URLDecoder.decode(pair[0], "UTF-8").equalsIgnoreCase("viewkey")) {
                    if (seenKey || pair.length != 2 || !pair[0].equals("viewkey")) return "";
                    seenKey = true;
                    id = URLDecoder.decode(pair[1], "UTF-8");
                }
            }
            if (path != null && path.matches("/embed/[a-z0-9]+/?")) {
                if (seenKey) return "";
                id = path.replaceFirst("^/embed/", "").replaceFirst("/$", "");
            } else if (!"/view_video.php".equals(path) && !"/video/show".equals(path)) return "";
            return id.matches("[a-z0-9]{1,64}") ? id : "";
        } catch (Exception invalid) { return ""; }
    }

    static String normalizeVideo(String url) {
        if (VideoPlatform.detect(url) != VideoPlatform.PORNHUB) return url;
        String id = videoId(url);
        if (id.isEmpty()) throw new IllegalArgumentException("请粘贴 Pornhub 单个视频的链接，暂不处理首页、搜索、作者页或播放列表。");
        URI uri = URI.create(url);
        StringBuilder query = new StringBuilder();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            if (part.startsWith("viewkey=")) continue;
            if (!part.isEmpty()) query.append('&').append(part);
        }
        // The bundled extractor requires viewkey to be the first query field.
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/view_video.php?viewkey=" + id
                + query;
    }

    static String identity(String url) {
        String id = videoId(url); if (id.isEmpty()) return "";
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        for (String root : new String[]{"pornhub.com", "pornhub.net", "pornhub.org"})
            if (host.equals(root) || host.endsWith("." + root)) return "pornhub:" + root + ":" + id;
        return "";
    }
}
