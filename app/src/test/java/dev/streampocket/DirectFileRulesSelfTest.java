package dev.streampocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Run with plain javac/java: no Android SDK, JSON library or network required. */
public final class DirectFileRulesSelfTest {
    public static void main(String[] args) throws Exception {
        String url = "https://files.example.com/a%2Fb.zip?sig=a%2Bb&key=two+words#anchor";
        check(DirectFileRules.normalizeUrl(url).equals(url.substring(0, url.indexOf('#'))), "Signed bytes changed");
        for (String bad : new String[]{"file:///tmp/file", "https://user:password@example.com/a", "http://127.0.0.1/a",
                "http://10.2.3.4/a", "http://100.100.1.1/a", "http://localhost/a", "https://127.1/a",
                "https://0177.0.0.1/a", "https://example.com/a\n--out=bad"}) {
            try { DirectFileRules.normalizeUrl(bad); throw new AssertionError("Accepted unsafe URL: " + bad); }
            catch (IllegalArgumentException expected) { /* Expected. */ }
        }
        check(DirectFileRules.filename(url, "attachment; filename=plain.zip; filename*=UTF-8''%E6%96%87%E4%BB%B6%2B.zip").equals("文件+.zip"), "Extended filename lost");
        check(DirectFileRules.filename(url, "attachment; filename=\"part; one.zip\"").equals("part; one.zip"), "Quoted semicolon split");
        check(DirectFileRules.filename(url, "attachment; filename*=bad''abc; filename=fallback.zip").equals("fallback.zip"), "Malformed extended name blocked fallback");
        check(DirectFileRules.filename("https://files.example.com/a+b.zip", "").equals("a+b.zip"), "Plus decoded as space");
        String name = DirectFileRules.safeName("../../bad\\file\u202e.zip");
        check(!name.contains("/") && !name.contains("\\") && !name.contains("\u202e") && !name.startsWith("."), "Unsafe filename survived");
        check(DirectFileRules.safeName("长".repeat(200) + ".zip").getBytes(StandardCharsets.UTF_8).length <= 200, "Filename too long");
        check(DirectFileRules.rangeTotal(206, "bytes 10-19/100", 10, 10) == 100, "Valid range rejected");
        for (Object[] bad : new Object[][]{{200,"",10L,100L},{206,"bytes 0-9/100",10L,10L},
                {206,"bytes 10-19/100",10L,11L},{206,"bytes 10-100/100",10L,91L},
                {206,"bytes 9999999999999999999999-100/100",10L,1L}}) {
            try { DirectFileRules.rangeTotal((int)bad[0], (String)bad[1], (long)bad[2], (long)bad[3]); throw new AssertionError("Invalid range accepted"); }
            catch (IOException expected) { /* A 200 response must never be appended. */ }
        }
        String date = "Wed, 21 Oct 2015 07:28:00 GMT";
        check(DirectFileRules.sameIdentity("\"a\"", "", 10, "\"a\"", "", 10), "Strong validator rejected");
        check(!DirectFileRules.sameIdentity("\"a\"", "", 10, "\"b\"", "", 10), "Changed entity accepted");
        check(!DirectFileRules.sameIdentity("W/\"a\"", "", 10, "W/\"a\"", "", 10), "Weak ETag treated as strong");
        check(DirectFileRules.sameIdentity("", date, 10, "", date, 10), "Dated same-size entity rejected");
        check(!DirectFileRules.sameIdentity("", "garbage", 10, "", "garbage", 10), "Invalid date accepted");
        check(!DirectFileRules.sameIdentity("", date, 10, "", date, 11), "Changed length accepted");
        byte[] html = " \ufeff<!doctype html><html>Login</html>".getBytes(StandardCharsets.UTF_8);
        check(DirectFileRules.looksHtml(html, html.length), "HTML response accepted as file");
        byte[] commented = "<!-- gateway -->\n<!doctype html><html>error</html>".getBytes(StandardCharsets.UTF_8);
        check(DirectFileRules.looksHtml(commented, commented.length), "Comment hid HTML error page");
        check(DirectFileRules.length("0") == 0 && DirectFileRules.length("no-length") == -1, "Empty/unknown sizes conflated");
        System.out.println("PASS: signed URLs, unsafe URLs, RFC filenames, safe names, range 200/206, validators, HTML and empty/unknown sizes");
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
