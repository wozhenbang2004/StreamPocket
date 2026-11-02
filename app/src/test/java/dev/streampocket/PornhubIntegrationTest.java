package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Button;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Synthetic links, cookies, metadata and WebView callbacks only; no adult media or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PornhubIntegrationTest {
    private static final String HEADER="# Netscape HTTP Cookie File\n\n";
    private static final String VIDEO="https://www.pornhub.com/view_video.php?viewkey=phfixture123";

    @Test public void aliasesNormalizeForTheBundledExtractorWithoutLosingQueryBytes() {
        String[][] inputs={
                {VIDEO,VIDEO},
                {"https://fr.pornhub.net/video/show?utm_source=share&viewkey=phfixture123&token=a%2Fb%3D",
                        "https://fr.pornhub.net/view_video.php?viewkey=phfixture123&utm_source=share&token=a%2Fb%3D"},
                {"https://m.pornhub.org/embed/phfixture123?token=a%2Fb%3D",
                        "https://m.pornhub.org/view_video.php?viewkey=phfixture123&token=a%2Fb%3D"}};
        for(String[] input:inputs) {
            assertEquals(input[1],Engine.normalizeUrl(input[0]));
            assertEquals("pornhub",SourceScheduler.platform(input[0]));
        }
        LinkBatch.Result pasted=LinkBatch.parse("分享 www.pornhub.com/view_video.php?viewkey=phfixture123 "+
                "https://m.pornhub.com/embed/phfixture123 "+"pornhub.net/view_video.php?viewkey=phfixture123");
        assertEquals(2,pasted.urls.size());assertEquals(1,pasted.duplicates);
        for(String lookalike:new String[]{"https://notpornhub.com/view_video.php?viewkey=phfixture123",
                "https://pornhub.com.attacker.example/embed/phfixture123","https://pornhub.com@evil.example/embed/phfixture123"}) {
            assertNull(VideoPlatform.detect(lookalike));assertFalse(LoginPlatform.PORNHUB.allowsNavigation(lookalike));
        }
        assertNull(VideoPlatform.detect("https://www.pornhubpremium.com/view_video.php?viewkey=phfixture123"));
    }

    @Test public void nonVideoAndAmbiguousIdsFailBeforeAnyNetworkRequest() {
        for(String path:new String[]{"/","/video/search?search=fixture","/model/fixture","/playlist/123",
                "/view_video.php","/view_video.php?viewkey=","/view_video.php?viewkey=&viewkey=phfixture123",
                "/view_video.php?viewkey=phfixture123&viewkey=other","/view_video.php?%76iewkey=phfixture123",
                "/view_video.php?viewkey=phfixture123&VIEWKEY=other","/embed/phfixture123?%76iewkey=other",
                "/view_video.php?viewkey=phfixture123%26other"}) {
            try {Engine.normalizeUrl("https://www.pornhub.com"+path);fail(path);}
            catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("单个视频"));}
        }
    }

    @Test public void mediaKeepsSourceQualityAndQueueIdentity() throws Exception {
        Engine.Media media=Engine.mediaFromMetadata(new JSONObject().put("id","phfixture123").put("title","合成视频")
                .put("url","https://cdn.example/720p_1000k.mp4").put("height",720).put("acodec","aac").put("vcodec","h264"),VIDEO).get(0);
        Engine.Media restored=Engine.Media.fromJson(media.toJson());
        assertEquals("Pornhub",restored.platform);assertEquals(media.url,restored.url);
        assertTrue(restored.hasAudio);assertFalse(restored.downloadChoices().isEmpty());
    }

    @Test public void pageConfirmationNeedsOwnedLiveCookiesAndNeverTrustsGuestCookiesAlone() {
        LoginPlatform platform=LoginPlatform.PORNHUB;
        String guest=HEADER+row(".pornhub.com","age_verified","synthetic");
        assertFalse(platform.hasLogin(guest));assertFalse(platform.hasLogin(guest,false));
        assertTrue(platform.hasLogin(guest,true));
        assertFalse(platform.hasLogin(HEADER,true));
        assertFalse(platform.hasLogin(HEADER+row(".example.org","synthetic_session","fixture"),true));
        assertFalse(platform.hasLogin(HEADER+".pornhub.com\tTRUE\t/\tTRUE\t1\tsynthetic_session\tfixture\n",true));
        assertFalse(platform.allowsNavigation("https://www.pornhubpremium.com/login"));
        assertFalse(platform.allowsNavigation("http://www.pornhub.com/login"));
    }

    @Test public void officialLoginRejectsStalePageCallbacksAndResetsOnNavigation() throws Exception {
        try(ActivityController<LoginActivity> controller=Robolectric.buildActivity(LoginActivity.class,new Intent().putExtra("platform","pornhub"))) {
            LoginActivity activity=controller.create().start().resume().get();Shadows.shadowOf(Looper.getMainLooper()).idle();
            WebView web=field(activity,"web",WebView.class);Button save=field(activity,"save",Button.class);
            assertEquals(LoginPlatform.PORNHUB.loginUrl,web.getUrl());assertFalse(save.isEnabled());
            CookieManager.getInstance().setCookie(web.getUrl(),"age_verified=synthetic; Path=/; Secure");
            web.getWebViewClient().onPageFinished(web,web.getUrl());
            assertTrue(Shadows.shadowOf(web).getLastEvaluatedJavascript().contains("#profileMenuDropdown"));
            Shadows.shadowOf(web).getLastEvaluatedJavascriptCallback().onReceiveValue("false");assertFalse(save.isEnabled());
            web.getWebViewClient().onPageFinished(web,web.getUrl());
            ValueCallback<String> stale=Shadows.shadowOf(web).getLastEvaluatedJavascriptCallback();
            web.getWebViewClient().onPageStarted(web,web.getUrl(),null);
            stale.onReceiveValue("true");assertFalse(save.isEnabled());
            web.getWebViewClient().onPageFinished(web,web.getUrl());
            Shadows.shadowOf(web).getLastEvaluatedJavascriptCallback().onReceiveValue("true");assertTrue(save.isEnabled());
            web.getWebViewClient().onPageStarted(web,web.getUrl(),null);assertFalse(save.isEnabled());
            web.loadUrl("https://example.org/");web.getWebViewClient().onPageFinished(web,web.getUrl());assertFalse(save.isEnabled());
            assertFalse(new AccountStore(activity).hasSaved(LoginPlatform.PORNHUB));
        } finally {CookieManager.getInstance().removeAllCookies(null);}
    }

    @Test public void oldGenericImportMigratesEvenWhen036MigrationAlreadyRan() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("account-sessions-metadata",0).edit().clear().putBoolean("legacy.migrated",true)
                .putBoolean("channels036.migrated",true).commit();
        AccountStore accounts=new AccountStore(app,new SecretKeySpec(new byte[32],"AES"));
        Method write=AccountStore.class.getDeclaredMethod("writeEncrypted",String.class,String.class);write.setAccessible(true);
        write.invoke(accounts,"other",HEADER+row(".pornhub.com","synthetic_session","old-ph")+row(".example.org","session","retained"));
        accounts.prepare();assertTrue(accounts.hasSaved(LoginPlatform.PORNHUB));
        File snapshot=new File(app.getCacheDir(),"pornhub-snapshot.txt");
        try {
            accounts.setEnabled(LoginPlatform.PORNHUB,false);accounts.writeSnapshot(snapshot);
            assertFalse(new String(Files.readAllBytes(snapshot.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("old-ph"));assertTrue(new String(Files.readAllBytes(snapshot.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("retained"));
            accounts.setEnabled(LoginPlatform.PORNHUB,true);accounts.writeSnapshot(snapshot);assertTrue(new String(Files.readAllBytes(snapshot.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("old-ph"));
            accounts.clear(LoginPlatform.PORNHUB);accounts.writeSnapshot(snapshot);assertFalse(new String(Files.readAllBytes(snapshot.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("old-ph"));
        } finally {snapshot.delete();}
    }

    private static String row(String host,String name,String value) {return host+"\tTRUE\t/\tTRUE\t0\t"+name+"\t"+value+"\n";}
    private static <T>T field(Object owner,String name,Class<T> type) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(owner));
    }
}
