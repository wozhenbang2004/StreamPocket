package dev.streampocket;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class UnverifiedLoginTest {
    @Test public void scopedUnverifiedCookiesCanBeSavedWithoutClaimingAuthentication() throws Exception {
        String session="# Netscape HTTP Cookie File\n.weibo.com\tTRUE\t/\tTRUE\t0\tguest\tpresent\n";
        assertTrue(LoginActivity.hasUnverifiedSession(LoginPlatform.WEIBO,session));
        assertFalse(LoginPlatform.WEIBO.hasLogin(session));
        assertFalse(LoginActivity.hasUnverifiedSession(LoginPlatform.WEIBO,"# Netscape HTTP Cookie File\n"));
        assertFalse(LoginActivity.hasUnverifiedSession(LoginPlatform.WEIBO,session.replace("\t0\t","\t1\t")));
        try { LoginActivity.hasUnverifiedSession(LoginPlatform.WEIBO,session.replace(".weibo.com",".ixigua.com")); fail("Cross-platform cookies must not be accepted"); }
        catch(java.io.IOException expected) { }
    }

    @Test public void knownPlatformsKeepTheirLoginMarkerRequirement() throws Exception {
        String session="# Netscape HTTP Cookie File\n.reddit.com\tTRUE\t/\tTRUE\t0\treddit_session\tpresent\n";
        assertFalse(LoginActivity.hasUnverifiedSession(LoginPlatform.REDDIT,session));
        assertTrue(LoginPlatform.REDDIT.hasLogin(session));
    }
}
