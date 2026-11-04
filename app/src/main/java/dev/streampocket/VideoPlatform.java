package dev.streampocket;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared channel identities for share text, request scheduling and local source filters. */
public enum VideoPlatform {
    X("x", "X / Twitter", "x.com", "twitter.com"),
    BILIBILI("bilibili", "哔哩哔哩", "bilibili.com", "b23.tv"),
    DOUYIN("douyin", "抖音", "douyin.com", "iesdouyin.com"),
    YOUTUBE("youtube", "YouTube", "youtube.com", "youtube-nocookie.com", "youtu.be"),
    XIAOHONGSHU("xiaohongshu", "小红书", "xiaohongshu.com", "xhslink.com"),
    KUAISHOU("kuaishou", "快手", "kuaishou.com", "kuaishou.cn", "gifshow.com", "chenzhongtech.com"),
    TIKTOK("tiktok", "TikTok", "tiktok.com"),
    INSTAGRAM("instagram", "Instagram", "instagram.com", "instagr.am"),
    FACEBOOK("facebook", "Facebook", "facebook.com", "fb.watch", "fb.me"),
    PINTEREST("pinterest", "Pinterest", "pinterest.com", "pin.it"),
    PORNHUB("pornhub", "Pornhub", "pornhub.com", "pornhub.net", "pornhub.org"),
    HEYBOX("heybox", "小黑盒", "xiaoheihe.cn"),
    WEIBO("weibo", "微博", "weibo.com", "weibo.cn", "t.cn"),
    XIGUA("xigua", "西瓜视频", "ixigua.com"),
    ACFUN("acfun", "AcFun", "acfun.cn"),
    REDDIT("reddit", "Reddit", "reddit.com", "redd.it"),
    VIMEO("vimeo", "Vimeo", "vimeo.com"),
    TWITCH("twitch", "Twitch", "twitch.tv");

    public final String id, title;
    private final String[] roots;
    VideoPlatform(String id, String title, String... roots) { this.id = id; this.title = title; this.roots = roots; }
    public static VideoPlatform fromId(String id) {
        for (VideoPlatform platform : values()) if (platform.id.equals(id)) return platform;
        return null;
    }
    public static VideoPlatform detect(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() != null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return null;
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            for (VideoPlatform platform : values()) for (String root : platform.roots)
                if (host.equals(root) || host.endsWith("." + root)) return platform;
        } catch (Exception ignored) { /* Unknown public sites can still use the generic extractor. */ }
        return null;
    }
    static String shareDomainPattern() {
        StringBuilder domains = new StringBuilder();
        for (VideoPlatform platform : values()) for (String root : platform.roots) {
            if (domains.length() > 0) domains.append('|');
            domains.append(Pattern.quote(root));
        }
        return domains.toString();
    }
}
