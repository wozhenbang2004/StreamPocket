package dev.streampocket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EngineTest {
    @Test public void acceptsAndroidShareCopyWithTrailingChinesePunctuation() {
        assertEquals("https://v.douyin.com/aBc123/", CoreRules.normalizeUrl("复制打开抖音，看视频 https://v.douyin.com/aBc123/。"));
    }
    @Test public void keepsEncodedBilibiliParametersAndDropsFragment() {
        assertEquals("https://www.bilibili.com/video/BV1xx?p=2&tag=a%2Fb", CoreRules.normalizeUrl("https://www.bilibili.com/video/BV1xx?p=2&tag=a%2Fb#reply"));
    }
    @Test public void rejectsLocalAndCredentialBearingTargets() {
        for (String value : new String[]{"http://localhost/a", "http://127.0.0.1/a", "http://192.168.1.2/a", "http://172.20.0.2/a", "http://100.64.0.2/a", "https://user:secret@x.com/a", "file:///storage/test.mp4"}) {
            try { CoreRules.normalizeUrl(value); fail("Unexpected acceptance: " + value); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void redactsLoginSecretsAndSignedMediaQueries() {
        String log = CoreRules.cleanLog("Cookie: auth_token=secret\nhttps://video.twimg.com/file.mp4?token=secret\nhttp://alice:secret@proxy.example.com:8080");
        assertFalse(log.contains("secret")); assertTrue(log.contains("video.twimg.com/file.mp4"));
    }
    @Test public void reportsSpaceFailureAsRecoverableStorageIssue() {
        assertTrue(CoreRules.userMessage(new Exception("write failed: ENOSPC" )).contains("存储空间不足"));
    }
    @Test public void scopesBilibiliCompatibilityToItsOwnHosts() {
        assertTrue(CoreRules.isBilibiliUrl("https://www.bilibili.com/video/BV1nx411u79K"));
        assertTrue(CoreRules.isBilibiliUrl("https://b23.tv/example"));
        assertFalse(CoreRules.isBilibiliUrl("https://bilibili.com.example.org/a"));
        assertFalse(CoreRules.isBilibiliUrl("https://x.com/user/status/1"));
    }
    @Test public void genericFreshCookiesErrorDoesNotAssertServerVerification() {
        String message = CoreRules.inspectionMessage(new Exception("Fresh cookies (not necessarily logged in) are needed"));
        assertTrue(message.contains("暂时没有取得视频信息"));
        assertFalse(message.contains("平台要求验证"));
    }
}
