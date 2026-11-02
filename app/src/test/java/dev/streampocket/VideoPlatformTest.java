package dev.streampocket;

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
        for (VideoPlatform channel : VideoPlatform.values()) {
            String host = channel == VideoPlatform.YOUTUBE ? "www.youtube.com/watch?v=BaW_jenozKc"
                    : channel == VideoPlatform.XIAOHONGSHU ? "www.xiaohongshu.com/explore/abcdef"
                    : channel == VideoPlatform.KUAISHOU ? "www.kuaishou.com/short-video/3fixture"
                    : channel == VideoPlatform.TIKTOK ? "www.tiktok.com/@fixture/video/123"
                    : channel == VideoPlatform.INSTAGRAM ? "www.instagram.com/reel/fixture"
                    : channel == VideoPlatform.FACEBOOK ? "www.facebook.com/reel/123"
                    : channel == VideoPlatform.PINTEREST ? "www.pinterest.com/pin/123"
                    : channel == VideoPlatform.HEYBOX ? "www.xiaoheihe.cn/app/bbs/link/123"
                    : channel == VideoPlatform.PORNHUB ? "www.pornhub.com/view_video.php?viewkey=phfixture123"
                    : channel == VideoPlatform.X ? "x.com/u/status/123"
                    : channel == VideoPlatform.BILIBILI ? "www.bilibili.com/video/BVfixture" : "www.douyin.com/video/123";
            Engine.Media item = Engine.mediaFromMetadata(new JSONObject().put("id", "fixture").put("title", "fixture")
                    .put("url", "https://media.example/video.mp4"), "https://" + host).get(0);
            assertEquals(channel.title, Engine.Media.fromJson(item.toJson()).platform);
        }
    }
}
