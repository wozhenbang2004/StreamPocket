package dev.streampocket;

import org.junit.Test;
import static org.junit.Assert.*;

/** Offline source rules; URL fixtures are not claims of a successful network download. */
public final class CommonVideoLinksTest {
    @Test public void identifiesSixSourcesAndTheirOwnedShareHostsWithoutLookalikes() {
        Object[][] cases = {
                {VideoPlatform.WEIBO, "https://weibo.com/tv/show?fid=1034%3Afixture"},
                {VideoPlatform.WEIBO, "https://m.weibo.cn/detail/fixture"},
                {VideoPlatform.WEIBO, "https://t.cn/fixture"},
                {VideoPlatform.XIGUA, "https://www.ixigua.com/fixture?id=123"},
                {VideoPlatform.XIGUA, "https://m.ixigua.com/fixture"},
                {VideoPlatform.ACFUN, "https://www.acfun.cn/v/ac123"},
                {VideoPlatform.REDDIT, "https://old.reddit.com/r/test/comments/abc123/title/"},
                {VideoPlatform.REDDIT, "https://redd.it/abc123"},
                {VideoPlatform.REDDIT, "https://v.redd.it/fixture"},
                {VideoPlatform.VIMEO, "https://player.vimeo.com/video/123?h=secret"},
                {VideoPlatform.TWITCH, "https://clips.twitch.tv/FixtureClip"}
        };
        for (Object[] item : cases) assertEquals((String) item[1], item[0], VideoPlatform.detect((String) item[1]));
        for (String host : new String[]{"weibo.com", "ixigua.com", "acfun.cn", "reddit.com", "vimeo.com", "twitch.tv"}) {
            assertNull(VideoPlatform.detect("https://" + host + ".example.org/video"));
            assertNull(VideoPlatform.detect("https://user@" + host + "/video"));
            assertNull(VideoPlatform.detect("file://" + host + "/video"));
        }
    }

    @Test public void normalizingWeiboPreservesContentSelectionAndEncodedParameters() {
        assertEquals("https://weibo.com/tv/show?fid=1034%3A123&token=a%2Bb%3D&f=1",
                CommonVideoLinks.normalizeVideo("分享 https://WWW.WEIBO.COM/tv/show?fid=1034%3A123&token=a%2Bb%3D&f=1#player"));
        String userVideo = "https://weibo.com/u/123?tabtype=newVideo&layerid=456&foo=one&foo=two";
        assertEquals(userVideo, CommonVideoLinks.normalizeVideo(userVideo));
        assertNotEquals(CommonVideoLinks.identity(userVideo), CommonVideoLinks.identity(userVideo.replace("456", "457")));
        assertNotEquals(CommonVideoLinks.identity(userVideo), CommonVideoLinks.identity(userVideo.replace("newVideo", "other")));
    }

    @Test public void xiguaAndAcfunKeepFullExtractorInputWithoutInventingAnIdRule() {
        for (String url : new String[]{"https://www.ixigua.com/?id=123&token=a%2Fb&foo=1&foo=2",
                "https://www.acfun.cn/v/ac123?part=2&token=one%3Dtwo",
                "https://www.acfun.cn/bangumi/aa123?resource=fixture"})
            assertEquals(url, CommonVideoLinks.normalizeVideo(url));
        assertNotEquals(CommonVideoLinks.identity("https://www.ixigua.com/?id=123"),
                CommonVideoLinks.identity("https://www.ixigua.com/?id=124"));
    }

    @Test public void redditAliasesShareAnIdentityWithoutRewritingIntoSilentCdnMedia() {
        String old = "https://old.reddit.com/r/videos/comments/ABC123/old_title/?utm_source=share";
        String current = "https://www.reddit.com/comments/abc123/new_title/";
        assertEquals(CommonVideoLinks.identity(current), CommonVideoLinks.identity(old));
        assertEquals("https://www.reddit.com/r/videos/comments/ABC123/old_title/?utm_source=share", CommonVideoLinks.normalizeVideo(old));
        assertNotEquals(CommonVideoLinks.identity(current), CommonVideoLinks.identity("https://v.redd.it/abc123/DASH_720.mp4"));
        assertEquals("https://redd.it/abc123", CommonVideoLinks.normalizeVideo("https://redd.it/abc123"));
    }

    @Test public void vimeoPrivacyHashAndSignedQueryNeverCollapseOrDisappear() {
        String video = "https://vimeo.com/123/privateHash?h=queryHash&token=a%2Fb%3D&expires=12345";
        assertEquals(video, CommonVideoLinks.normalizeVideo(video));
        assertNotEquals(CommonVideoLinks.identity(video), CommonVideoLinks.identity(video.replace("privateHash", "anotherHash")));
        assertNotEquals(CommonVideoLinks.identity(video), CommonVideoLinks.identity(video.replace("queryHash", "anotherQueryHash")));
        assertNotEquals(CommonVideoLinks.identity(video), CommonVideoLinks.identity(video.replace("a%2Fb%3D", "different")));
        assertEquals(CommonVideoLinks.identity("https://vimeo.com/123?h=one"), CommonVideoLinks.identity("https://player.vimeo.com/video/123?h=one"));
        assertNotEquals(CommonVideoLinks.identity("https://vimeo.com/123?h=one"), CommonVideoLinks.identity("https://player.vimeo.com/video/123?h=two"));
    }

    @Test public void onlyMarketingParametersAreIgnoredForDedupAndNeverForTheRequest() {
        String withTracking = "https://www.ixigua.com/123?id=456&token=a%2Fb&utm_source=copy";
        assertEquals(withTracking, CommonVideoLinks.normalizeVideo(withTracking));
        assertEquals(CommonVideoLinks.identity(withTracking), CommonVideoLinks.identity("https://www.ixigua.com/123?id=456&token=a%2Fb"));
        assertNotEquals(CommonVideoLinks.identity(withTracking), CommonVideoLinks.identity("https://www.ixigua.com/123?id=456&token=other"));
        assertNotEquals(CommonVideoLinks.identity("https://vimeo.com/123?h=a&h=b"), CommonVideoLinks.identity("https://vimeo.com/123?h=b&h=a"));
    }

    @Test public void twitchExplicitVodClipAndEmbedSelectorsPassPreflightWithoutRewriting() {
        String[] urls = {
                "http://www.twitch.tv/riotgames/v/6528877?t=5m10s",
                "https://www.twitch.tv/videos/635475444",
                "https://www.twitch.tv/tangotek/schedule?vodID=1822395420",
                "https://player.twitch.tv/?video=v40464143&parent=streamernews.example.com&autoplay=false",
                "https://player.twitch.tv/?vodID=40464143&t=5m",
                "https://clips.twitch.tv/FaintLightGullWholeWheat",
                "https://clips.twitch.tv/embed?clip=FixtureClip&parent=example.org",
                "https://www.twitch.tv/user/clip/FixtureClip"
        };
        for (String url : urls) {
            CommonVideoLinks.validateSingleVideoUrl(url);
            assertEquals(url, CommonVideoLinks.normalizeVideo(url));
        }
    }

    @Test public void twitchLiveChannelAndMixedSelectorsGiveActionableSingleVideoInstruction() {
        for (String url : new String[]{"https://www.twitch.tv/riotgames", "https://www.twitch.tv/riotgames/videos",
                "https://www.twitch.tv/riotgames/schedule", "https://www.twitch.tv/directory/category/games",
                "https://player.twitch.tv/?channel=live&video=v40464143", "https://player.twitch.tv/?channel=&video=v40464143",
                "https://player.twitch.tv/?%63hannel=live&video=v40464143", "https://player.twitch.tv/?video=1&video=2",
                "https://clips.twitch.tv/embed/", "https://player.twitch.tv/", "https://dashboard.twitch.tv/videos/123",
                "https://go.twitch.tv/riotgames", "https://go.twitch.tv/riotgames/video/6528877?channel=live&video=v6528877",
                "https://arbitrary.twitch.tv/riotgames/video/6528877?t=5m10s"}) {
            try { CommonVideoLinks.validateSingleVideoUrl(url); fail(url); }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("具体的 Twitch 剪辑"));
                assertEquals(expected.getMessage(), CoreRules.userMessage(expected));
                assertEquals(expected.getMessage(), CoreRules.inspectionMessage(expected));
            }
        }
    }

    @Test public void upstreamTwitchGoAndLongVideoAliasesKeepTimeAndAuthorizationParameters() {
        String query = "?t=5m10s&token=a%2Fb%3D&token=second";
        String canonical = "https://www.twitch.tv/videos/6528877" + query;
        for (String base : new String[]{"http://www.twitch.tv/riotgames/v/6528877",
                "https://www.twitch.tv/riotgames/video/6528877", "https://go.twitch.tv/riotgames/v/6528877",
                "https://go.twitch.tv/riotgames/video/6528877", "https://go.twitch.tv/videos/6528877"}) {
            String url = base + query;
            CommonVideoLinks.validateSingleVideoUrl(url);
            assertEquals(url, CommonVideoLinks.normalizeVideo(url));
            assertEquals(CommonVideoLinks.identity(canonical), CommonVideoLinks.identity(url));
            assertNotEquals(CommonVideoLinks.identity(url), CommonVideoLinks.identity(url.replace("a%2Fb%3D", "changed")));
            assertNotEquals(CommonVideoLinks.identity(url), CommonVideoLinks.identity(url.replace("5m10s", "6m")));
        }
    }

    @Test public void twitchTimestampAndVideoSelectorsRemainPartOfIdentity() {
        assertEquals(CommonVideoLinks.identity("https://www.twitch.tv/videos/6528877?t=5m10s"),
                CommonVideoLinks.identity("http://www.twitch.tv/riotgames/v/6528877?t=5m10s"));
        assertNotEquals(CommonVideoLinks.identity("https://www.twitch.tv/videos/6528877?t=5m10s"),
                CommonVideoLinks.identity("https://www.twitch.tv/videos/6528877?t=10m"));
        assertNotEquals(CommonVideoLinks.identity("https://player.twitch.tv/?video=v1"), CommonVideoLinks.identity("https://player.twitch.tv/?video=v2"));
        assertNotEquals(CommonVideoLinks.identity("https://www.twitch.tv/user/schedule?vodID=1"), CommonVideoLinks.identity("https://www.twitch.tv/user/schedule?vodID=2"));
    }

    @Test public void unknownSitesAndExistingPlatformsKeepTheirExistingRules() {
        for (String url : new String[]{"https://example.org/watch?id=1&token=a%2Fb", "https://x.com/user/status/123",
                "https://www.bilibili.com/video/BVfixture?p=2", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"}) {
            assertEquals(url, CommonVideoLinks.normalizeVideo(url));
            assertEquals("", CommonVideoLinks.identity(url));
            CommonVideoLinks.validateSingleVideoUrl(url);
        }
        assertNull(VideoPlatform.detect("https://example.org/watch?id=1"));
    }
}
