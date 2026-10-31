package dev.streampocket;

/** Runs without Android SDK or JUnit: java dev.streampocket.CoreRulesSelfTest. */
public final class CoreRulesSelfTest {
    public static void main(String[] args) {
        equal("https://v.douyin.com/aBc123/", CoreRules.normalizeUrl("复制打开抖音，看视频 https://v.douyin.com/aBc123/。"));
        equal("https://www.bilibili.com/video/BV1xx?p=2&tag=a%2Fb", CoreRules.normalizeUrl("https://www.bilibili.com/video/BV1xx?p=2&tag=a%2Fb#reply"));
        equal("https://x.com/user/status/123?a=%25&b=%252F", CoreRules.normalizeUrl("https://X.COM/user/status/123?a=%25&b=%252F"));
        equal("https://example.com/%E4%B8%AD%E6%96%87?a=%2f", CoreRules.normalizeUrl("https://example.com/中文?a=%2f"));
        for (String value : new String[]{"http://localhost/a", "http://127.0.0.1/a", "http://192.168.1.2/a", "http://172.20.0.2/a", "http://100.64.0.2/a", "http://2130706433/a", "http://[::1]/a", "https://user:secret@x.com/a", "file:///storage/test.mp4", "https://x.com:99999/a"}) {
            boolean rejected = false;
            try { CoreRules.normalizeUrl(value); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Local, malformed or credential-bearing URL accepted: " + value);
        }
        String safe = CoreRules.cleanLog("Cookie: auth_token=secret\nhttps://video.twimg.com/file.mp4?token=secret\nhttp://alice:secret@proxy.example.com:8080");
        check(!safe.contains("secret"), "Credential escaped log filtering");
        check(safe.contains("video.twimg.com/file.mp4"), "Useful media host removed from log");
        check(CoreRules.userMessage(new Exception("write failed: ENOSPC")).contains("存储空间不足"), "Missing storage recovery message");
        check(CoreRules.userMessage(new Exception("Fresh cookies needed, captcha required")).contains("验证"), "Verification misclassified as ordinary login");
        String freshCookies = CoreRules.inspectionMessage(new Exception("Fresh cookies (not necessarily logged in) are needed"));
        check(freshCookies.contains("会话或解析接口") && !freshCookies.contains("平台要求验证"), "Generic extractor error was misreported as a server verification demand");
        check(CoreRules.isBilibiliUrl("https://www.bilibili.com/video/BV1nx411u79K"), "Bilibili compatibility rule missed the main site");
        check(CoreRules.isBilibiliUrl("https://B23.TV/example"), "Bilibili compatibility rule missed a share link");
        for (String value : new String[]{"https://evilbilibili.com/a", "https://bilibili.com.example.org/a", "https://x.com/user/status/1", "https://www.douyin.com/video/1"})
            check(!CoreRules.isBilibiliUrl(value), "Bilibili Range rule leaked to another platform: " + value);
        check(CoreRules.inspectionMessage(new Exception("No video could be found in this tweet")).contains("当前访问状态"), "Unavailable tweet was reported as an ordinary download failure");
        check(!CoreRules.inspectionMessage(new Exception("unknown parse failure")).contains("临时数据"), "Analysis claimed download data existed");
        check(CoreRules.isTwitterAuthCookieLine("#HttpOnly_.x.com\tTRUE\t/\tTRUE\t0\tauth_token\tfixture"), "HttpOnly auth cookie was missed");
        check(!CoreRules.isTwitterAuthCookieLine(".notx.com\tTRUE\t/\tTRUE\t0\tauth_token\tfixture"), "Unrelated host changed X authentication behavior");
        check(!CoreRules.isTwitterAuthCookieLine(".x.com\tTRUE\t/\tTRUE\t0\tct0\tfixture"), "CSRF cookie was treated as an authenticated account");
        equal("2026.10.26", CoreRules.zipappVersion("# generated\n__version__ = '2026.10.26'\n"));
        check(CoreRules.needsBundledCore("2025.11.12", "2026.10.26"), "Installed old bundle would survive APK upgrade");
        check(!CoreRules.needsBundledCore("2026.10.26", "2026.10.26"), "Equal version was unnecessarily replaced");
        check(!CoreRules.needsBundledCore("2026.11.01.123456", "2026.10.26"), "A newer user engine would be downgraded");
        System.out.println("PASS: share text, encoded query, Unicode path, rejected addresses, secret redaction, recoverable error messages");
    }
    private static void equal(String expected, String actual) { check(expected.equals(actual), "Expected " + expected + "; got " + actual); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
