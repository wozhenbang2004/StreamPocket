package dev.streampocket;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.webkit.CookieManagerCompat;
import androidx.webkit.WebViewFeature;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Official website login in this app's own, temporary WebView cookie jar. */
public final class LoginActivity extends Activity {
    private int INK, MUTED, LINE, SURFACE, BG, ACCENT;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LoginPlatform platform;
    private WebView web;
    private CookieManager cookies;
    private TextView address, status;
    private Button save, loginEntry;
    private ProgressBar progress;
    private boolean closing, saving, ready, detected, desktop;
    private int browserGeneration;
    private String mobileAgent;
    private final Runnable observe = new Runnable() {
        @Override public void run() {
            if (closing || !ready) return;
            updateDetection();
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        UiTheme.apply(this); super.onCreate(state);
        UiTheme.Palette palette=UiTheme.get(this); INK=palette.ink;MUTED=palette.muted;LINE=palette.line;SURFACE=palette.surface;BG=palette.bg;ACCENT=palette.accent;
        platform = LoginPlatform.fromId(getIntent().getStringExtra("platform"));
        if (platform == null) { super.finish(); return; }
        desktop = platform.prefersDesktopLogin();
        // User-controlled screenshots are needed to scan official login QR codes.
        // This exception is local to this page; credentials still never enter logs.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        UiTheme.systemBars(this);
        buildScreen();
        try { openWebsite(); }
        catch (RuntimeException unavailable) {
            status.setText("系统网页组件无法启动。请更新 Android System WebView 后重试。");
            save.setEnabled(false);
        }
    }

    private void buildScreen() {
        LinearLayout root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        Button cancel = button("取消", false); cancel.setOnClickListener(v -> requestClose());
        header.addView(cancel, new LinearLayout.LayoutParams(dp(64), dp(48)));
        LinearLayout title = column(); title.setPadding(dp(8), 0, 0, 0);
        title.addView(text(platform.title + "登录", 16, INK, true));
        address = text(URI.create(platform.loginUrl).getHost(), 12, MUTED, false); address.setMaxLines(1); title.addView(address);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button options = button("选项", false); options.setOnClickListener(v -> showOptions(options));
        header.addView(options, new LinearLayout.LayoutParams(dp(64), dp(48))); root.addView(header);
        progress = new PocketProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(INK));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        LinearLayout webArea = column(); webArea.setTag("official-web-area");
        root.addView(webArea, new LinearLayout.LayoutParams(-1, 0, 1));
        View divider = new View(this); divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout footer = column(); footer.setPadding(dp(20), dp(12), dp(20), dp(16));
        status = text("正在准备官方登录页…", 12, MUTED, false);
        footer.addView(status, new LinearLayout.LayoutParams(-1, -2));
        save = button("登录后保存到本机", true); save.setEnabled(false);
        save.setOnClickListener(v -> saveSession());
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-1, dp(48)); action.topMargin = dp(10);
        if (platform == LoginPlatform.DOUYIN) {
            // Keep a visible recovery action beside Save, rather than hiding
            // the desktop account entry inside an overflow help menu.
            LinearLayout actions = new LinearLayout(this);
            actions.addView(save, new LinearLayout.LayoutParams(0, dp(48), 1));
            loginEntry = button("重新打开登录", false); loginEntry.setEnabled(false);
            loginEntry.setOnClickListener(v -> reopenLoginEntry());
            LinearLayout.LayoutParams retry = new LinearLayout.LayoutParams(dp(128), dp(48)); retry.leftMargin = dp(8);
            actions.addView(loginEntry, retry); footer.addView(actions, action);
        } else footer.addView(save, action);
        root.addView(footer);
        setContentView(root); root.requestApplyInsets();
    }

    private void openWebsite() {
        web = new WebView(this); web.setBackgroundColor(Color.WHITE);
        View area = getWindow().getDecorView().findViewWithTag("official-web-area");
        ((LinearLayout) area).addView(web, new LinearLayout.LayoutParams(-1, -1));
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        mobileAgent = settings.getUserAgentString();
        if (mobileAgent == null || mobileAgent.trim().isEmpty()) mobileAgent = WebSettings.getDefaultUserAgent(this);
        applyBrowserMode();
        cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value); progress.setVisibility(value == 100 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return blockNavigation(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return blockNavigation(url); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (closing) { view.stopLoading(); return; }
                if (blockNavigation(url)) { view.stopLoading(); return; }
                try { address.setText(new URI(url).getHost()); }
                catch (Exception ignored) { address.setText("官方网页"); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!closing && ready) updateDetection();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !closing) {
                    status.setText("登录页未能加载，请检查手机网络，再从选项中刷新。");
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                status.setText("网页安全连接失败，请检查网络和系统时间后重试。");
            }
        });
        prepareWebSession(true);
    }

    private void prepareWebSession(boolean reuseSaved) {
        final int token = ++browserGeneration;
        ready = false; detected = false; save.setEnabled(false); save.setText("登录后保存到本机");
        if (loginEntry != null) { loginEntry.setVisibility(View.VISIBLE); loginEntry.setEnabled(false); }
        status.setText(reuseSaved ? "正在读取本机已保存的登录信息…" : "正在准备新的官方登录页…");
        // Isolate the temporary browser before restoring ONLY the selected
        // platform. Encrypted masters and other app data are never cleared here.
        cookies.removeAllCookies(ignored -> {
            if (!currentBrowser(token)) return;
            WebStorage.getInstance().deleteAllData();
            web.clearCache(true);
            AccountStore accounts = new AccountStore(this);
            if (!reuseSaved || !accounts.hasSaved(platform)) {
                openOfficialPage(token, false, ""); return;
            }
            io.execute(() -> {
                try {
                    List<AccountStore.WebCookie> rows = AccountStore.webRestoreCookies(platform, accounts.load(platform));
                    main.post(() -> restoreWebCookies(token, rows, 0, 0));
                } catch (Exception unavailable) {
                    main.post(() -> openOfficialPage(token, false, "已保存的登录信息未能带入网页，原数据仍保留。可重新登录后保存。"));
                }
            });
        });
    }

    private boolean currentBrowser(int token) {
        return !closing && !isDestroyed() && web != null && token == browserGeneration;
    }

    private void restoreWebCookies(int token, List<AccountStore.WebCookie> rows, int index, int restored) {
        if (!currentBrowser(token)) return;
        if (index == rows.size()) { openOfficialPage(token, restored > 0, ""); return; }
        AccountStore.WebCookie row = rows.get(index);
        try {
            cookies.setCookie(row.url, row.value, accepted -> {
                if (currentBrowser(token)) main.post(() -> restoreWebCookies(token, rows, index + 1,
                        restored + (Boolean.TRUE.equals(accepted) ? 1 : 0)));
            });
        } catch (RuntimeException refused) {
            main.post(() -> restoreWebCookies(token, rows, index + 1, restored));
        }
    }

    private void openOfficialPage(int token, boolean restored, String error) {
        if (!currentBrowser(token)) return;
        ready = true;
        if (loginEntry != null) loginEntry.setEnabled(true);
        status.setText(!error.isEmpty() ? error : restored
                ? "已带入本机保存的登录信息；是否仍有效由平台确认。" : instruction());
        web.loadUrl(platform.loginUrl);
        main.removeCallbacks(observe); main.post(observe);
    }

    private String instruction() {
        if (platform == LoginPlatform.DOUYIN) return desktop
                ? "抖音官方账号页：请选择「验证码登录」或「密码登录」。若只有首页，点「重新打开登录」。"
                : "手机版可能只显示官网首页。点「重新打开登录」可切换到电脑版账号入口。";
        return platform == LoginPlatform.X
                ? "请用手机号、邮箱或用户名登录。检测到登录信息后可保存。"
                : "请在官方网页完成登录，再保存到本机。";
    }

    private boolean blockNavigation(String url) {
        if (platform.allowsNavigation(url)) return false;
        status.setText("此入口无法在本页打开，请使用平台账号或短信登录。可从选项查看帮助。");
        return true;
    }

    private String collectSession() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            Map<String, List<String>> entries = new LinkedHashMap<>();
            for (String url : platform.cookieUrls()) entries.put(url, CookieManagerCompat.getCookieInfo(cookies, url));
            return AccountStore.fromWebCookieInfo(platform, entries);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String url : platform.cookieUrls()) {
            String value = cookies.getCookie(url);
            if (value != null && !value.isEmpty()) headers.put(url, value);
        }
        return AccountStore.fromWebCookies(platform, headers);
    }

    private void updateDetection() {
        if (saving || cookies == null) return;
        boolean found;
        try { found = platform.hasLogin(collectSession()); }
        catch (IllegalArgumentException malformed) { found = false; }
        if (found != detected) {
            detected = found;
            status.setText(found ? "已检测到账号会话，可保存到本机；有效性仍以平台响应为准。" : instruction());
        }
        save.setEnabled(found);
        save.setText(found ? "保存并返回" : "登录后保存到本机");
        if (loginEntry != null) loginEntry.setVisibility(found ? View.GONE : View.VISIBLE);
    }

    private void saveSession() {
        if (saving || closing || !ready) return;
        final String session;
        try {
            session = collectSession();
            if (!platform.hasLogin(session)) {
                status.setText("还未检测到账号登录信息，请先完成网页登录。");
                return;
            }
        } catch (Exception malformed) { status.setText("无法读取本页登录信息，请刷新后重试。"); return; }
        saving = true; save.setEnabled(false); save.setText("正在保存…");
        if (loginEntry != null) loginEntry.setEnabled(false);
        io.execute(() -> {
            try {
                new AccountStore(this).save(platform, session, "网页登录");
                main.post(() -> {
                    if (isDestroyed() || closing) return;
                    setResult(RESULT_OK); closeWebSession();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (isDestroyed() || closing) return;
                    saving = false; status.setText("本机保存未完成，请重试或返回账号管理查看状态。");
                    save.setEnabled(true); save.setText("重新保存");
                    if (loginEntry != null) loginEntry.setEnabled(true);
                });
            }
        });
    }

    private void reopenLoginEntry() {
        if (web == null || !ready || saving || closing) return;
        desktop = platform.prefersDesktopLogin() || desktop;
        applyBrowserMode(); status.setText(instruction());
        // Navigation only: do not clear the current browser session or any
        // encrypted saved account while repairing a landing-page detour.
        web.loadUrl(platform.loginUrl);
    }

    private void applyBrowserMode() {
        if (web == null) return;
        WebSettings settings = web.getSettings();
        if (mobileAgent != null && !mobileAgent.isEmpty())
            settings.setUserAgentString(desktop ? desktopAgent(mobileAgent) : mobileAgent);
        settings.setUseWideViewPort(desktop); settings.setLoadWithOverviewMode(desktop);
        settings.setSupportZoom(desktop); settings.setBuiltInZoomControls(desktop);
        settings.setDisplayZoomControls(false);
    }

    static String desktopAgent(String mobile) {
        // Keep this device's actual engine version; only request the website's
        // desktop presentation, as the existing explicit browser option did.
        return mobile.replaceFirst("\\([^)]*\\)", "(X11; Linux x86_64)").replace(" Mobile", "");
    }

    private void showOptions(View anchor) {
        PocketMenu menu = new PocketMenu(this, anchor);
        menu.getMenu().add("刷新登录页").setOnMenuItemClickListener(item -> {
            if (web != null && ready && !saving) web.reload(); return true;
        });
        menu.getMenu().add("使用其他账号重新登录").setOnMenuItemClickListener(item -> {
            if (web != null && ready && !saving) prepareWebSession(false);
            return true;
        });
        menu.getMenu().add(desktop ? "使用手机版网页" : "使用电脑版网页").setOnMenuItemClickListener(item -> {
            if (web != null && ready && !saving) {
                desktop = !desktop;
                applyBrowserMode(); status.setText(instruction());
                // Re-enter the official account route, not a cached mobile
                // landing-page redirect. Current cookies are preserved.
                web.loadUrl(platform.loginUrl);
            }
            return true;
        });
        menu.getMenu().add("登录帮助").setOnMenuItemClickListener(item -> {
            new PocketDialog.Builder(this).setTitle("在官方网页登录")
                    .setMessage("登录页跟随手机网络与系统 VPN。请完成平台要求的验证码或短信验证。页面只有打开 App 的入口时，可在选项切换电脑版网页。\n\nGoogle、Apple 等第三方登录可能不支持此网页环境，请优先使用平台账号登录。\n\n保存的登录信息仅供本机下载，账号是否有效及可用画质以源站响应为准。可在账号管理中暂停、重新登录或清除。")
                    .setPositiveButton("知道了", null).show();
            return true;
        });
        menu.show();
    }

    private void requestClose() {
        if (closing || saving) return;
        if (detected) new PocketDialog.Builder(this).setTitle("登录信息尚未保存")
                .setMessage("保存后可用于下载，也可以直接离开。原有已保存信息会保留。")
                .setNegativeButton("继续登录", null)
                .setNeutralButton("离开", (d, w) -> closeWebSession())
                .setPositiveButton("保存", (d, w) -> saveSession()).show();
        else closeWebSession();
    }

    private void closeWebSession() {
        if (closing) return;
        closing = true; browserGeneration++; main.removeCallbacks(observe);
        destroyWebView();
        if (cookies == null) { super.finish(); return; }
        WebStorage.getInstance().deleteAllData();
        cookies.removeAllCookies(ignored -> { cookies.flush(); LoginActivity.super.finish(); });
    }

    @Override public void onBackPressed() {
        if (!saving && web != null && web.canGoBack()) web.goBack(); else requestClose();
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (ready && !closing) { main.removeCallbacks(observe); main.post(observe); }
    }
    @Override protected void onPause() {
        main.removeCallbacks(observe); if (web != null) web.onPause(); super.onPause();
    }
    @Override protected void onDestroy() {
        browserGeneration++;
        main.removeCallbacksAndMessages(null);
        destroyWebView();
        if (cookies != null && !closing) {
            WebStorage.getInstance().deleteAllData(); cookies.removeAllCookies(null); cookies.flush();
        }
        io.shutdown(); super.onDestroy();
    }

    private void destroyWebView() {
        if (web == null) return;
        web.stopLoading();
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        web.destroy(); web = null;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(this); result.setText(value); result.setTextSize(size); result.setTextColor(color);
        result.setLineSpacing(dp(2), 1); if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return result;
    }
    private Button button(String label,boolean primary){return PocketButton.of(this,label,primary?PocketButton.Role.PRIMARY:PocketButton.Role.SECONDARY);}
}
