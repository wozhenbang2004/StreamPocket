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
            new String[]{"https://x.com/", "https://www.x.com/", "https://api.x.com/", "https://twitter.com/", "https://www.twitter.com/", "https://api.twitter.com/"});

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
                    && (uri.getPort() == -1 || uri.getPort() == 443) && ownsDomain(uri.getHost());
        } catch (Exception invalid) { return false; }
    }

    /** Presence means a login credential was captured, not that the server accepted it. */
    public boolean hasLogin(String netscape) {
        if (netscape == null) return false;
        long now = System.currentTimeMillis() / 1000;
        for (String line : netscape.split("\\r?\\n")) {
            if (line.startsWith("#") && !line.startsWith("#HttpOnly_")) continue;
            String[] row = line.split("\\t", -1);
            if (row.length != 7 || !ownsDomain(row[0]) || row[6].trim().isEmpty()) continue;
            try { long expires = Long.parseLong(row[4]); if (expires != 0 && expires <= now) continue; }
            catch (NumberFormatException invalid) { continue; }
            String name = row[5];
            if (this == BILIBILI && name.equals("SESSDATA")) return true;
            if (this == X && name.equals("auth_token")) return true;
            if (this == DOUYIN && (name.equals("sessionid") || name.equals("sessionid_ss") || name.equals("sid_guard"))) return true;
        }
        return false;
    }
}
