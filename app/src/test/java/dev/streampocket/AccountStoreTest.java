package dev.streampocket;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic credentials only. No network, browser or real account is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AccountStoreTest {
    private static final String HEADER = "# Netscape HTTP Cookie File\n\n";
    private Context app;
    private AccountStore store;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("account-sessions-metadata", 0).edit().clear().commit();
        delete(new File(app.getNoBackupFilesDir(), "account-sessions"));
        new File(app.getFilesDir(), "cookies.txt").delete();
        store = new AccountStore(app, new SecretKeySpec(new byte[32], "AES"));
    }

    @Test public void platformDomainsAndNavigationRejectLookalikesAndUnsafeSchemes() {
        assertTrue(LoginPlatform.BILIBILI.ownsDomain("#HttpOnly_.bilibili.com"));
        assertTrue(LoginPlatform.X.allowsNavigation("https://x.com/i/flow/login"));
        for (String value : new String[]{"https://x.com.attacker.example/", "https://evilx.com/", "http://x.com/", "https://user@x.com/", "https://x.com:1234/", "intent://x.com/", "file:///data/test"})
            assertFalse(value, LoginPlatform.X.allowsNavigation(value));
        assertFalse(LoginPlatform.BILIBILI.ownsDomain("bilibili.com.example.org"));
        assertFalse(LoginPlatform.BILIBILI.ownsDomain("..bilibili.com"));
    }

    @Test public void fullCookieInfoPreservesOriginalScopeHttpOnlyPathAndExpiry() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("https://api.bilibili.com/", Arrays.asList(
                "SESSDATA=fixture=a=b; Domain=.bilibili.com; Path=/; Secure; HttpOnly; Max-Age=3600; SameSite=None",
                "path_cookie=narrow; Path=/private; Secure",
                "expired=gone; Path=/; Max-Age=0"));
        long before = System.currentTimeMillis() / 1000;
        String exported = AccountStore.fromWebCookieInfo(LoginPlatform.BILIBILI, values);
        assertTrue(exported.contains("#HttpOnly_.bilibili.com\tTRUE\t/\tTRUE\t"));
        assertTrue(exported.contains("\tSESSDATA\tfixture=a=b\n"));
        assertTrue(exported.contains("api.bilibili.com\tFALSE\t/private\tTRUE\t0\tpath_cookie\tnarrow"));
        assertFalse(exported.contains("expired"));
        String first = exported.split("\n")[2];
        long expires = Long.parseLong(first.split("\t")[4]);
        assertTrue(expires >= before + 3599 && expires <= before + 3601);
        assertTrue(LoginPlatform.BILIBILI.hasLogin(exported));
    }

    @Test public void cookieInfoExpiresDateIsRetainedAndWrongDomainIsRejected() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("https://x.com/", Arrays.asList("auth_token=fixture; Expires=Wed, 09 Jun 2038 10:18:14 GMT; Path=/; HttpOnly; Secure"));
        String exported = AccountStore.fromWebCookieInfo(LoginPlatform.X, values);
        assertTrue(LoginPlatform.X.hasLogin(exported));
        assertFalse(exported.contains("\tTRUE\t0\tauth_token"));
        values.put("https://x.com/", Arrays.asList("auth_token=fixture; Domain=.twitter.com; Path=/"));
        try { AccountStore.fromWebCookieInfo(LoginPlatform.X, values); fail(); } catch (IllegalArgumentException expected) {}
        values.put("https://x.com/", Arrays.asList("auth_token=fixture; Domain=.com; Path=/"));
        try { AccountStore.fromWebCookieInfo(LoginPlatform.X, values); fail(); } catch (IllegalArgumentException expected) {}
    }

    @Test public void chromiumExplicitHostOnlyDomainNeverWidensToSubdomainsOrAParent() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("https://api.bilibili.com/", Arrays.asList("SESSDATA=fixture; Domain=api.bilibili.com; Path=/; Secure; HttpOnly"));
        String exported = AccountStore.fromWebCookieInfo(LoginPlatform.BILIBILI, values);
        assertTrue(exported.contains("#HttpOnly_api.bilibili.com\tFALSE\t/\tTRUE\t0\tSESSDATA\tfixture"));
        assertFalse(exported.contains(".api.bilibili.com\tTRUE"));
        values.put("https://api.bilibili.com/", Arrays.asList("SESSDATA=fixture; Domain=bilibili.com; Path=/; Secure"));
        try { AccountStore.fromWebCookieInfo(LoginPlatform.BILIBILI, values); fail("Host-only parent must not be widened"); }
        catch (IllegalArgumentException expected) {}
        values.put("https://api.bilibili.com/", Arrays.asList("SESSDATA=fixture; Domain=.bilibili.com; Path=/; Secure"));
        assertTrue(AccountStore.fromWebCookieInfo(LoginPlatform.BILIBILI, values).contains(".bilibili.com\tTRUE\t/\tTRUE"));
    }

    @Test public void headerFallbackKeepsExactHostAndEqualsValuesAndRejectsInjection() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("https://x.com/", "auth_token=fixture==; ct0=csrf");
        String exported = AccountStore.fromWebCookies(LoginPlatform.X, values);
        assertTrue(exported.contains("x.com\tFALSE\t/\tTRUE\t0\tauth_token\tfixture=="));
        assertFalse(exported.contains(".twitter.com"));
        assertFalse(exported.contains(".x.com\tTRUE"));
        assertTrue(LoginPlatform.X.hasLogin(exported));
        values.put("https://x.com/", "auth_token=fixture\r\nInjected=yes");
        try { AccountStore.fromWebCookies(LoginPlatform.X, values); fail(); } catch (IllegalArgumentException expected) {}
        values.clear(); values.put("https://x.com.attacker.example/", "auth_token=fixture");
        try { AccountStore.fromWebCookies(LoginPlatform.X, values); fail(); } catch (IllegalArgumentException expected) {}
    }

    @Test public void authPresenceIsNotConfusedWithCsrfAnonymousOrExpiredCookies() {
        assertFalse(LoginPlatform.X.hasLogin(HEADER + row("x.com", "ct0", "csrf")));
        assertFalse(LoginPlatform.DOUYIN.hasLogin(HEADER + row("www.douyin.com", "ttwid", "guest")));
        assertFalse(LoginPlatform.BILIBILI.hasLogin(HEADER + row("bilibili.com.example.org", "SESSDATA", "fixture")));
        assertFalse(LoginPlatform.X.hasLogin(HEADER + "x.com\tFALSE\t/\tTRUE\t1\tauth_token\tfixture\n"));
        assertTrue(LoginPlatform.DOUYIN.hasLogin(HEADER + row("www.douyin.com", "sessionid_ss", "fixture")));
    }

    @Test public void encryptedPlatformSessionsCanBeDisabledAndClearedIndependently() throws Exception {
        store.save(LoginPlatform.X, HEADER + row("x.com", "auth_token", "secret-fixture-x"), "网页登录");
        store.save(LoginPlatform.BILIBILI, HEADER + row(".bilibili.com", "SESSDATA", "secret-fixture-bili"), "文件导入");
        assertTrue(store.hasSaved(LoginPlatform.X));
        assertEquals("网页登录", store.source(LoginPlatform.X));
        assertTrue(store.savedAt(LoginPlatform.X) > 0);
        assertTrue(store.hasTwitterLogin());
        byte[] encrypted = Files.readAllBytes(new File(app.getNoBackupFilesDir(), "account-sessions/x.session").toPath());
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("secret-fixture-x"));
        assertEquals(HEADER + row("x.com", "auth_token", "secret-fixture-x"), store.load(LoginPlatform.X));
        File snapshot = new File(app.getCacheDir(), "test-accounts.txt");
        store.writeSnapshot(snapshot);
        assertTrue(readText(snapshot.toPath()).contains("secret-fixture-x"));
        store.setEnabled(LoginPlatform.X, false);
        assertFalse(store.hasTwitterLogin());
        store.writeSnapshot(snapshot);
        String disabled = readText(snapshot.toPath());
        assertFalse(disabled.contains("secret-fixture-x"));
        assertTrue(disabled.contains("secret-fixture-bili"));
        assertTrue(store.load(LoginPlatform.X).contains("secret-fixture-x"));
        store.clear(LoginPlatform.X);
        assertFalse(store.hasSaved(LoginPlatform.X));
        assertTrue(store.hasSaved(LoginPlatform.BILIBILI));
        snapshot.delete();
    }

    @Test public void legacyMigrationPreservesOtherSitesAndNeverResurrectsClearedPlatform() throws Exception {
        File legacy = new File(app.getFilesDir(), "cookies.txt");
        writeText(legacy.toPath(), HEADER + row(".x.com", "auth_token", "migrated-x")
                + row(".bilibili.com", "SESSDATA", "migrated-bili") + row(".youtube.com", "SID", "migrated-other"));
        store.prepare();
        assertFalse(legacy.exists());
        assertTrue(store.hasSaved(LoginPlatform.X));
        store.clear(LoginPlatform.X);
        File snapshot = new File(app.getCacheDir(), "test-legacy.txt");
        store.writeSnapshot(snapshot);
        String result = readText(snapshot.toPath());
        assertFalse(result.contains("migrated-x"));
        assertTrue(result.contains("migrated-bili"));
        assertTrue(result.contains("migrated-other"));
        store.prepare();
        assertFalse(store.hasSaved(LoginPlatform.X));
        snapshot.delete();
    }

    @Test public void invalidOrCrossPlatformSavePreservesPreviouslySavedSession() throws Exception {
        store.save(LoginPlatform.X, HEADER + row("x.com", "auth_token", "preserved"), "网页登录");
        for (String invalid : new String[]{HEADER + "broken\trow", HEADER + row("x.com", "auth_token", "new") + row("www.douyin.com", "sessionid", "other"), HEADER + "x.com\tFALSE\t/\tTRUE\t0\tauth_token\tbad\u0000value\n"}) {
            try { store.save(LoginPlatform.X, invalid, "网页登录"); fail(); } catch (java.io.IOException expected) {}
            assertTrue(store.load(LoginPlatform.X).contains("preserved"));
        }
    }

    @Test public void tamperingOrWrongKeyFailsClosedWithoutPlaintextFallback() throws Exception {
        store.save(LoginPlatform.X, HEADER + row("x.com", "auth_token", "preserved"), "网页登录");
        byte[] different = new byte[32]; different[0] = 1;
        AccountStore wrongKey = new AccountStore(app, new SecretKeySpec(different, "AES"));
        try { wrongKey.load(LoginPlatform.X); fail(); } catch (java.io.IOException expected) { assertFalse(expected.getMessage().contains("preserved")); }
        File saved = new File(app.getNoBackupFilesDir(), "account-sessions/x.session");
        byte[] bytes = Files.readAllBytes(saved.toPath()); bytes[bytes.length - 1] ^= 1; Files.write(saved.toPath(), bytes);
        try { store.load(LoginPlatform.X); fail(); } catch (java.io.IOException expected) {}
        assertFalse(new File(app.getFilesDir(), "cookies.txt").exists());
    }

    @Test public void clearAllRecoversFromUnreadableSessionsAndDamagedLegacyWithoutMigration() throws Exception {
        store.save(LoginPlatform.X, HEADER + row("x.com", "auth_token", "fixture-x"), "网页登录");
        store.importLegacy(HEADER + row(".youtube.com", "SID", "fixture-other"));
        writeText(new File(app.getFilesDir(), "cookies.txt").toPath(), "damaged legacy");
        writeText(new File(app.getFilesDir(), "cookies.txt.new").toPath(), "damaged staged legacy");
        assertTrue(store.hasAnySaved());
        byte[] wrongKey = new byte[32]; wrongKey[1] = 1;
        new AccountStore(app, new SecretKeySpec(wrongKey, "AES")).clearAll();
        assertFalse(store.hasAnySaved());
        assertFalse(new File(app.getFilesDir(), "cookies.txt").exists());
        assertFalse(new File(app.getFilesDir(), "cookies.txt.new").exists());
        File snapshot = new File(app.getCacheDir(), "cleared-session.txt");
        store.writeSnapshot(snapshot);
        assertEquals(HEADER, readText(snapshot.toPath()));
        snapshot.delete();
    }

    private static String readText(java.nio.file.Path path) throws java.io.IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private static void writeText(java.nio.file.Path path, String text) throws java.io.IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }
    private static String row(String domain, String name, String value) {
        return domain + "\t" + (domain.startsWith(".") ? "TRUE" : "FALSE") + "\t/\tTRUE\t0\t" + name + "\t" + value + "\n";
    }
    private static void delete(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
