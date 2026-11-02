package dev.streampocket;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic post and account fixtures only; real network evidence is recorded separately. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public final class HeyboxIntegrationTest {
    private static final String HEADER="# Netscape HTTP Cookie File\n\n";

    @Test public void shareAndWebUrlsKeepOnePostIdentityWithoutRequestingTheWebsite() {
        String share="https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?h_src=share&link_id=abc123&token=a%2Fb%3D";
        String web="https://www.xiaoheihe.cn/app/bbs/link/abc123";
        assertEquals("https://www.xiaoheihe.cn/app/bbs/link/abc123?h_src=share&token=a%2Fb%3D",Engine.normalizeUrl(share));
        assertEquals(web,Engine.normalizeUrl("https://xiaoheihe.cn/a/abc123"));
        LinkBatch.Result batch=LinkBatch.parse("游戏视频 "+share+" "+web+" xiaoheihe.cn/bbs/post_share?link_id=abc123");
        assertEquals(1,batch.urls.size());assertEquals(2,batch.duplicates);
        assertEquals("heybox",SourceScheduler.platform(share));assertEquals(VideoPlatform.HEYBOX,VideoPlatform.detect(web));
    }

    @Test public void unrelatedPagesAndAmbiguousPostIdsFailBeforeExtraction() {
        for(String path:new String[]{"/app/bbs/home","/game/share_game_detail?appid=123","/bbs/post_share",
                "/bbs/post_share?link_id=&link_id=123","/bbs/post_share?link_id=123&LINK_ID=123",
                "/bbs/post_share?%6cink_id=123","/app/bbs/link/123?link_id=456","/bbs/post_share?link_id=123%26other"}) {
            try {Engine.normalizeUrl("https://www.xiaoheihe.cn"+path);fail(path);}
            catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("单个视频帖"));}
        }
        for(String url:new String[]{"https://notxiaoheihe.cn/app/bbs/link/123","https://xiaoheihe.cn.evil.example/app/bbs/link/123","https://xiaoheihe.cn@evil.example/app/bbs/link/123"}) {
            assertNull(VideoPlatform.detect(url));assertFalse(LoginPlatform.HEYBOX.allowsNavigation(url));
        }
    }

    @Test public void targetMediaPreservesSourceQualityAndDurableQueueData() throws Exception {
        Engine.Media media=Engine.mediaFromMetadata(new JSONObject().put("id","123").put("title","合成游戏视频")
                .put("url","https://videoheybox.max-c.com/video/fixture.mp4?auth=keep%2F").put("height",720).put("width",1280)
                .put("duration",41).put("acodec","aac").put("vcodec","h264"),"https://www.xiaoheihe.cn/app/bbs/link/123").get(0);
        Engine.Media restored=Engine.Media.fromJson(media.toJson());
        assertEquals("小黑盒",restored.platform);assertEquals(media.url,restored.url);assertEquals(41,restored.duration);
        assertFalse(restored.qualities.isEmpty());assertTrue(restored.hasAudio);
    }

    @Test public void officialLoginUsesOnlyOwnedAuthenticationCookies() {
        assertTrue(LoginPlatform.HEYBOX.allowsNavigation(LoginPlatform.HEYBOX.loginUrl));
        for(String key:new String[]{"pkey","user_pkey"})assertTrue(LoginPlatform.HEYBOX.hasLogin(HEADER+row(".xiaoheihe.cn",key,"synthetic")));
        for(String value:new String[]{"null","None","undefined",""})assertFalse(LoginPlatform.HEYBOX.hasLogin(HEADER+row(".xiaoheihe.cn","pkey",value)));
        assertFalse(LoginPlatform.HEYBOX.hasLogin(HEADER+row(".xiaoheihe.cn","x_xhh_tokenid","guest")));
        assertFalse(LoginPlatform.HEYBOX.hasLogin(HEADER+row(".max-c.com","pkey","not-an-account-domain")));
        assertFalse(LoginPlatform.HEYBOX.allowsNavigation("https://login.xiaoheihe.cn.evil.example/"));
    }

    @Test public void pre038GenericImportsCannotBypassPauseOrClear() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("account-sessions-metadata",0).edit().clear().putBoolean("legacy.migrated",true)
                .putBoolean("channels037.migrated",true).commit();
        AccountStore accounts=new AccountStore(app,new SecretKeySpec(new byte[32],"AES"));
        Method write=AccountStore.class.getDeclaredMethod("writeEncrypted",String.class,String.class);write.setAccessible(true);
        write.invoke(accounts,"other",HEADER+row(".xiaoheihe.cn","pkey","old-heybox")+row(".example.org","session","keep-other"));
        accounts.prepare();assertTrue(accounts.hasSaved(LoginPlatform.HEYBOX));
        File snapshot=new File(app.getCacheDir(),"heybox-snapshot.txt");
        try {
            accounts.setEnabled(LoginPlatform.HEYBOX,false);accounts.writeSnapshot(snapshot);
            assertFalse(read(snapshot).contains("old-heybox"));assertTrue(read(snapshot).contains("keep-other"));
            accounts.setEnabled(LoginPlatform.HEYBOX,true);accounts.writeSnapshot(snapshot);assertTrue(read(snapshot).contains("old-heybox"));
            accounts.clear(LoginPlatform.HEYBOX);accounts.writeSnapshot(snapshot);assertFalse(read(snapshot).contains("old-heybox"));
        } finally {snapshot.delete();}
    }

    private static String read(File path) throws Exception {return new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8);}
    private static String row(String domain,String key,String value) {return domain+"\tTRUE\t/\tTRUE\t0\t"+key+"\t"+value+"\n";}
}
