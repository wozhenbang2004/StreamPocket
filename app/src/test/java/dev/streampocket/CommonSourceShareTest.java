package dev.streampocket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CommonSourceShareTest {
    @Test public void pastedSharesAndBareOfficialDomainsEnterAllSixSourceLanes() {
        LinkBatch.Result result = LinkBatch.parse("微博 t.cn/fixture。西瓜 www.ixigua.com/123?id=456，"
                + "A站 https://www.acfun.cn/v/ac123?part=2\n"
                + "Reddit old.reddit.com/r/videos/comments/abc123/title/\n"
                + "Vimeo player.vimeo.com/video/123?h=secret；Twitch clips.twitch.tv/FixtureClip");
        VideoPlatform[] expected = {VideoPlatform.WEIBO, VideoPlatform.XIGUA, VideoPlatform.ACFUN,
                VideoPlatform.REDDIT, VideoPlatform.VIMEO, VideoPlatform.TWITCH};
        assertEquals(expected.length, result.urls.size());
        assertEquals(0, result.duplicates);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], VideoPlatform.detect(result.urls.get(i)));
            assertEquals(expected[i].id, SourceScheduler.platform(result.urls.get(i)));
        }
        assertTrue(result.urls.get(1).endsWith("?id=456"));
        assertTrue(result.urls.get(4).endsWith("?h=secret"));
    }

    @Test public void dedupRetainsTheFirstCompleteRequestWhileCombiningRedditAliases() {
        String first = "https://old.reddit.com/r/test/comments/abc123/title/?token=a%2Fb&utm_source=share";
        String second = "https://www.reddit.com/comments/ABC123/new_title/?token=a%2Fb";
        LinkBatch.Result result = LinkBatch.parse(first + "\n" + second);
        assertEquals(1, result.urls.size());
        assertEquals(1, result.duplicates);
        assertEquals(first.replace("old.reddit.com", "www.reddit.com"), result.urls.get(0));
    }

    @Test public void privateHashesAndContentSelectorsRemainSeparateInOnePaste() {
        String[] urls = {"https://vimeo.com/123/privateA?h=queryA", "https://vimeo.com/123/privateB?h=queryA",
                "https://player.vimeo.com/video/123?h=queryA", "https://player.vimeo.com/video/123?h=queryB",
                "https://weibo.com/u/123?tabtype=newVideo&layerid=111", "https://weibo.com/u/123?tabtype=newVideo&layerid=222",
                "https://www.ixigua.com/?id=111", "https://www.ixigua.com/?id=222",
                "https://www.twitch.tv/user/schedule?vodID=111", "https://www.twitch.tv/user/schedule?vodID=222"};
        LinkBatch.Result result = LinkBatch.parse(String.join("\n", urls));
        assertEquals(urls.length, result.urls.size());
        assertEquals(0, result.duplicates);
        for (int i = 0; i < urls.length; i++) assertEquals(urls[i], result.urls.get(i));
    }

    @Test public void existingPlatformAliasesAndUnknownSiteFallbackRemainAvailable() {
        LinkBatch.Result result = LinkBatch.parse("https://x.com/user/status/123?s=19\n"
                + "https://twitter.com/user/status/123\n"
                + "https://youtu.be/dQw4w9WgXcQ\nhttps://www.youtube.com/watch?v=dQw4w9WgXcQ\n"
                + "https://example.org/watch?token=a%2Fb\nhttps://example.org/watch?token=c%2Fd");
        assertEquals(4, result.urls.size());
        assertEquals(2, result.duplicates);
        assertNull(VideoPlatform.detect(result.urls.get(2)));
        assertEquals("https://example.org/watch?token=a%2Fb", result.urls.get(2));
        assertEquals("https://example.org/watch?token=c%2Fd", result.urls.get(3));
    }

    @Test public void twitchGoSharesDeduplicateOldAndNewPathsWithoutDiscardingRequestParameters() {
        String query = "?t=5m10s&token=a%2Fb%3D&token=second";
        String first = "https://go.twitch.tv/riotgames/video/6528877" + query;
        LinkBatch.Result result = LinkBatch.parse(first + "\n"
                + "https://www.twitch.tv/riotgames/v/6528877" + query + "&utm_source=share\n"
                + "go.twitch.tv/videos/6528877" + query + "\n"
                + first.replace("a%2Fb%3D", "changed") + "\n" + first.replace("5m10s", "6m"));
        assertEquals(3, result.urls.size());
        assertEquals(2, result.duplicates);
        assertEquals(first, result.urls.get(0));
        for (String url : result.urls) CommonVideoLinks.validateSingleVideoUrl(url);
    }
}
