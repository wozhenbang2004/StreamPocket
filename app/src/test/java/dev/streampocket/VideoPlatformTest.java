package dev.streampocket;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VideoPlatformTest {
    @Test public void recognizesNativeAndShortShareDomainsWithoutMatchingLookalikes() {
        String[][] links = {
                {"https://xhslink.com/o/fixture", "xiaohongshu"},
                {"https://www.xiaohongshu.com/explore/abcdef?xsec_token=A%2FB%3D", "xiaohongshu"},
                {"https://v.kuaishou.com/fixture", "kuaishou"},
                {"https://www.kuaishou.com/short-video/3fixture", "kuaishou"},
                {"https://vm.tiktok.com/fixture/", "tiktok"},
                {"https://www.instagram.com/reel/fixture/", "instagram"},
                {"https://fb.watch/fixture/", "facebook"},
                {"https://pin.it/fixture", "pinterest"}};
        for (String[] link : links) {
            assertEquals(link[1], SourceScheduler.platform(link[0]));
            assertEquals(link[1], VideoPlatform.detect(link[0]).id);
        }
        for (String value : new String[]{"https://notinstagram.com/reel/id", "https://tiktok.com.attacker.example/v", "https://pinterest.com@evil.example/pin/id"})
            assertNull(value, VideoPlatform.detect(value));
    }

    @Test public void pasteAcceptsAllSixNewChannelsAndKeepsSignedQueryBytes() {
        String input = "小红书 xhslink.com/o/one，快手 v.kuaishou.com/two，TikTok vm.tiktok.com/three，"
                + "instagram.com/reel/four/ facebook.com/reel/5 pin.it/six";
        LinkBatch.Result batch = LinkBatch.parse(input);
        assertEquals(6, batch.urls.size());
        String signed = "https://xiaohongshu.com/explore/abcdef?xsec_token=a%2Fb%3D&xsec_source=pc_share";
        assertEquals(signed, LinkBatch.parse(signed).urls.get(0));
        assertEquals("https://www.xiaohongshu.com/explore/abcdef?xsec_token=a%2Fb%3D&xsec_source=pc_share", Engine.normalizeUrl(signed));
        assertEquals(2, LinkBatch.parse(signed + " " + signed.replace("a%2Fb%3D", "another")).urls.size());
    }

    @Test public void parsedChannelsKeepTheirIdentityAfterQueueSerialization() throws Exception {
        Map<VideoPlatform, String> urls = new EnumMap<>(VideoPlatform.class);
        urls.put(VideoPlatform.X, "https://x.com/u/status/123");
        urls.put(VideoPlatform.BILIBILI, "https://www.bilibili.com/video/BVfixture");
        urls.put(VideoPlatform.DOUYIN, "https://www.douyin.com/video/123");
        urls.put(VideoPlatform.YOUTUBE, "https://www.youtube.com/watch?v=BaW_jenozKc");
        urls.put(VideoPlatform.XIAOHONGSHU, "https://www.xiaohongshu.com/explore/abcdef");
        urls.put(VideoPlatform.KUAISHOU, "https://www.kuaishou.com/short-video/3fixture");
        urls.put(VideoPlatform.TIKTOK, "https://www.tiktok.com/@fixture/video/123");
        urls.put(VideoPlatform.INSTAGRAM, "https://www.instagram.com/reel/fixture");
        urls.put(VideoPlatform.FACEBOOK, "https://www.facebook.com/reel/123");
        urls.put(VideoPlatform.PINTEREST, "https://www.pinterest.com/pin/123");
        urls.put(VideoPlatform.HEYBOX, "https://www.xiaoheihe.cn/app/bbs/link/123");
        urls.put(VideoPlatform.PORNHUB, "https://www.pornhub.com/view_video.php?viewkey=phfixture123");
        urls.put(VideoPlatform.WEIBO, "https://weibo.com/tv/show?fid=1034%3A123");
        urls.put(VideoPlatform.XIGUA, "https://www.ixigua.com/?id=123");
        urls.put(VideoPlatform.ACFUN, "https://www.acfun.cn/v/ac123");
        urls.put(VideoPlatform.REDDIT, "https://www.reddit.com/r/videos/comments/abc123/title/");
        urls.put(VideoPlatform.VIMEO, "https://vimeo.com/123/privateHash?h=queryHash");
        urls.put(VideoPlatform.TWITCH, "https://www.twitch.tv/videos/635475444?t=5m10s");
        assertEquals("Each platform needs its own serialization fixture", EnumSet.allOf(VideoPlatform.class), urls.keySet());
        for (VideoPlatform channel : VideoPlatform.values()) {
            String url = urls.get(channel);
            assertEquals(channel.id, channel, VideoPlatform.detect(url));
            Engine.Media item = Engine.mediaFromMetadata(new JSONObject().put("id", "fixture").put("title", "fixture")
                    .put("url", "https://media.example/video.mp4"), url).get(0);
            Engine.Media restored = Engine.Media.fromJson(new JSONObject(item.toJson().toString()));
            assertEquals(channel.id, channel.title, restored.platform);
            assertEquals(channel.id, url, restored.url);
            assertEquals(channel.id, "fixture", restored.id);
            assertEquals(channel.id, "fixture", restored.title);
        }
    }
}
