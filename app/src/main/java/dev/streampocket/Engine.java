package dev.streampocket;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.ffmpeg.FFmpeg;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.security.MessageDigest;

/** Local yt-dlp adapter. No media or login credentials are sent to an app server. */
public final class Engine {
    private static final Object INIT_LOCK = new Object();
    private static final java.util.concurrent.locks.ReentrantLock UPDATE_LOCK = new java.util.concurrent.locks.ReentrantLock(true);
    private static boolean initialized;
    private static final String BUNDLED_VERSION = "2026.10.26";
    private static final String BUNDLED_SHA256 = "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3";
    static final java.util.concurrent.locks.ReentrantReadWriteLock RUNTIME_LOCK = new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final Context app;
    private volatile String inspectProcess;
    private volatile boolean inspectCancelled;
    private final java.util.Map<String,String> requestScopes = new java.util.concurrent.ConcurrentHashMap<>();

    public Engine(Context context) { app = context.getApplicationContext(); }

    public void init() throws Exception {
        synchronized (INIT_LOCK) {
            CoreUpdate core = coreUpdate();
            if (initialized && !core.needsRecovery()) return;
            RUNTIME_LOCK.writeLock().lockInterruptibly();
            try {
                // Restore an interrupted replacement before the wrapper can recreate a missing core.
                core.recover(coreMetadata());
                if (initialized) return;
                core.discardDownload();
                YoutubeDL.getInstance().init(app);
                FFmpeg.getInstance().init(app);
                installBundledCoreIfOlder();
                copyAssets("plugins", new File(app.getFilesDir(), "plugins"));
                File[] oldSnapshots = new File(app.getCacheDir(), "auth-snapshots").listFiles();
                if (oldSnapshots != null) for (File snapshot : oldSnapshots) snapshot.delete();
                // This executes the bundled interpreter rather than assuming extraction succeeded.
                YoutubeDLRequest diagnostic = new YoutubeDLRequest(Collections.emptyList());
                diagnostic.addOption("--version");
                YoutubeDL.getInstance().execute(diagnostic, "runtime-diagnostic", false, null);
                initialized = true;
            } catch (Throwable error) {
                throw new Exception("下载功能暂时无法启动。请更新应用后重试。" +
                        "\n" + cleanLog(error.toString()), error);
            } finally { RUNTIME_LOCK.writeLock().unlock(); }
        }
    }

    /** The upstream library only copies its raw resource on first install. Migrate upgrades too. */
    private void installBundledCoreIfOlder() throws Exception {
        File binary = new File(app.getNoBackupFilesDir(), "youtubedl-android/yt-dlp/yt-dlp");
        String version = "";
        if (binary.isFile()) {
            try (ZipFile zip = new ZipFile(binary)) {
                ZipEntry entry = zip.getEntry("yt_dlp/version.py");
                if (entry != null) try (InputStream in = zip.getInputStream(entry)) {
                    ByteArrayOutputStream text = new ByteArrayOutputStream();
                    byte[] buffer = new byte[2048]; int n;
                    while ((n = in.read(buffer)) != -1 && text.size() < 32768) text.write(buffer, 0, n);
                    version = CoreRules.zipappVersion(text.toString("UTF-8"));
                }
            } catch (IOException damagedCore) { /* Recover an unreadable old core from our verified resource. */ }
        }
        if (!CoreRules.needsBundledCore(version, BUNDLED_VERSION)) return;
        File parent = binary.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法准备本地解析器目录");
        File staged = File.createTempFile("bundled-", ".tmp", parent);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = app.getResources().openRawResource(R.raw.ytdlp);
                 FileOutputStream out = new FileOutputStream(staged)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) != -1) { digest.update(buffer, 0, n); out.write(buffer, 0, n); }
                out.getFD().sync();
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!BUNDLED_SHA256.equals(hash.toString())) throw new IOException("内置解析器校验失败");
            if (!staged.renameTo(binary)) throw new IOException("无法更新本地解析器");
            app.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit()
                    .putString("dlpVersion", BUNDLED_VERSION).putString("dlpVersionName", BUNDLED_VERSION).apply();
        } finally { staged.delete(); }
    }

    private void copyAssets(String source, File destination) throws Exception {
        String[] children = app.getAssets().list(source);
        if (children != null && children.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs()) throw new Exception("无法创建内核扩展目录");
            for (String child : children) copyAssets(source + "/" + child, new File(destination, child));
        } else if (source.endsWith(".py") || source.endsWith(".txt")) {
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new Exception("无法创建扩展目录");
            try (InputStream input = app.getAssets().open(source); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[16384]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
    }

    public String diagnose() throws Exception {
        init();
        RUNTIME_LOCK.readLock().lockInterruptibly();
        try { return diagnoseLocked(); } finally { RUNTIME_LOCK.readLock().unlock(); }
    }

    private String diagnoseLocked() throws Exception {
        String nativeDirectory = app.getApplicationInfo().nativeLibraryDir;
        File packages = new File(app.getNoBackupFilesDir(), "youtubedl-android/packages");
        String libraryPath = new File(packages, "python/usr/lib") + ":" + new File(packages, "ffmpeg/usr/lib") + ":" + new File(packages, "aria2c/usr/lib");
        ProcessBuilder builder = new ProcessBuilder(nativeDirectory + "/libffmpeg.so", "-version");
        builder.environment().put("LD_LIBRARY_PATH", libraryPath);
        builder.environment().put("PATH", nativeDirectory + ":" + builder.environment().get("PATH"));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (captured) { if (captured.size() < 8192) captured.write(buffer, 0, Math.min(count, 8192 - captured.size())); }
                }
            } catch (IOException ignored) {}
        }, "ffmpeg-diagnostic-reader");
        reader.setDaemon(true); reader.start();
        try {
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (true) {
                try { process.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (System.nanoTime() > deadline) throw new Exception("FFmpeg 诊断超时");
                    Thread.sleep(40);
                }
            }
            reader.join(500);
            String output;
            synchronized (captured) { output = new String(captured.toByteArray(), StandardCharsets.UTF_8); }
            if (process.exitValue() != 0) throw new Exception("FFmpeg 无法执行：\n" + cleanLog(output));
            YoutubeDLRequest version = new YoutubeDLRequest(Collections.emptyList()); version.addOption("--version");
            return "yt-dlp " + YoutubeDL.getInstance().execute(version, "diagnostic-version", false, null).getOut().trim()
                    + "\n" + output.split("\\n", 2)[0] + "\n本地解析与合并组件已响应；这不代表所有平台都可访问。";
        } finally { process.destroy(); }
    }

    public void cancelInspect() {
        inspectCancelled = true;
        String process = inspectProcess;
        if (process != null) YoutubeDL.getInstance().destroyProcessById(process);
    }

    public String update() throws Exception {
        UPDATE_LOCK.lockInterruptibly();
        CoreUpdate core = coreUpdate();
        try {
            init();
            JSONObject release = new JSONObject(AppUpdates.getJson(CoreUpdate.API, 256 * 1024, false));
            if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true))
                throw new IOException("下载支持暂时没有可用的稳定更新");
            JSONObject asset = UpdateOffer.asset(release, "yt-dlp");
            if (asset == null) throw new IOException("下载支持的官方更新文件缺失");
            CoreUpdate.Release offered = new CoreUpdate.Release(release.getString("tag_name"),
                    asset.getString("browser_download_url"), asset.getLong("size"), asset.getString("digest"));
            RUNTIME_LOCK.readLock().lockInterruptibly();
            try {
                String installed = CoreUpdate.archiveVersion(coreBinary());
                if (!CoreRules.needsBundledCore(installed, offered.version)) return installed;
            } finally { RUNTIME_LOCK.readLock().unlock(); }
            // Slow networks leave parsing and active downloads on the current, working core.
            core.prepare(offered, "StreamPocket/" + BuildConfig.VERSION_NAME);
            RUNTIME_LOCK.writeLock().lockInterruptibly();
            try {
                core.activate(offered, coreMetadata(), this::checkCoreVersion);
                ResolvedMediaCache.clear();
                return offered.version;
            } finally { RUNTIME_LOCK.writeLock().unlock(); }
        } finally { core.discardDownload(); UPDATE_LOCK.unlock(); }
    }

    private File coreBinary() {
        return new File(app.getNoBackupFilesDir(), "youtubedl-android/yt-dlp/yt-dlp");
    }

    private CoreUpdate coreUpdate() { return new CoreUpdate(coreBinary()); }

    private CoreUpdate.Metadata coreMetadata() {
        android.content.SharedPreferences preferences = app.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE);
        return new CoreUpdate.Metadata() {
            public String version() { return preferences.getString("dlpVersion", null); }
            public String name() { return preferences.getString("dlpVersionName", null); }
            public void write(String version, String name) throws IOException {
                android.content.SharedPreferences.Editor edit = preferences.edit();
                if (version == null) edit.remove("dlpVersion"); else edit.putString("dlpVersion", version);
                if (name == null) edit.remove("dlpVersionName"); else edit.putString("dlpVersionName", name);
                if (!edit.commit()) throw new IOException("无法保存下载支持的版本信息");
            }
        };
    }

    private void checkCoreVersion(String expectedVersion) throws Exception {
        String nativeDirectory = app.getApplicationInfo().nativeLibraryDir;
        File packages = new File(app.getNoBackupFilesDir(), "youtubedl-android/packages");
        File pythonHome = new File(packages, "python/usr");
        ProcessBuilder builder = new ProcessBuilder(nativeDirectory + "/libpython.so", coreBinary().getAbsolutePath(),
                "--ignore-config", "--no-plugin-dirs", "--version");
        builder.environment().put("LD_LIBRARY_PATH", new File(pythonHome, "lib") + ":"
                + new File(packages, "ffmpeg/usr/lib") + ":" + new File(packages, "aria2c/usr/lib"));
        builder.environment().put("SSL_CERT_FILE", new File(pythonHome, "etc/tls/cert.pem").getAbsolutePath());
        builder.environment().put("PYTHONHOME", pythonHome.getAbsolutePath());
        builder.environment().put("HOME", pythonHome.getAbsolutePath());
        builder.environment().put("TMPDIR", app.getCacheDir().getAbsolutePath());
        builder.environment().put("PATH", builder.environment().get("PATH") + ":" + nativeDirectory);
        CoreUpdate.checkVersion(builder, expectedVersion, 15_000);
    }

    YoutubeDLRequest request(String url, String processId) throws Exception {return request(url,processId,true);}
    YoutubeDLRequest request(String url, String processId,boolean useAccount) throws Exception {
        return requestFor(normalizeUrl(url), processId, useAccount, false);
    }

    private YoutubeDLRequest requestFor(String normalized, String processId, boolean useAccount, boolean collection) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(normalized);
        // Some Bilibili CDN nodes reject a plain GET but accept a complete byte
        // range. This exact option passed both full-file/decode regression cases.
        // Native compatibility keeps the full range. The Bili Metalink public
        // postprocessor removes this static header only after validating exact
        // CDN ranges; aria2 then generates its own disjoint byte ranges.
        if (CoreRules.isBilibiliUrl(normalized)) request.addOption("--add-headers", "Range:bytes=0-");
        request.addOption(collection ? "--yes-playlist" : "--no-playlist");
        request.addOption("--socket-timeout", "15");
        request.addOption("--retries", "3");
        request.addOption("--retry-sleep", "http:exp=1:8");
        request.addOption("--retry-sleep", "fragment:exp=1:8");
        request.addOption("--extractor-retries", "1");
        request.addOption("--fragment-retries", "3");
        request.addOption("--no-colors");
        File plugins = new File(app.getFilesDir(), "plugins");
        if (plugins.isDirectory()) request.addOption("--plugin-dirs", plugins.getAbsolutePath());
        // yt-dlp writes its cookie jar on exit. Each process gets a private copy so it
        // cannot overwrite a newer import or recreate a Cookie the user has cleared.
        File snapshot = cookieSnapshot(processId);
        File parent = snapshot.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建登录凭据临时目录");
        try {
            AccountStore accounts = new AccountStore(app);
            if(useAccount) requestScopes.put(processId, accounts.writeScopedSnapshot(snapshot, normalized));
            else {
                try(FileOutputStream empty=new FileOutputStream(snapshot)){empty.write("# Netscape HTTP Cookie File\n".getBytes(StandardCharsets.UTF_8));}
                requestScopes.put(processId, accounts.cacheScope(normalized));
            }
        }
        catch (Exception failure) { snapshot.delete(); throw failure; }
        request.addOption("--cookies", snapshot.getAbsolutePath());
        String proxy = app.getSharedPreferences("config", Context.MODE_PRIVATE).getString("proxy", "");
        if (proxy != null && !proxy.trim().isEmpty()) request.addOption("--proxy", proxy.trim());
        return request;
    }

    void cleanupRequest(String processId) {
        requestScopes.remove(processId);
        cookieSnapshot(processId).delete();
        new File(cookieSnapshot(processId).getParentFile(), processId.replaceAll("[^A-Za-z0-9_-]", "_") + ".info.json").delete();
        // Only this request's temporary Metalink URLs, never media parts,
        // account masters or another active request's metadata.
        deleteTransferMetadata(transferMetadataDirectory(processId));
    }

    private File transferMetadataDirectory(String processId) {
        String id = processId == null || processId.isEmpty() ? "standalone" : processId.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(new File(app.getCacheDir(), "transfer-metadata"), id);
    }

    private static void deleteTransferMetadata(File entry) {
        File[] children = entry.listFiles();
        if (children != null) for (File child : children) deleteTransferMetadata(child);
        entry.delete();
    }

    /** Download-only options: extraction and account requests never start aria2. */
    String configureDownload(YoutubeDLRequest request, String url) {
        return configureDownload(request, url, false);
    }

    String configureDownload(YoutubeDLRequest request, String url, boolean preserveNativeResume) {
        return configureDownload(request, url, preserveNativeResume, null);
    }

    String configureDownload(YoutubeDLRequest request, String url, boolean preserveNativeResume, String processId) {
        int connections = DownloadTuning.connections(app.getSharedPreferences("config", Context.MODE_PRIVATE)
                .getInt(DownloadTuning.PREFERENCE, DownloadTuning.DEFAULT_CONNECTIONS));
        request.addOption("--concurrent-fragments", String.valueOf(connections));
        request.addOption("--abort-on-unavailable-fragments");
        String proxy = app.getSharedPreferences("config", Context.MODE_PRIVATE).getString("proxy", "");
        if (preserveNativeResume || !DownloadTuning.parallelHttpEligible(url, proxy, connections)) return "native";
        try {
            File binary = new File(app.getApplicationInfo().nativeLibraryDir, "libaria2c.so");
            if (!binary.isFile()) return "native";
            com.yausername.aria2c.Aria2c.getInstance().init(app);
            // Keep this exact token: upstream supplies the packaged CA certificate for it.
            request.addOption("--downloader", "libaria2c.so");
            request.addOption("--downloader", "dash,m3u8:native");
            request.addOption("--downloader-args", DownloadTuning.ariaArguments(connections));
            request.addOption("--use-postprocessor", "StreamPocketTransfer:when=before_dl");
            if (CoreRules.isBilibiliUrl(url))
                request.addOption("--use-postprocessor", "StreamPocketBiliRanges:when=before_dl;metadata_root="
                        + transferMetadataDirectory(processId).getAbsolutePath());
            return "parallel-http";
        } catch (Exception unavailable) {
            // Optional acceleration must not disable the established native downloader.
            return "native";
        }
    }

    private File cookieSnapshot(String processId) {
        return new File(new File(app.getCacheDir(), "auth-snapshots"), "cookies-" + processId.replaceAll("[^A-Za-z0-9_-]", "_") + ".txt");
    }

    public List<Media> inspect(String input) throws Exception {
        return inspect(input, stage -> {});
    }

    public List<Media> inspect(String input, Consumer<String> progress) throws Exception {
        return inspect(input, progress, () -> false);
    }

    public List<Media> inspect(String input, Consumer<String> progress, java.util.function.BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) throw new InterruptedException("解析已取消");
        inspectCancelled = false;
        init();
        String url = normalizeUrl(input);
        AccountStore accounts = new AccountStore(app);
        String accountScope = accounts.cacheScope(url);
        boolean twitter = CoreRules.isTwitterUrl(url);
        boolean signedIn = twitter && hasTwitterLoginCookie();
        List<String> apis = twitter ? (signedIn?java.util.Arrays.asList("","syndication"):SourceScheduler.SHARED.twitterApis()) : Collections.singletonList("");
        StringBuilder failures = new StringBuilder();
        for (String api : apis) {
            for (int retry = 0; retry < 2; retry++) {
                if (inspectCancelled || cancelled.getAsBoolean()) throw new InterruptedException("解析已取消");
                progress.accept(retry > 0 ? "连接暂时失败，正在重试…" : "正在获取视频信息…");
                String processId = "inspect-" + java.util.UUID.randomUUID();
                try (SourceScheduler.Lease lease = SourceScheduler.SHARED.acquire(url,
                        () -> inspectCancelled || cancelled.getAsBoolean(), progress)) {
                    RUNTIME_LOCK.readLock().lockInterruptibly();
                    boolean publicTwitter=twitter&&signedIn&&!api.isEmpty();
                    try {
                        inspectProcess = processId;
                        YoutubeDLRequest request = request(url, processId,!publicTwitter);
                        if (!api.isEmpty()) request.addOption("--extractor-args", "twitter:api=" + api);
                        request.addOption("--dump-single-json");
                        request.addOption("--dump-json");
                        request.addOption("--ignore-errors");
                        request.addOption("--skip-download");
                        if (inspectCancelled || cancelled.getAsBoolean()) throw new InterruptedException("解析已取消");
                        YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, processId, false, null);
                        JSONObject root = metadataFromResponse(response.getOut(), response.getErr(), response.getExitCode());
                        if (inspectCancelled || cancelled.getAsBoolean()) throw new InterruptedException("解析已取消");
                        List<Media> results = mediaFromMetadata(root, url);
                        if (results.isEmpty()) throw new IOException("源站没有返回可下载的完整视频。\n" + cleanLog(response.getErr()));
                        for (Media media : results) {
                            media.twitterApi = api; media.publicTwitter = publicTwitter;
                            JSONObject info = selectedInfo(root, media.id);
                            if (info != null && accountScope.equals(accounts.cacheScope(url)))
                                media.resolvedKey = ResolvedMediaCache.put(info, url, accountScope + ":" + publicTwitter, android.os.SystemClock.elapsedRealtime());
                        }
                        if (!publicTwitter) accounts.noteRequest(url, accountScope, null);
                        lease.outcome(null);
                        return results;
                    } catch (Exception error) {
                        if (inspectCancelled || cancelled.getAsBoolean() || error instanceof InterruptedException)
                            throw new InterruptedException("解析已取消");
                        lease.outcome(error);
                        if (!publicTwitter) accounts.noteRequest(url, accountScope, error);
                        String reason = cleanLog(error.getMessage() == null ? error.toString() : error.getMessage());
                        failures.append(api.isEmpty() ? "默认解析" : "X / " + api).append(":\n")
                                .append(reason.substring(0, Math.min(reason.length(), 3500))).append("\n\n");
                        SourceFailure.Kind kind = SourceFailure.kind(error);
                        if (SourceFailure.retryable(error) && retry == 0) continue;
                        // A logged-in extractor can miss public formats. Try the public
                        // endpoint once only for that error, never for auth/limits/access.
                        if(twitter&&signedIn&&api.isEmpty()&&kind!=SourceFailure.Kind.NO_FORMATS)
                            throw new IOException(failures.toString().trim(),error);
                        // One platform limit is shared across APIs. Do not hammer other endpoints after it.
                        if (kind == SourceFailure.Kind.RATE_LIMIT || kind == SourceFailure.Kind.AUTH
                                || kind == SourceFailure.Kind.CHALLENGE || SourceFailure.retryable(error))
                            throw new IOException(failures.toString().trim(), error);
                        break;
                    } finally { inspectProcess = null; cleanupRequest(processId); RUNTIME_LOCK.readLock().unlock(); }
                }
            }
        }
        throw new IOException(failures.toString().trim());
    }

    private boolean hasTwitterLoginCookie() throws IOException {
        return new AccountStore(app).hasTwitterLogin();
    }

    static JSONObject metadataFromResponse(String output, String stderr, int exitCode) throws Exception {
        try {
            JSONObject data = lastJsonObject(output == null ? "" : output);
            if (hasMedia(data)) return data;
            // dump-json may contain a valid earlier entry followed by an empty
            // playlist/card. Keep actual playable entries, not the final null.
            JSONArray recovered=new JSONArray();
            for(String line:(output==null?"":output).split("\\r?\\n"))try{JSONObject item=new JSONObject(line);if(hasMedia(item)&&item.optInt("playlist_index",0)>0)recovered.put(item);}catch(Exception ignored){}
            if(recovered.length()>0)return new JSONObject().put("entries",recovered);
        } catch (Exception noMetadata) { /* The useful error belongs to stderr, not the JSON parser. */ }
        String error = cleanLog(stderr == null ? "" : stderr).trim();
        if (error.isEmpty()) error = "解析器没有返回有效视频数据。";
        throw new IOException("解析器退出码 " + exitCode + "\n" + error);
    }

    private static boolean hasMedia(JSONObject data) {
        JSONArray entries = data.optJSONArray("entries");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) { JSONObject entry = entries.optJSONObject(i); if (entry != null && hasMedia(entry)) return true; }
            return false;
        }
        JSONArray formats = data.optJSONArray("formats");
        if (formats != null) for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format != null && !format.isNull("url") && !format.optString("url").isEmpty()) return true;
        }
        return !data.isNull("url") && !data.optString("url").isEmpty() && !data.optString("id").isEmpty();
    }

    static List<Media> mediaFromMetadata(JSONObject root, String url) throws Exception {
        List<Media> results = new ArrayList<>();
        if (root.optBoolean("_streampocket_album")) { results.add(parseMedia(root, url, 0)); return results; }
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) {
            results.add(parseMedia(root, url, 0));
        } else {
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null || !hasMedia(entry)) continue;
                if ("is_live".equals(entry.optString("live_status")) || "is_upcoming".equals(entry.optString("live_status")) || entry.optBoolean("is_live")) continue;
                results.add(parseMedia(entry, url, index + 1));
            }
        }
        return results;
    }

    static JSONObject lastJsonObject(String output) throws Exception {
        String[] lines = output.split("\\r?\\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.startsWith("{")) continue;
            try { return new JSONObject(line); } catch (Exception invalidLine) { /* Continue to preceding JSON. */ }
        }
        throw new Exception("解析器未返回可用的视频信息；请检查链接、登录状态或更新下载内核。");
    }

    private static Media parseMedia(JSONObject data, String source, int playlistIndex) throws Exception {
        String live = data.optString("live_status");
        if (data.optBoolean("is_live") || "is_live".equals(live) || "is_upcoming".equals(live)) {
            throw new Exception("暂不下载直播或尚未发布的视频；请在直播结束、完整回放可用后重试。");
        }
        Media media = new Media();
        media.url = source;
        media.playlistIndex = Math.max(0,data.optInt("playlist_index",playlistIndex));
        media.id = data.optString("id", "video");
        media.title = data.optString("title", "未命名视频");
        media.uploader = data.optString("uploader", data.optString("channel", ""));
        media.duration = (long) data.optDouble("duration", 0);
        media.thumbnail = data.optString("thumbnail", "");
        media.album = data.optBoolean("_streampocket_album");
        media.imageCount = media.album ? data.optInt("_streampocket_image_count", data.optJSONArray("entries") == null ? 0 : data.optJSONArray("entries").length()) : 0;
        media.subtitleAvailable = hasEntries(data.optJSONObject("subtitles")) || hasEntries(data.optJSONObject("automatic_captions"));
        media.subtitleLanguage = preferredSubtitle(data);
        media.width = Math.max(0,data.optInt("width",0)); media.height = Math.max(0,data.optInt("height",0));
        media.filesize = data.optLong("filesize",-1);
        if(media.filesize<=0){media.filesize=data.optLong("filesize_approx",-1);media.filesizeEstimated=media.filesize>0;}
        String host = new URI(source).getHost().toLowerCase(Locale.ROOT);
        VideoPlatform channel = VideoPlatform.detect(source);
        media.platform = channel == null ? host : channel.title;
        media.hasAudio = audioCodec(data);
        if (media.album) { media.qualities.add(new Quality("images", "全部图片 · ZIP", "best")); return media; }
        media.qualities.add(new Quality("best", "最高可用画质", "bv*+ba/b"));
        TreeSet<Integer> heights = new TreeSet<>(Collections.reverseOrder());
        java.util.Map<Integer, Double> frameRates = new java.util.HashMap<>();
        JSONArray formats = data.optJSONArray("formats");
        if (formats != null) for (int index = 0; index < formats.length(); index++) {
            JSONObject format = formats.optJSONObject(index);
            if (format != null && audioCodec(format)) media.hasAudio = true;
            if (format == null || "none".equals(format.optString("vcodec"))) continue;
            int height = format.optInt("height", 0);
            if (height > 0) {
                heights.add(height);
                double fps = format.optDouble("fps", 0);
                if (Double.isFinite(fps) && fps > 0 && fps <= 1000)
                    frameRates.put(height, Math.max(frameRates.getOrDefault(height, 0d), fps));
            }
        }
        for (int height : heights) {
            Quality quality = new Quality(String.valueOf(height), height + "p",
                    "bv*[height=" + height + "]+ba/b[height=" + height + "]");
            double fps = frameRates.getOrDefault(height, 0d);
            if (fps > 0) quality.details = "源站最高 " + String.format(Locale.ROOT, "%.0f", fps) + " fps";
            media.qualities.add(quality);
        }
        return media;
    }

    private static boolean audioCodec(JSONObject format) {
        String codec = format.optString("acodec", "");
        return !codec.isEmpty() && !"none".equals(codec) && !"null".equals(codec);
    }

    private static boolean hasEntries(JSONObject data) { return data != null && data.length() > 0; }

    static String preferredSubtitle(JSONObject data) {
        java.util.Set<String> languages = new TreeSet<>();
        for (String key : new String[]{"subtitles", "automatic_captions"}) {
            JSONObject subs = data.optJSONObject(key); if (subs == null) continue;
            java.util.Iterator<String> keys = subs.keys();
            while (keys.hasNext()) { String lang = keys.next(); if (lang.matches("[A-Za-z0-9_-]{1,40}") && !lang.equals("live_chat")) languages.add(lang); }
        }
        for (String prefix : new String[]{"zh", "en", ""}) for (String lang : languages) if (lang.startsWith(prefix)) return lang;
        return "";
    }

    private static JSONObject selectedInfo(JSONObject root, String id) {
        if (id.equals(root.optString("id"))) return root;
        JSONArray entries = root.optJSONArray("entries");
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    boolean useResolved(YoutubeDLRequest request, Media media, String processId) throws Exception {
        String scope = new AccountStore(app).cacheScope(media.url);
        if (!scope.equals(requestScopes.get(processId))) return false;
        JSONObject info = ResolvedMediaCache.get(media.resolvedKey, media.url, scope + ":" + media.publicTwitter, android.os.SystemClock.elapsedRealtime());
        if (info == null) return false;
        // Refresh is explicit and bounded in the worker, not an implicit second extraction by yt-dlp.
        cleanResolvedInfo(info);
        File file = new File(cookieSnapshot(processId).getParentFile(), processId.replaceAll("[^A-Za-z0-9_-]", "_") + ".info.json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            file.setReadable(false, false); file.setWritable(false, false); file.setReadable(true, true); file.setWritable(true, true);
            out.write(info.toString().getBytes(StandardCharsets.UTF_8));
        }
        request.addOption("--load-info-json", file.getAbsolutePath());
        // yt-dlp's default info-json cleaner removes playlist entries entirely.
        if (media.album) request.addOption("--no-clean-info-json");
        return true;
    }

    static void cleanResolvedInfo(JSONObject info) {
        for(String key:new String[]{"webpage_url","original_url","filepath","_filename","filename","infojson_filename",
                "requested_downloads","requested_formats","requested_subtitles","requested_entries","__files_to_move","__postprocessors"}) info.remove(key);
        JSONArray entries=info.optJSONArray("entries");if(entries!=null)for(int i=0;i<entries.length();i++) {
            JSONObject item=entries.optJSONObject(i);if(item!=null)cleanResolvedInfo(item);
        }
    }

    String requestScope(String processId) { return requestScopes.getOrDefault(processId, ""); }

    static YoutubeDLRequest withoutResolved(YoutubeDLRequest original) {
        List<String> args = new ArrayList<>(original.buildCommand());
        int index = args.indexOf("--load-info-json");
        if (index >= 0) { args.remove(index); if (index < args.size()) args.remove(index); }
        return new YoutubeDLRequest(Collections.emptyList()).addCommands(args);
    }

    static boolean expiredMedia(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return value.contains("http error 403") || value.contains("http error 410") || value.contains("url has expired")
                || value.contains("requested format is not available");
    }

    public static final class CollectionItem {
        final String url, title;
        final long duration, bytes;
        CollectionItem(String url, String title, long duration, long bytes) { this.url=url;this.title=title;this.duration=duration;this.bytes=bytes; }
    }

    List<CollectionItem> collection(String input, Consumer<String> progress, java.util.function.BooleanSupplier cancelled) throws Exception {
        String url = CollectionLinks.normalize(input); init(); inspectCancelled=false;
        String processId = "collection-" + java.util.UUID.randomUUID();
        try (SourceScheduler.Lease lease = SourceScheduler.SHARED.acquire(url, cancelled, progress)) {
            RUNTIME_LOCK.readLock().lockInterruptibly();
            try {
                inspectProcess=processId;
                YoutubeDLRequest request=requestFor(url,processId,true,true);
                request.addOption("--flat-playlist"); request.addOption("--playlist-end","100");
                request.addOption("--skip-download");request.addOption("--dump-single-json");
                YoutubeDLResponse response=YoutubeDL.getInstance().execute(request,processId,false,null);
                if (cancelled.getAsBoolean() || inspectCancelled) throw new InterruptedException("已取消展开合集");
                if (response.getExitCode()!=0) throw new IOException(cleanLog(response.getErr()));
                return collectionItems(lastJsonObject(response.getOut()),url);
            } finally { inspectProcess=null;cleanupRequest(processId);RUNTIME_LOCK.readLock().unlock(); }
        }
    }

    static List<CollectionItem> collectionItems(JSONObject root, String source) {
        List<CollectionItem> results=new ArrayList<>();java.util.Set<String> seen=new java.util.HashSet<>();
        JSONArray entries=root.optJSONArray("entries");if(entries==null)return results;
        for(int i=0;i<Math.min(100,entries.length());i++) {
            JSONObject item=entries.optJSONObject(i);if(item==null || item.optBoolean("is_live") || "is_upcoming".equals(item.optString("live_status")))continue;
            try {
                String url=item.optString("webpage_url",item.optString("url"));
                if(VideoPlatform.detect(source)==VideoPlatform.YOUTUBE && item.optString("id").matches("[A-Za-z0-9_-]{11}")) url="https://www.youtube.com/watch?v="+item.optString("id");
                if(url.isEmpty()&&VideoPlatform.detect(source)==VideoPlatform.BILIBILI)url=source+"?p="+(i+1);
                url=normalizeUrl(url);
                if(VideoPlatform.detect(url)!=VideoPlatform.detect(source) || !seen.add(url))continue;
                results.add(new CollectionItem(url,item.optString("title","第 "+(i+1)+" 项"),(long)item.optDouble("duration"),item.optLong("filesize",-1)));
            }catch(Exception invalid) { /* Unavailable entries are not fabricated or silently downloaded. */ }
        }
        return results;
    }

    public static String normalizeUrl(String text) {
        String url = HeyboxLinks.normalizeVideo(PornhubLinks.normalizeVideo(YouTubeLinks.normalizeVideo(CoreRules.normalizeUrl(text))));
        try {
            URI uri = new URI(url);
            // The built-in extractor requires www; signed query bytes must survive normalization.
            if ("xiaohongshu.com".equalsIgnoreCase(uri.getHost()))
                return "https://www.xiaohongshu.com" + uri.getRawPath()
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception ignored) { /* CoreRules already validates the public URL. */ }
        return url;
    }
    public static String cleanLog(String text) { return CoreRules.cleanLog(text); }
    public static String userMessage(Throwable error) { return CoreRules.userMessage(error); }
    public static String inspectionMessage(Throwable error) { return CoreRules.inspectionMessage(error); }

    public static final class Quality {
        public String id, label, selector;
        public String audioFormat = "", details = "";
        public Quality(String id, String label, String selector) { this.id = id; this.label = label; this.selector = selector; }
        public boolean audioOnly() { return "m4a".equals(audioFormat) || "mp3".equals(audioFormat); }
        static Quality audio(String format) {
            Quality quality = new Quality("audio-" + format, "仅音频 · " + format.toUpperCase(Locale.ROOT), "ba/b");
            quality.audioFormat = format;
            quality.details = "mp3".equals(format) ? "兼容常见播放器 · 转换需要一些时间" : "只保存声音 · 必要时转换为 M4A";
            return quality;
        }
        void configure(YoutubeDLRequest request) {
            request.addOption("--format", selector);
            if (audioOnly()) {
                request.addOption("--extract-audio");
                request.addOption("--audio-format", audioFormat);
                request.addOption("--audio-quality", "0");
            } else request.addOption("--merge-output-format", "mp4/mkv");
        }
        public JSONObject toJson() throws Exception { return new JSONObject().put("id", id).put("label", label).put("selector", selector).put("audioFormat", audioFormat).put("details", details); }
        public static Quality fromJson(JSONObject json) {
            Quality quality = new Quality(json.optString("id", "best"), json.optString("label", "最高可用画质"), json.optString("selector", "bv*+ba/b"));
            String format = json.optString("audioFormat", "");
            if ("mp3".equals(format) || "m4a".equals(format)) quality.audioFormat = format;
            quality.details = json.optString("details", "");
            return quality;
        }
    }

    public static final class Media {
        public String url = "", id = "", title = "", uploader = "", platform = "";
        public String twitterApi = "", thumbnail = "";
        public boolean publicTwitter, hasAudio;
        public String resolvedKey = "";
        public String subtitleLanguage = "";
        public boolean album, subtitleAvailable, saveSubtitles, saveCover;
        public int imageCount;
        public int width, height;
        public long filesize=-1;
        public boolean filesizeEstimated;
        public long duration;
        public int playlistIndex;
        public List<Quality> qualities = new ArrayList<>();
        public List<Quality> downloadChoices() {
            List<Quality> choices = new ArrayList<>(qualities);
            if (hasAudio) { choices.add(Quality.audio("m4a")); choices.add(Quality.audio("mp3")); }
            return choices;
        }
        public JSONObject toJson() throws Exception {
            JSONArray choices = new JSONArray(); for (Quality quality : qualities) choices.put(quality.toJson());
            return new JSONObject().put("url", url).put("id", id).put("title", title).put("uploader", uploader)
                    .put("resolvedKey", resolvedKey).put("album", album).put("imageCount", imageCount).put("subtitleAvailable", subtitleAvailable)
                    .put("saveSubtitles", saveSubtitles).put("saveCover", saveCover)
                    .put("subtitleLanguage", subtitleLanguage)
                    .put("thumbnail",thumbnail).put("width",width).put("height",height).put("filesize",filesize).put("filesizeEstimated",filesizeEstimated)
                    .put("platform", platform).put("duration", duration).put("playlistIndex", playlistIndex).put("twitterApi", twitterApi).put("publicTwitter",publicTwitter).put("hasAudio", hasAudio).put("qualities", choices);
        }
        public static Media fromJson(JSONObject json) {
            Media media = new Media(); media.url = json.optString("url"); media.id = json.optString("id");
            media.title = json.optString("title"); media.uploader = json.optString("uploader"); media.platform = json.optString("platform");
            media.duration = json.optLong("duration"); media.playlistIndex = json.optInt("playlistIndex");
            media.twitterApi = json.optString("twitterApi", "");media.publicTwitter=json.optBoolean("publicTwitter",false);
            media.hasAudio = json.optBoolean("hasAudio", false);
            media.resolvedKey=json.optString("resolvedKey"); media.album=json.optBoolean("album"); media.imageCount=json.optInt("imageCount");
            media.subtitleAvailable=json.optBoolean("subtitleAvailable"); media.saveSubtitles=json.optBoolean("saveSubtitles"); media.saveCover=json.optBoolean("saveCover");
            media.subtitleLanguage=json.optString("subtitleLanguage");
            media.thumbnail=json.optString("thumbnail", ""); media.width=Math.max(0,json.optInt("width",0));media.height=Math.max(0,json.optInt("height",0));
            media.filesize=json.optLong("filesize",-1);media.filesizeEstimated=json.optBoolean("filesizeEstimated");
            JSONArray choices = json.optJSONArray("qualities"); if (choices != null) for (int i = 0; i < choices.length(); i++) {
                JSONObject quality = choices.optJSONObject(i); if (quality != null) media.qualities.add(Quality.fromJson(quality));
            }
            if (media.qualities.isEmpty()) media.qualities.add(new Quality("best", "最高可用画质", "bv*+ba/b"));
            return media;
        }
    }
}
