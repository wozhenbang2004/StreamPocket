package dev.streampocket;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real AES/GCM and Android AtomicFile with synthetic credentials; no external requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public final class CommonSourceAccountMigrationTest {
    private static final String HEADER = "# Netscape HTTP Cookie File\n\n";
    private static final String COMMON_MIGRATED = "channels.commonVideoSources.v1";
    private static final LoginPlatform[] PLATFORMS = {LoginPlatform.WEIBO, LoginPlatform.XIGUA, LoginPlatform.ACFUN,
            LoginPlatform.REDDIT, LoginPlatform.VIMEO, LoginPlatform.TWITCH};
    private static final String[] HOSTS = {".weibo.com", ".ixigua.com", ".acfun.cn", ".reddit.com", ".vimeo.com", ".twitch.tv"};
    private static final String[] NAMES = {"SUB", "visitor", "session", "reddit_session", "vimeo", "auth-token"};
    private Context app;
    private SharedPreferences metadata;
    private AccountStore store;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        metadata = app.getSharedPreferences("account-sessions-metadata", 0);
        metadata.edit().clear().commit();
        delete(new File(app.getNoBackupFilesDir(), "account-sessions"));
        new File(app.getFilesDir(), "cookies.txt").delete();
        new File(app.getFilesDir(), "cookies.txt.new").delete();
        store = new AccountStore(app, new SecretKeySpec(new byte[32], "AES"));
    }

    @Test public void completedOldMigrationStillReclassifiesAllSixSourcesAndKeepsUnknownCookies() throws Exception {
        seedOldOther(allRows("old-") + row("example.org", "session", "unknown-retained"));
        store.prepare();
        for (LoginPlatform platform : PLATFORMS) {
            assertTrue(platform.id, store.hasSaved(platform));
            assertTrue(store.isEnabled(platform));
            assertTrue(store.load(platform).contains("old-" + platform.id));
            assertFalse(new String(Files.readAllBytes(file(platform.id).toPath()), StandardCharsets.ISO_8859_1)
                    .contains("old-" + platform.id));
        }
        assertTrue(metadata.getBoolean(COMMON_MIGRATED, false));
        String snapshot = snapshot();
        for (LoginPlatform platform : PLATFORMS) assertTrue(snapshot.contains("old-" + platform.id));
        assertTrue(snapshot.contains("unknown-retained"));
        store.prepare();
        assertEquals(snapshot, snapshot());
    }

    @Test public void aNewerDisabledSessionKeepsItsCiphertextAndMetadataDuringMigration() throws Exception {
        Map<String, byte[]> before = new LinkedHashMap<>();
        Map<String, String> revisions = new LinkedHashMap<>();
        for (int i = 0; i < PLATFORMS.length; i++) {
            LoginPlatform platform = PLATFORMS[i];
            store.save(platform, HEADER + row(HOSTS[i], NAMES[i], "new-" + platform.id), "网页登录");
            store.setEnabled(platform, false);
            metadata.edit().putLong(platform.id + ".savedAt", 123456L).commit();
            before.put(platform.id, Files.readAllBytes(file(platform.id).toPath()));
            revisions.put(platform.id, metadata.getString(platform.id + ".revision", ""));
        }
        seedOldOther(allRows("stale-"));
        store.prepare();
        for (LoginPlatform platform : PLATFORMS) {
            assertArrayEquals(before.get(platform.id), Files.readAllBytes(file(platform.id).toPath()));
            assertEquals(revisions.get(platform.id), metadata.getString(platform.id + ".revision", ""));
            assertEquals(123456L, store.savedAt(platform));
            assertEquals("网页登录", store.source(platform));
            assertFalse(store.isEnabled(platform));
        }
        assertEquals(HEADER, snapshot());
        store.setEnabled(LoginPlatform.REDDIT, true);
        assertTrue(snapshot().contains("new-reddit"));
        assertFalse(snapshot().contains("stale-"));
    }

    @Test public void clearTombstonesPreventInterruptedMigrationFromRevivingAnySource() throws Exception {
        store.importLegacy(HEADER + allRows("current-"));
        Map<String, String> revisions = new LinkedHashMap<>();
        for (LoginPlatform platform : PLATFORMS) {
            store.clear(platform);
            revisions.put(platform.id, metadata.getString(platform.id + ".revision", ""));
        }
        seedOldOther(allRows("stale-"));
        store.prepare();
        for (LoginPlatform platform : PLATFORMS) {
            assertFalse(platform.id, store.hasSaved(platform));
            assertEquals(revisions.get(platform.id), metadata.getString(platform.id + ".revision", ""));
        }
        assertEquals(HEADER, snapshot());
    }

    @Test public void missingOwnerFilesStillRespectAnExplicitPauseOrSavedRevision() throws Exception {
        store.save(LoginPlatform.VIMEO, HEADER + row(".vimeo.com", "vimeo", "new-vimeo"), "网页登录");
        assertTrue(file("vimeo").delete());
        metadata.edit().putBoolean("reddit.enabled", false).commit();
        seedOldOther(row(".vimeo.com", "vimeo", "old-vimeo") + row(".reddit.com", "reddit_session", "old-reddit"));
        store.prepare();
        assertFalse(store.hasSaved(LoginPlatform.VIMEO));
        assertFalse(store.hasSaved(LoginPlatform.REDDIT));
        assertEquals(HEADER, snapshot());
    }

    @Test public void staleAtomicBackupCannotBypassPauseOrClearAfterMigration() throws Exception {
        seedOldOther(allRows("stale-") + row("example.org", "session", "unknown-retained"));
        byte[] stale = Files.readAllBytes(file("other").toPath());
        store.prepare();
        store.setEnabled(LoginPlatform.REDDIT, false);
        store.clear(LoginPlatform.WEIBO);
        Files.write(new File(file("other").getPath() + ".bak").toPath(), stale);
        String snapshot = snapshot();
        assertFalse(store.hasSaved(LoginPlatform.WEIBO));
        assertFalse(store.isEnabled(LoginPlatform.REDDIT));
        assertFalse(snapshot.contains("stale-weibo"));
        assertFalse(snapshot.contains("stale-reddit"));
        assertTrue(snapshot.contains("stale-vimeo"));
        assertTrue(snapshot.contains("unknown-retained"));
    }

    @Test public void clearAllSealsMigrationBeforeAStaleGenericBackupCanReturn() throws Exception {
        seedOldOther(allRows("stale-"));
        byte[] stale = Files.readAllBytes(file("other").toPath());
        store.clearAll();
        assertTrue(metadata.getBoolean(COMMON_MIGRATED, false));
        assertTrue(metadata.getBoolean("channels038.migrated", false));
        Files.write(new File(file("other").getPath() + ".bak").toPath(), stale);
        store.prepare();
        for (LoginPlatform platform : PLATFORMS) assertFalse(platform.id, store.hasSaved(platform));
        assertEquals(HEADER, snapshot());
    }

    @Test public void anInterruptedPlaintextMigrationAlsoHonorsAClearTombstone() throws Exception {
        store.save(LoginPlatform.WEIBO, HEADER + row(".weibo.com", "SUB", "current-weibo"), "文件导入");
        store.clear(LoginPlatform.WEIBO);
        Files.write(new File(app.getFilesDir(), "cookies.txt").toPath(),
                (HEADER + row(".weibo.com", "SUB", "stale-weibo") + row(".acfun.cn", "session", "new-acfun")).getBytes(StandardCharsets.UTF_8));
        metadata.edit().remove("legacy.migrated").commit();
        store.prepare();
        assertFalse(store.hasSaved(LoginPlatform.WEIBO));
        assertTrue(store.load(LoginPlatform.ACFUN).contains("new-acfun"));
        assertFalse(snapshot().contains("stale-weibo"));
        assertFalse(new File(app.getFilesDir(), "cookies.txt").exists());
    }

    @Test public void authenticationOrOwnerValidationFailsBeforeAnyPartialMove() throws Exception {
        seedOldOther(allRows("old-"));
        byte[] before = Files.readAllBytes(file("other").toPath());
        byte[] wrong = new byte[32]; wrong[0] = 1;
        try { new AccountStore(app, new SecretKeySpec(wrong, "AES")).prepare(); fail(); }
        catch (IOException expected) {}
        assertArrayEquals(before, Files.readAllBytes(file("other").toPath()));
        for (LoginPlatform platform : PLATFORMS) assertFalse(store.hasSaved(platform));
        assertFalse(metadata.getBoolean(COMMON_MIGRATED, false));
        // X was already a dedicated owner before these migrations, so an other jar must not impersonate it.
        seedOldOther(allRows("old-") + row(".x.com", "auth_token", "invalid-owner"));
        before = Files.readAllBytes(file("other").toPath());
        try { store.prepare(); fail(); } catch (IOException expected) {}
        assertArrayEquals(before, Files.readAllBytes(file("other").toPath()));
        for (LoginPlatform platform : PLATFORMS) assertFalse(store.hasSaved(platform));
    }

    @Test public void importsPauseAndClearRemainIndependentAcrossNewAndExistingOwners() throws Exception {
        store.importLegacy(HEADER + allRows("owned-") + row(".douyin.com", "sessionid", "existing-douyin"));
        String vimeoScope = store.cacheScope("https://vimeo.com/123");
        String redditScope = store.cacheScope("https://reddit.com/comments/abc123");
        store.setEnabled(LoginPlatform.REDDIT, false);
        store.clear(LoginPlatform.WEIBO);
        assertNotEquals(redditScope, store.cacheScope("https://reddit.com/comments/abc123"));
        assertEquals(vimeoScope, store.cacheScope("https://vimeo.com/123"));
        String snapshot = snapshot();
        assertFalse(snapshot.contains("owned-reddit"));
        assertFalse(snapshot.contains("owned-weibo"));
        for (String id : new String[]{"xigua", "acfun", "vimeo", "twitch"}) assertTrue(snapshot.contains("owned-" + id));
        assertTrue(snapshot.contains("existing-douyin"));
        assertFalse(LoginPlatform.XIGUA.hasLogin(store.load(LoginPlatform.XIGUA)));
        store.importForPlatform(LoginPlatform.VIMEO, HEADER + row(".vimeo.com", "vimeo", "replacement-vimeo")
                + row(".twitch.tv", "auth-token", "ignored-twitch"));
        assertTrue(store.load(LoginPlatform.VIMEO).contains("replacement-vimeo"));
        assertTrue(store.load(LoginPlatform.TWITCH).contains("owned-twitch"));
        assertFalse(snapshot().contains("ignored-twitch"));
    }

    @Test public void ownerJarValidationStaysStrictAndWebCaptureDoesNotWidenHostScope() throws Exception {
        store.prepare();
        writeEncrypted("vimeo", HEADER + row(".reddit.com", "reddit_session", "wrong-owner"));
        try { store.load(LoginPlatform.VIMEO); fail(); } catch (IOException expected) {}
        Map<String, String> sampled = new LinkedHashMap<>();
        sampled.put("https://www.twitch.tv/", "auth-token=synthetic");
        String webOnly = AccountStore.fromWebCookies(LoginPlatform.TWITCH, sampled);
        assertFalse(LoginPlatform.TWITCH.hasLogin(webOnly));
        sampled.put("https://gql.twitch.tv/", "auth-token=synthetic");
        assertTrue(LoginPlatform.TWITCH.hasLogin(AccountStore.fromWebCookies(LoginPlatform.TWITCH, sampled)));
        sampled.clear(); sampled.put("https://t.cn/", "SUB=synthetic");
        try { AccountStore.fromWebCookies(LoginPlatform.WEIBO, sampled); fail(); }
        catch (IllegalArgumentException expected) {}
    }

    private void seedOldOther(String rows) throws Exception {
        writeEncrypted("other", HEADER + rows);
        metadata.edit().putBoolean("legacy.migrated", true).putBoolean("channels038.migrated", true)
                .putBoolean("youtube.otherMigrated", true).remove(COMMON_MIGRATED).commit();
    }

    private void writeEncrypted(String id, String value) throws Exception {
        Method write = AccountStore.class.getDeclaredMethod("writeEncrypted", String.class, String.class);
        write.setAccessible(true); write.invoke(store, id, value);
    }

    private String snapshot() throws Exception {
        File snapshot = new File(app.getCacheDir(), "common-sources-snapshot.txt");
        try { store.writeSnapshot(snapshot); return new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8); }
        finally { snapshot.delete(); }
    }

    private File file(String id) { return new File(app.getNoBackupFilesDir(), "account-sessions/" + id + ".session"); }
    private static String allRows(String prefix) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < PLATFORMS.length; i++) rows.append(row(HOSTS[i], NAMES[i], prefix + PLATFORMS[i].id));
        return rows.toString();
    }
    private static String row(String domain, String name, String value) {
        return domain + "\t" + (domain.startsWith(".") ? "TRUE" : "FALSE") + "\t/\tTRUE\t0\t" + name + "\t" + value + "\n";
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
