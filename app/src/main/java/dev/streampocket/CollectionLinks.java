package dev.streampocket;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Collections are an explicit operation; ordinary video normalization remains single-item. */
final class CollectionLinks {
    static String normalize(String text) {
        String url = CoreRules.normalizeUrl(text);
        try {
            URI uri = new URI(url);
            if (YouTubeLinks.matches(url)) {
                String list = "";
                for (String part : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
                    String[] pair = part.split("=", 2);
                    if (!URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name()).equals("list")) continue;
                    if (!list.isEmpty()) throw new IllegalArgumentException();
                    list = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()) : "";
                    if (!list.matches("[A-Za-z0-9_-]{10,100}")) throw new IllegalArgumentException();
                }
                if (!list.isEmpty()) return "https://www.youtube.com/playlist?list=" + list;
            }
            if (VideoPlatform.detect(url) == VideoPlatform.BILIBILI && uri.getPath().matches("/video/(BV[A-Za-z0-9]+|av[0-9]+)/?"))
                return "https://www.bilibili.com" + uri.getRawPath();
        } catch (Exception invalid) { /* Explain the supported bounded scope below. */ }
        throw new IllegalArgumentException("请粘贴 YouTube 播放列表或 B站分P视频链接；每次最多展开 100 项。");
    }
}
