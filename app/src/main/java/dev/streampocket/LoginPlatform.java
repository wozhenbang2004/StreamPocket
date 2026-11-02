package dev.streampocket;

import java.net.URI;
import java.util.Locale;

/** Only official platform origins may participate in the local login flow. */
public enum LoginPlatform {
    BILIBILI("bilibili", "哔哩哔哩", "https://passport.bilibili.com/login", new String[]{"bilibili.com"},
            new String[]{"https://bilibili.com/", "https://www.bilibili.com/", "https://api.bilibili.com/", "https://passport.bilibili.com/", "https://m.bilibili.com/", "https://member.bilibili.com/"}),
    DOUYIN("douyin", "抖音", "https://www.douyin.com/user/self?from_nav=1", new String[]{"douyin.com", "iesdouyin.com"},
            new String[]{"https://douyin.com/", "https://www.douyin.com/", "https://sso.douyin.com/", "https://www.iesdouyin.com/"}),
    X("x", "X / Twitter", "https://x.com/i/flow/login", new String[]{"x.com", "twitter.com"},
            new String[]{"https://x.com/", "https://www.x.com/", "https://api.x.com/", "https://twitter.com/", "https://www.twitter.com/", "https://api.twitter.com/"}),
    YOUTUBE("youtube", "YouTube", "https://www.youtube.com/", new String[]{"youtube.com"},
            new String[]{"https://youtube.com/", "https://www.youtube.com/", "https://m.youtube.com/", "https://music.youtube.com/"}),
    XIAOHONGSHU("xiaohongshu", "小红书", "https://www.xiaohongshu.com/", new String[]{"xiaohongshu.com"},
            new String[]{"https://xiaohongshu.com/", "https://www.xiaohongshu.com/", "https://edith.xiaohongshu.com/"}),
    KUAISHOU("kuaishou", "快手", "https://www.kuaishou.com/", new String[]{"kuaishou.com", "kuaishou.cn", "gifshow.com"},
            new String[]{"https://kuaishou.com/", "https://www.kuaishou.com/", "https://v.kuaishou.com/", "https://www.gifshow.com/"}),
    TIKTOK("tiktok", "TikTok", "https://www.tiktok.com/login", new String[]{"tiktok.com"},
            new String[]{"https://tiktok.com/", "https://www.tiktok.com/", "https://m.tiktok.com/"}),
    INSTAGRAM("instagram", "Instagram", "https://www.instagram.com/accounts/login/", new String[]{"instagram.com"},
            new String[]{"https://instagram.com/", "https://www.instagram.com/", "https://i.instagram.com/"}),
    FACEBOOK("facebook", "Facebook", "https://www.facebook.com/login/", new String[]{"facebook.com"},
            new String[]{"https://facebook.com/", "https://www.facebook.com/", "https://m.facebook.com/"}),
    PINTEREST("pinterest", "Pinterest", "https://www.pinterest.com/login/", new String[]{"pinterest.com"},
            new String[]{"https://pinterest.com/", "https://www.pinterest.com/"}),
    PORNHUB("pornhub", "Pornhub", "https://www.pornhub.com/login", new String[]{"pornhub.com", "pornhub.net", "pornhub.org"},
            new String[]{"https://pornhub.com/", "https://www.pornhub.com/", "https://pornhub.net/", "https://www.pornhub.net/", "https://pornhub.org/", "https://www.pornhub.org/"}),
    HEYBOX("heybox", "小黑盒", "https://login.xiaoheihe.cn/?origin=&redirect_url=https%3A%2F%2Fwww.xiaoheihe.cn%2Fapp%2Fbbs%2Fhome",
            new String[]{"xiaoheihe.cn"}, new String[]{"https://xiaoheihe.cn/", "https://www.xiaoheihe.cn/", "https://api.xiaoheihe.cn/", "https://login.xiaoheihe.cn/"});

    public final String id;
    public final String title;
    public final String loginUrl;
    private final String[] roots;
    private final String[] urls;

    LoginPlatform(String id, String title, String loginUrl, String[] roots, String[] urls) {
        this.id = id; this.title = title; this.loginUrl = loginUrl; this.roots = roots; this.urls = urls;
    }

    public static LoginPlatform fromId(String id) {
        for (LoginPlatform platform : values()) if (platform.id.equals(id)) return platform;
        return null;
    }

    public String[] cookieUrls() { return urls.clone(); }

    /** The mobile homepage may be an app/marketing landing page, not web login.
     * This account entry is the official site's observed "我的" navigation. */
    public boolean prefersDesktopLogin() { return this == DOUYIN; }

    public boolean ownsDomain(String domain) {
        if (domain == null) return false;
        String host = domain.toLowerCase(Locale.ROOT);
        if (host.startsWith("#httponly_")) host = host.substring(10);
        if (host.startsWith(".")) host = host.substring(1);
        if (!host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")) return false;
        for (String root : roots) if (host.equals(root) || host.endsWith("." + root)) return true;
        return false;
    }

    public boolean allowsNavigation(String url) {
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && (ownsDomain(uri.getHost()) || (this == YOUTUBE && "accounts.google.com".equalsIgnoreCase(uri.getHost())));
        } catch (Exception invalid) { return false; }
    }

    /** Presence means a login credential was captured, not that the server accepted it. */
    public boolean hasLogin(String netscape) {
        return hasLogin(netscape, false);
    }

    /** Pornhub uses the official page's account marker; guest cookies alone prove nothing. */
    public boolean hasLogin(String netscape, boolean accountPageConfirmed) {
        if (netscape == null) return false;
        long now = System.currentTimeMillis() / 1000;
        for (String line : netscape.split("\\r?\\n")) {
            if (line.startsWith("#") && !line.startsWith("#HttpOnly_")) continue;
            String[] row = line.split("\\t", -1);
            if (row.length != 7 || !ownsDomain(row[0]) || row[6].trim().isEmpty()) continue;
            try { long expires = Long.parseLong(row[4]); if (expires != 0 && expires <= now) continue; }
            catch (NumberFormatException invalid) { continue; }
            if (this == PORNHUB && accountPageConfirmed) return true;
            String name = row[5];
            if (this == YOUTUBE && (name.equals("SAPISID") || name.equals("__Secure-1PAPISID") || name.equals("__Secure-3PAPISID"))) return true;
            if (this == BILIBILI && name.equals("SESSDATA")) return true;
            if (this == X && name.equals("auth_token")) return true;
            if (this == DOUYIN && (name.equals("sessionid") || name.equals("sessionid_ss") || name.equals("sid_guard"))) return true;
            if (this == XIAOHONGSHU && name.equals("web_session")) return true;
            if (this == KUAISHOU && (name.equals("kuaishou.server.web_st") || name.equals("kuaishou.server.web_ph"))) return true;
            if ((this == TIKTOK || this == INSTAGRAM) && name.equals("sessionid")) return true;
            if (this == FACEBOOK && name.equals("c_user")) return true;
            if (this == PINTEREST && name.equals("_pinterest_sess")) return true;
            if (this == HEYBOX && (name.equals("pkey") || name.equals("user_pkey"))
                    && !row[6].trim().matches("(?i)(?:none|null|undefined)")) return true;
        }
        return false;
    }
}
