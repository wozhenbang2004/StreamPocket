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

    @Test public void accountChangesInvalidateResolutionScopeAndResetRequestEvidence() throws Exception {
        String url="https://www.instagram.com/p/fixture/";
        String initial=store.cacheScope(url);
        store.save(LoginPlatform.INSTAGRAM,HEADER+row(".instagram.com","sessionid","synthetic"),"文件导入");
        String saved=store.cacheScope(url);assertNotEquals(initial,saved);assertEquals("未验证",store.requestStatus(LoginPlatform.INSTAGRAM));
        store.noteRequest(url,saved,null);assertEquals("最近请求成功",store.requestStatus(LoginPlatform.INSTAGRAM));
        store.noteRequest(url,saved,new java.io.IOException("Login required"));assertEquals("需要重新登录",store.requestStatus(LoginPlatform.INSTAGRAM));
        store.setEnabled(LoginPlatform.INSTAGRAM,false);assertNotEquals(saved,store.cacheScope(url));assertEquals("未验证",store.requestStatus(LoginPlatform.INSTAGRAM));
        store.noteRequest(url,saved,null);assertEquals("未验证",store.requestStatus(LoginPlatform.INSTAGRAM));
        store.clear(LoginPlatform.INSTAGRAM);assertNotEquals(initial,store.cacheScope(url));
    }

    @Test public void savedTwitterCredentialsReachRequestSnapshotWithoutRelogin() throws Exception {
        store.save(LoginPlatform.X,HEADER+row(".x.com","auth_token","synthetic-existing-token")+row(".x.com","ct0","synthetic-csrf"),"网页登录");
        assertTrue(store.hasTwitterLogin());File snapshot=new File(app.getCacheDir(),"twitter-existing-request.txt");
        try{String scope=store.writeScopedSnapshot(snapshot,"https://x.com/fixture/status/1");assertEquals(store.cacheScope("https://x.com/fixture/status/1"),scope);
            String body=new String(Files.readAllBytes(snapshot.toPath()),StandardCharsets.UTF_8);assertTrue(body.contains("synthetic-existing-token"));assertTrue(body.contains("synthetic-csrf"));
            store.noteRequest("https://x.com/fixture/status/1",scope,new java.io.IOException("ERROR: No video formats found"));assertEquals("未验证",store.requestStatus(LoginPlatform.X));
            assertTrue(store.hasTwitterLogin());
        }finally{snapshot.delete();}
    }

    @Test public void channelCredentialsRemainIsolatedAndCanBePausedOrCleared() throws Exception {
        LoginPlatform[] platforms={LoginPlatform.XIAOHONGSHU,LoginPlatform.KUAISHOU,LoginPlatform.TIKTOK,LoginPlatform.INSTAGRAM,LoginPlatform.FACEBOOK,LoginPlatform.PINTEREST};
        String[] hosts={".xiaohongshu.com",".kuaishou.com",".tiktok.com",".instagram.com",".facebook.com",".pinterest.com"};
        String[] names={"web_session","kuaishou.server.web_st","sessionid","sessionid","c_user","_pinterest_sess"};
        StringBuilder cookies=new StringBuilder(HEADER);
        for(int i=0;i<platforms.length;i++) {
            String value=row(hosts[i],names[i],"fixture-"+i);cookies.append(value);
            assertTrue(platforms[i].hasLogin(HEADER+value));
            assertFalse(platforms[i].hasLogin(HEADER+row(hosts[i]+".attacker.example",names[i],"fixture")));
        }
        store.importLegacy(cookies.toString());
        File snapshot=new File(app.getCacheDir(),"new-channel-snapshot.txt");
        try {
            store.setEnabled(LoginPlatform.INSTAGRAM,false);store.clear(LoginPlatform.XIAOHONGSHU);store.writeSnapshot(snapshot);
            String output=new String(java.nio.file.Files.readAllBytes(snapshot.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(output.contains("fixture-0"));assertFalse(output.contains("fixture-3"));
            assertTrue(output.contains("fixture-1"));assertTrue(output.contains("fixture-2"));assertTrue(output.contains("fixture-4"));assertTrue(output.contains("fixture-5"));
        } finally {snapshot.delete();}
    }

    @Test public void oldGenericCookiesMigrateBeforeAnyNewPlatformCanBeDisabled() throws Exception {
        app.getSharedPreferences("account-sessions-metadata",0).edit().putBoolean("legacy.migrated",true).commit();
        java.lang.reflect.Method write=AccountStore.class.getDeclaredMethod("writeEncrypted",String.class,String.class);write.setAccessible(true);
        write.invoke(store,"other",HEADER+row(".xiaohongshu.com","web_session","old-xhs")+row(".instagram.com","sessionid","old-ig")
                +row(".youtube.com","SAPISID","old-yt")+row(".example.org","session","retained"));
        store.prepare();assertTrue(store.hasSaved(LoginPlatform.XIAOHONGSHU));assertTrue(store.hasSaved(LoginPlatform.INSTAGRAM));assertTrue(store.hasSaved(LoginPlatform.YOUTUBE));
        store.setEnabled(LoginPlatform.INSTAGRAM,false);store.clear(LoginPlatform.XIAOHONGSHU);
        File snapshot=new File(app.getCacheDir(),"migrated-channel-snapshot.txt");
        try {store.writeSnapshot(snapshot);String output=new String(java.nio.file.Files.readAllBytes(snapshot.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(output.contains("old-xhs"));assertFalse(output.contains("old-ig"));assertTrue(output.contains("old-yt"));assertTrue(output.contains("retained"));
        } finally {snapshot.delete();}
    }

    @Test public void platformDomainsAndNavigationRejectLookalikesAndUnsafeSchemes() {
        assertTrue(LoginPlatform.BILIBILI.ownsDomain("#HttpOnly_.bilibili.com"));
        assertTrue(LoginPlatform.X.allowsNavigation("https://x.com/i/flow/login"));
        for (String value : new String[]{"https://x.com.attacker.example/", "https://evilx.com/", "http://x.com/", "https://user@x.com/", "https://x.com:1234/", "intent://x.com/", "file:///data/test"})
            assertFalse(value, LoginPlatform.X.allowsNavigation(value));
        assertFalse(LoginPlatform.BILIBILI.ownsDomain("bilibili.com.example.org"));
        assertFalse(LoginPlatform.BILIBILI.ownsDomain("..bilibili.com"));
    }

    @Test public void youtubeGoogleNavigationNeverGrantsCookieOwnership() {
        assertTrue(LoginPlatform.YOUTUBE.ownsDomain("#HttpOnly_.youtube.com"));
        assertTrue(LoginPlatform.YOUTUBE.ownsDomain("music.youtube.com"));
        assertTrue(LoginPlatform.YOUTUBE.allowsNavigation("https://accounts.google.com/ServiceLogin?service=youtube"));
        assertTrue(LoginPlatform.YOUTUBE.allowsNavigation("https://www.youtube.com/"));
        for (String domain : new String[]{"accounts.google.com", ".google.com", "youtube.com.attacker.example", "notyoutube.com"})
            assertFalse(domain, LoginPlatform.YOUTUBE.ownsDomain(domain));
        for (String url : new String[]{"http://accounts.google.com/", "https://accounts.google.com.attacker.example/",
                "https://other.accounts.google.com/", "https://user@accounts.google.com/", "https://accounts.google.com:1234/", "intent://accounts.google.com/"})
            assertFalse(url, LoginPlatform.YOUTUBE.allowsNavigation(url));
        assertFalse(LoginPlatform.X.allowsNavigation("https://accounts.google.com/"));
        for (String url : LoginPlatform.YOUTUBE.cookieUrls()) assertFalse(url.contains("google.com"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("https://accounts.google.com/", "SAPISID=google-only");
        try { AccountStore.fromWebCookies(LoginPlatform.YOUTUBE, headers); fail(); }
        catch (IllegalArgumentException expected) {}
        Map<String, List<String>> full = new LinkedHashMap<>();
        full.put("https://www.youtube.com/", Arrays.asList("SAPISID=google-only; Domain=.google.com; Path=/; Secure"));
        try { AccountStore.fromWebCookieInfo(LoginPlatform.YOUTUBE, full); fail(); }
        catch (IllegalArgumentException expected) {}
    }

    @Test public void youtubeDetectionNeedsANonExpiredOwnedAuthenticationCookie() {
        for (String name : new String[]{"SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID"}) {
            assertTrue(name, LoginPlatform.YOUTUBE.hasLogin(HEADER + row(".youtube.com", name, "fixture")));
            assertFalse(LoginPlatform.YOUTUBE.hasLogin(HEADER + row(".google.com", name, "fixture")));
            assertFalse(LoginPlatform.YOUTUBE.hasLogin(HEADER + "youtube.com\tFALSE\t/\tTRUE\t1\t" + name + "\tfixture\n"));
            assertFalse(LoginPlatform.YOUTUBE.hasLogin(HEADER + row(".youtube.com", name, "")));
        }
        assertFalse(LoginPlatform.YOUTUBE.hasLogin(HEADER + row(".youtube.com", "PREF", "guest")));
        assertFalse(LoginPlatform.YOUTUBE.hasLogin(HEADER + row("youtube.com.attacker.example", "SAPISID", "fixture")));
        long future = System.currentTimeMillis() / 1000 + 3600;
        assertTrue(LoginPlatform.YOUTUBE.hasLogin(HEADER + "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t" + future + "\tSAPISID\tfixture\n"));
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

    @Test public void encryptedOldOtherMovesYouTubeAndKeepsOtherSites() throws Exception {
        seedOldOther(row(".youtube.com", "SAPISID", "old-youtube") + row("example.org", "session", "other-site"));
        store.prepare();
        assertTrue(store.hasSaved(LoginPlatform.YOUTUBE));
        assertEquals(HEADER + row(".youtube.com", "SAPISID", "old-youtube"), store.load(LoginPlatform.YOUTUBE));
        assertEquals("文件导入", store.source(LoginPlatform.YOUTUBE));
        File snapshot = new File(app.getCacheDir(), "upgraded-youtube.txt");
        store.writeSnapshot(snapshot);
        assertTrue(readText(snapshot.toPath()).contains("old-youtube"));
        assertTrue(readText(snapshot.toPath()).contains("other-site"));
        byte[] encrypted = Files.readAllBytes(new File(app.getNoBackupFilesDir(), "account-sessions/youtube.session").toPath());
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("old-youtube"));
        store.setEnabled(LoginPlatform.YOUTUBE, false);
        store.writeSnapshot(snapshot);
        assertFalse(readText(snapshot.toPath()).contains("old-youtube"));
        assertTrue(readText(snapshot.toPath()).contains("other-site"));
        store.clear(LoginPlatform.YOUTUBE); store.prepare(); store.writeSnapshot(snapshot);
        assertFalse(store.hasSaved(LoginPlatform.YOUTUBE));
        assertFalse(readText(snapshot.toPath()).contains("old-youtube"));
        snapshot.delete();
    }

    @Test public void oldOtherMigrationNeverOverwritesANewerDisabledYouTubeSession() throws Exception {
        store.save(LoginPlatform.YOUTUBE, HEADER + row(".youtube.com", "SAPISID", "new-youtube"), "网页登录");
        store.setEnabled(LoginPlatform.YOUTUBE, false);
        app.getSharedPreferences("account-sessions-metadata", 0).edit().putLong("youtube.savedAt", 123456L).commit();
        seedOldOther(row(".youtube.com", "SAPISID", "old-youtube") + row("example.org", "session", "other-site"));
        store.prepare(); store.prepare();
        assertTrue(store.load(LoginPlatform.YOUTUBE).contains("new-youtube"));
        assertFalse(store.load(LoginPlatform.YOUTUBE).contains("old-youtube"));
        assertFalse(store.isEnabled(LoginPlatform.YOUTUBE));
        assertEquals(123456L, store.savedAt(LoginPlatform.YOUTUBE));
        assertEquals("网页登录", store.source(LoginPlatform.YOUTUBE));
        File snapshot = new File(app.getCacheDir(), "new-youtube-wins.txt");
        store.writeSnapshot(snapshot);
        assertFalse(readText(snapshot.toPath()).contains("youtube"));
        assertTrue(readText(snapshot.toPath()).contains("other-site"));
        store.setEnabled(LoginPlatform.YOUTUBE, true); store.writeSnapshot(snapshot);
        assertTrue(readText(snapshot.toPath()).contains("new-youtube"));
        assertFalse(readText(snapshot.toPath()).contains("old-youtube"));
        snapshot.delete();
    }

    @Test public void oldOtherBackupCannotBypassPauseOrResurrectAClearedYouTubeSession() throws Exception {
        store.save(LoginPlatform.YOUTUBE, HEADER + row(".youtube.com", "SAPISID", "new-youtube"), "文件导入");
        store.setEnabled(LoginPlatform.YOUTUBE, false);
        seedOldOther(row(".youtube.com", "SAPISID", "stale-youtube") + row("example.org", "session", "other-site"));
        // A stale generic jar cannot undo a completed migration or a later user action.
        app.getSharedPreferences("account-sessions-metadata", 0).edit().putBoolean("youtube.otherMigrated", true).commit();
        File snapshot = new File(app.getCacheDir(), "stale-youtube.txt");
        store.writeSnapshot(snapshot);
        assertFalse(readText(snapshot.toPath()).contains("youtube"));
        assertTrue(readText(snapshot.toPath()).contains("other-site"));
        store.clear(LoginPlatform.YOUTUBE); store.prepare(); store.writeSnapshot(snapshot);
        assertFalse(store.hasSaved(LoginPlatform.YOUTUBE));
        assertFalse(readText(snapshot.toPath()).contains("youtube"));
        assertTrue(readText(snapshot.toPath()).contains("other-site"));
        snapshot.delete();
    }

    @Test public void scopedImportKeepsOnlyTheTargetAndLeavesOtherAccountsUnchanged() throws Exception {
        store.save(LoginPlatform.X, HEADER + row("x.com", "auth_token", "preserved-x"), "网页登录");
        store.importLegacy(HEADER + row("example.org", "session", "preserved-other"));
        File other = new File(app.getNoBackupFilesDir(), "account-sessions/other.session");
        byte[] originalOther = Files.readAllBytes(other.toPath());
        store.importForPlatform(LoginPlatform.YOUTUBE, HEADER + row(".youtube.com", "SAPISID", "selected-youtube")
                + row("x.com", "auth_token", "ignored-x") + row(".google.com", "SAPISID", "ignored-google")
                + row("example.org", "session", "ignored-other"));
        assertTrue(LoginPlatform.YOUTUBE.hasLogin(store.load(LoginPlatform.YOUTUBE)));
        assertTrue(store.load(LoginPlatform.X).contains("preserved-x"));
        assertArrayEquals(originalOther, Files.readAllBytes(other.toPath()));
        File snapshot = new File(app.getCacheDir(), "scoped-youtube.txt");
        store.writeSnapshot(snapshot);
        String actual = readText(snapshot.toPath());
        assertTrue(actual.contains("selected-youtube")); assertTrue(actual.contains("preserved-other"));
        assertFalse(actual.contains("ignored-google")); assertFalse(actual.contains("ignored-x")); assertFalse(actual.contains("ignored-other"));
        assertEquals("文件导入", store.source(LoginPlatform.YOUTUBE));
        snapshot.delete();
    }

    @Test public void missingTargetOrMalformedScopedImportPreservesSavedData() throws Exception {
        String original = HEADER + row(".youtube.com", "SAPISID", "preserved-youtube");
        store.save(LoginPlatform.YOUTUBE, original, "网页登录"); store.setEnabled(LoginPlatform.YOUTUBE, false);
        for (String invalid : new String[]{HEADER + row(".google.com", "SAPISID", "wrong-domain"),
                HEADER + row("youtube.com.attacker.example", "SAPISID", "wrong-domain"), HEADER + "broken\trow",
                HEADER + row(".youtube.com", "SAPISID", "new") + "broken\trow\n"}) {
            try { store.importForPlatform(LoginPlatform.YOUTUBE, invalid); fail(); }
            catch (java.io.IOException expected) {}
            assertEquals(original, store.load(LoginPlatform.YOUTUBE));
            assertFalse(store.isEnabled(LoginPlatform.YOUTUBE));
            assertEquals("网页登录", store.source(LoginPlatform.YOUTUBE));
        }
    }

    @Test public void wrongKeyCannotPartiallyMoveAnOldGenericJar() throws Exception {
        seedOldOther(row(".youtube.com", "SAPISID", "old-youtube") + row("example.org", "session", "other-site"));
        File other = new File(app.getNoBackupFilesDir(), "account-sessions/other.session");
        byte[] before = Files.readAllBytes(other.toPath());
        byte[] different = new byte[32]; different[0] = 1;
        try { new AccountStore(app, new SecretKeySpec(different, "AES")).prepare(); fail(); }
        catch (java.io.IOException expected) {}
        assertArrayEquals(before, Files.readAllBytes(other.toPath()));
        assertFalse(store.hasSaved(LoginPlatform.YOUTUBE));
        store.prepare();
        assertTrue(store.load(LoginPlatform.YOUTUBE).contains("old-youtube"));
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

    @Test public void clearAllDoesNotMigrateOrReviveAnUnreadableOldGenericJar() throws Exception {
        seedOldOther(row(".youtube.com", "SAPISID", "old-youtube") + row("example.org", "session", "other-site"));
        byte[] different = new byte[32]; different[0] = 1;
        new AccountStore(app, new SecretKeySpec(different, "AES")).clearAll();
        store.prepare(); assertFalse(store.hasAnySaved()); assertFalse(store.hasSaved(LoginPlatform.YOUTUBE));
        File snapshot = new File(app.getCacheDir(), "clear-old-youtube.txt");
        store.writeSnapshot(snapshot); assertEquals(HEADER, readText(snapshot.toPath())); snapshot.delete();
    }

    private void seedOldOther(String rows) throws Exception {
        java.lang.reflect.Method write = AccountStore.class.getDeclaredMethod("writeEncrypted", String.class, String.class);
        write.setAccessible(true); write.invoke(store, "other", HEADER + rows);
        app.getSharedPreferences("account-sessions-metadata", 0).edit().putBoolean("legacy.migrated", true)
                .remove("youtube.otherMigrated").commit();
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
