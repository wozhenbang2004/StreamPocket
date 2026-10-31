package dev.streampocket;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Browser configuration / shell checks only, without opening an account or real website. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LoginActivityTest {
    @Test public void invalidPlatformNeverCreatesBrowser() throws Exception {
        try (ActivityController<LoginActivity> controller = Robolectric.buildActivity(LoginActivity.class,
                new Intent().putExtra("platform", "attacker.example"))) {
            LoginActivity activity = controller.create().get();
            assertTrue(activity.isFinishing());
            assertNull(field(activity, "web", WebView.class));
        }
    }

    @Test public void officialLoginUsesRestrictedPrivateBrowserAndExplicitSave() throws Exception {
        try (ActivityController<LoginActivity> controller = Robolectric.buildActivity(LoginActivity.class,
                new Intent().putExtra("platform", "bilibili"))) {
            LoginActivity activity = controller.create().start().resume().get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            WebView web = field(activity, "web", WebView.class);
            assertNotNull(web);
            assertTrue(web.getSettings().getJavaScriptEnabled());
            assertTrue(web.getSettings().getDomStorageEnabled());
            assertFalse(web.getSettings().getAllowFileAccess());
            assertFalse(web.getSettings().getAllowContentAccess());
            assertFalse(web.getSettings().getJavaScriptCanOpenWindowsAutomatically());
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.getSettings().getMixedContentMode());
            assertEquals("The official login page must let the user capture a QR code",0,
                    activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
            assertFalse("Anonymous website cookies must not enable saving an account",
                    field(activity, "save", Button.class).isEnabled());
            assertEquals(LoginPlatform.BILIBILI.loginUrl, web.getUrl());
            assertFalse("No credentials were saved by visiting a login entry",
                    new AccountStore(activity).hasSaved(LoginPlatform.BILIBILI));
            // Only the native shell is rendered. ShadowWebView never loads the website.
            android.view.View decor = activity.getWindow().getDecorView();
            int width = Math.round(412 * activity.getResources().getDisplayMetrics().density);
            int height = Math.round(915 * activity.getResources().getDisplayMetrics().density);
            decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bitmap));
            File output = new File("build/ui-render");
            if (!output.isDirectory() && !output.mkdirs()) throw new java.io.IOException("Cannot create UI output");
            try (FileOutputStream stream = new FileOutputStream(new File(output, "013-login-shell-no-website.png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally { bitmap.recycle(); }
        }
    }

    @Test public void douyinStartsAtObservedAccountEntryInDesktopModeWithoutClaimingLogin() throws Exception {
        try (ActivityController<LoginActivity> controller = Robolectric.buildActivity(LoginActivity.class,
                new Intent().putExtra("platform", "douyin"))) {
            LoginActivity activity=controller.create().start().resume().get();Shadows.shadowOf(Looper.getMainLooper()).idle();
            WebView web=field(activity,"web",WebView.class);
            assertEquals("https://www.douyin.com/user/self?from_nav=1",web.getUrl());
            assertTrue(field(activity,"desktop",Boolean.class));
            assertTrue(web.getSettings().getUseWideViewPort());assertTrue(web.getSettings().getLoadWithOverviewMode());
            assertTrue("Small desktop account controls must allow pinch zoom",web.getSettings().getBuiltInZoomControls());
            assertFalse(web.getSettings().getDisplayZoomControls());
            assertTrue(field(activity,"status",android.widget.TextView.class).getText().toString().contains("验证码登录"));
            assertTrue(field(activity,"loginEntry",Button.class).isEnabled());
            assertFalse(field(activity,"save",Button.class).isEnabled());
            assertFalse(new AccountStore(activity).hasSaved(LoginPlatform.DOUYIN));
            android.webkit.CookieManager.getInstance().setCookie("https://www.douyin.com/","ttwid=anonymous-ui-fixture; Path=/; Secure");
            web.getWebViewClient().onPageFinished(web,web.getUrl());
            assertFalse("Opening the website or getting a guest cookie is never login success",field(activity,"save",Button.class).isEnabled());
        }
    }

    @Test public void visibleDouyinRecoveryReentersAccountRouteWithoutClearingBrowserCookiesOrSavingMasters() throws Exception {
        try (ActivityController<LoginActivity> controller = Robolectric.buildActivity(LoginActivity.class,
                new Intent().putExtra("platform", "douyin"))) {
            LoginActivity activity=controller.create().start().resume().get();Shadows.shadowOf(Looper.getMainLooper()).idle();
            WebView web=field(activity,"web",WebView.class);
            android.webkit.CookieManager jar=android.webkit.CookieManager.getInstance();
            jar.setCookie("https://www.douyin.com/","ttwid=preserved-guest-fixture; Path=/; Secure");
            String before=jar.getCookie("https://www.douyin.com/");web.loadUrl("https://www.douyin.com/");
            Field desktop=LoginActivity.class.getDeclaredField("desktop");desktop.setAccessible(true);desktop.set(activity,false);
            field(activity,"loginEntry",Button.class).performClick();
            assertEquals(LoginPlatform.DOUYIN.loginUrl,web.getUrl());assertTrue(field(activity,"desktop",Boolean.class));
            assertEquals("Repair navigation does not log out an in-progress browser session",before,jar.getCookie("https://www.douyin.com/"));
            assertFalse(new AccountStore(activity).hasSaved(LoginPlatform.DOUYIN));
        }
    }

    @Test public void desktopPresentationKeepsActualWebViewEngineVersionAndOtherPlatformsStayMobile() {
        String mobile="Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 Version/4.0 Chrome/126.0.6478.122 Mobile Safari/537.36";
        String desktop=LoginActivity.desktopAgent(mobile);
        assertTrue(desktop.contains("Chrome/126.0.6478.122"));assertFalse(desktop.contains("Android"));assertFalse(desktop.contains(" Mobile"));
        assertTrue(LoginPlatform.DOUYIN.prefersDesktopLogin());assertFalse(LoginPlatform.X.prefersDesktopLogin());assertFalse(LoginPlatform.BILIBILI.prefersDesktopLogin());
        assertTrue(LoginPlatform.DOUYIN.allowsNavigation(LoginPlatform.DOUYIN.loginUrl));
        assertFalse(LoginPlatform.DOUYIN.allowsNavigation("https://www.douyin.com.attacker.example/user/self"));
        assertFalse(LoginPlatform.DOUYIN.allowsNavigation("intent://login"));
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner));
    }
}
