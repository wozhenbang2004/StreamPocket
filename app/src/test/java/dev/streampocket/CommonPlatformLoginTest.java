package dev.streampocket;

import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CommonPlatformLoginTest {
    private static final String HEADER = "# Netscape HTTP Cookie File\n\n";

    @Test public void sourceAndAccountIdsMatchAndEveryEntryStaysOnOfficialOrigins() throws Exception {
        for (LoginPlatform platform : new LoginPlatform[]{LoginPlatform.WEIBO, LoginPlatform.XIGUA, LoginPlatform.ACFUN,
                LoginPlatform.REDDIT, LoginPlatform.VIMEO, LoginPlatform.TWITCH}) {
            assertEquals(platform.id, VideoPlatform.detect(platform.loginUrl).id);
            assertEquals(platform, LoginPlatform.fromId(platform.id));
            assertTrue(platform.allowsNavigation(platform.loginUrl));
            for (String url : platform.cookieUrls()) assertTrue(url, platform.ownsDomain(new URI(url).getHost()));
            assertFalse(platform.allowsNavigation(platform.loginUrl.replace("https://", "http://")));
            assertFalse(platform.allowsNavigation("https://" + new URI(platform.loginUrl).getHost() + ".example.org/"));
        }
    }

    @Test public void shareShortenersAndOtherBytedanceSitesDoNotGainAccountOwnership() {
        assertFalse(LoginPlatform.WEIBO.ownsDomain("t.cn"));
        assertFalse(LoginPlatform.REDDIT.ownsDomain("redd.it"));
        assertFalse(LoginPlatform.REDDIT.ownsDomain("v.redd.it"));
        assertFalse(LoginPlatform.XIGUA.ownsDomain("douyin.com"));
        assertFalse(LoginPlatform.DOUYIN.ownsDomain("ixigua.com"));
        assertFalse(LoginPlatform.TWITCH.ownsDomain("twitch.tv.example.org"));
    }

    @Test public void unverifiedMarkersNeverTurnGuestCookiesIntoALoginClaim() {
        assertFalse(LoginPlatform.WEIBO.hasKnownLoginMarker());
        assertFalse(LoginPlatform.XIGUA.hasKnownLoginMarker());
        assertFalse(LoginPlatform.ACFUN.hasKnownLoginMarker());
        assertFalse(LoginPlatform.WEIBO.hasLogin(HEADER + row(".weibo.com", "TRUE", "/", "0", "SUB", "synthetic")));
        assertFalse(LoginPlatform.XIGUA.hasLogin(HEADER + row(".ixigua.com", "TRUE", "/", "0", "sessionid", "synthetic")));
        assertFalse(LoginPlatform.ACFUN.hasLogin(HEADER + row(".acfun.cn", "TRUE", "/", "0", "session", "synthetic")));
    }

    @Test public void verifiedMarkerNamesMustActuallyReachTheExtractorHost() {
        assertTrue(LoginPlatform.REDDIT.hasKnownLoginMarker());
        assertTrue(LoginPlatform.REDDIT.hasLogin(HEADER + row(".reddit.com", "TRUE", "/", "0", "reddit_session", "synthetic")));
        assertTrue(LoginPlatform.REDDIT.hasLogin(HEADER + row("www.reddit.com", "FALSE", "/", "0", "reddit_session", "synthetic")));
        assertFalse(LoginPlatform.REDDIT.hasLogin(HEADER + row("old.reddit.com", "FALSE", "/", "0", "reddit_session", "synthetic")));
        assertFalse(LoginPlatform.REDDIT.hasLogin(HEADER + row("reddit.com", "FALSE", "/", "0", "reddit_session", "synthetic")));
        assertTrue(LoginPlatform.VIMEO.hasLogin(HEADER + row("vimeo.com", "FALSE", "/", "0", "vimeo", "synthetic")));
        assertFalse(LoginPlatform.VIMEO.hasLogin(HEADER + row("player.vimeo.com", "FALSE", "/", "0", "vimeo", "synthetic")));
        assertTrue(LoginPlatform.TWITCH.hasLogin(HEADER + row("#HttpOnly_.twitch.tv", "TRUE", "/", "0", "auth-token", "synthetic")));
        assertTrue(LoginPlatform.TWITCH.hasLogin(HEADER + row("gql.twitch.tv", "FALSE", "/", "0", "auth-token", "synthetic")));
        assertFalse(LoginPlatform.TWITCH.hasLogin(HEADER + row("www.twitch.tv", "FALSE", "/", "0", "auth-token", "synthetic")));
    }

    @Test public void expiredBlankGuestAndPathScopedCookiesAreNotLoginEvidence() {
        LoginPlatform[] platforms = {LoginPlatform.REDDIT, LoginPlatform.VIMEO, LoginPlatform.TWITCH};
        String[] hosts = {".reddit.com", ".vimeo.com", ".twitch.tv"};
        String[] names = {"reddit_session", "vimeo", "auth-token"};
        for (int i = 0; i < platforms.length; i++) {
            assertFalse(platforms[i].hasLogin(HEADER + row(hosts[i], "TRUE", "/", "1", names[i], "synthetic")));
            assertFalse(platforms[i].hasLogin(HEADER + row(hosts[i], "TRUE", "/", "0", names[i], "")));
            assertFalse(platforms[i].hasLogin(HEADER + row(hosts[i], "TRUE", "/", "0", "visitor", "synthetic")));
            assertFalse(platforms[i].hasLogin(HEADER + row(hosts[i], "TRUE", "/private", "0", names[i], "synthetic")));
            assertFalse(platforms[i].hasLogin(HEADER + row(hosts[i] + ".example.org", "TRUE", "/", "0", names[i], "synthetic")));
        }
    }

    private static String row(String domain, String subdomains, String path, String expiry, String name, String value) {
        return domain + "\t" + subdomains + "\t" + path + "\tTRUE\t" + expiry + "\t" + name + "\t" + value + "\n";
    }
}
