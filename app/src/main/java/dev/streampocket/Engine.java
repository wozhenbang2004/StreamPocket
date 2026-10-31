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
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.security.MessageDigest;

/** Local yt-dlp adapter. No media or login credentials are sent to an app server. */
public final class Engine {
    private static final Object INIT_LOCK = new Object();
    private static boolean initialized;
    private static final String BUNDLED_VERSION = "2026.10.26";
    private static final String BUNDLED_SHA256 = "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3";
    static final java.util.concurrent.locks.ReentrantReadWriteLock RUNTIME_LOCK = new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final Context app;
    private volatile String inspectProcess;
    private volatile boolean inspectCancelled;

    public Engine(Context context) { app = context.getApplicationContext(); }

    public void init() throws Exception {
        synchronized (INIT_LOCK) {
            if (initialized) return;
            try {
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
                throw new Exception("下载内核初始化失败。请确认安装包包含适合本机的原生库；当前版本可能不支持此设备的内存页大小。" +
                        "\n" + cleanLog(error.toString()), error);
            }
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
        init();
        RUNTIME_LOCK.writeLock().lockInterruptibly();
        try {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
            return String.valueOf(YoutubeDL.getInstance().version(app));
        } finally { RUNTIME_LOCK.writeLock().unlock(); }
    }

    YoutubeDLRequest request(String url, String processId) throws Exception {
        String normalized = normalizeUrl(url);
        YoutubeDLRequest request = new YoutubeDLRequest(normalized);
        // Some Bilibili CDN nodes reject a plain GET but accept a complete byte
        // range. This exact option passed both full-file/decode regression cases.
        // Native compatibility keeps the full range. The Bili Metalink public
        // postprocessor removes this static header only after validating exact
        // CDN ranges; aria2 then generates its own disjoint byte ranges.
        if (CoreRules.isBilibiliUrl(normalized)) request.addOption("--add-headers", "Range:bytes=0-");
        request.addOption("--no-playlist");
        request.addOption("--socket-timeout", "15");
        request.addOption("--retries", "3");
        request.addOption("--fragment-retries", "3");
        request.addOption("--no-colors");
        File plugins = new File(app.getFilesDir(), "plugins");
        if (plugins.isDirectory()) request.addOption("--plugin-dirs", plugins.getAbsolutePath());
        // yt-dlp writes its cookie jar on exit. Each process gets a private copy so it
        // cannot overwrite a newer import or recreate a Cookie the user has cleared.
        File snapshot = cookieSnapshot(processId);
        File parent = snapshot.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建登录凭据临时目录");
        try { new AccountStore(app).writeSnapshot(snapshot); }
        catch (Exception failure) { snapshot.delete(); throw failure; }
        request.addOption("--cookies", snapshot.getAbsolutePath());
        String proxy = app.getSharedPreferences("config", Context.MODE_PRIVATE).getString("proxy", "");
        if (proxy != null && !proxy.trim().isEmpty()) request.addOption("--proxy", proxy.trim());
        return request;
    }

    void cleanupRequest(String processId) {
        cookieSnapshot(processId).delete();
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
        inspectCancelled = false;
        init();
        String url = normalizeUrl(input);
        boolean twitter = CoreRules.isTwitterUrl(url);
        boolean signedIn = twitter && hasTwitterLoginCookie();
        List<String> apis = twitter && !signedIn ? Arrays.asList("graphql", "syndication", "legacy") : Collections.singletonList("");
        StringBuilder failures = new StringBuilder();
        RUNTIME_LOCK.readLock().lockInterruptibly();
        try {
            for (int attempt = 0; attempt < apis.size(); attempt++) {
                if (inspectCancelled) throw new InterruptedException("解析已取消");
                String api = apis.get(attempt);
                progress.accept(attempt == 0 ? "正在获取视频信息…" : "正在尝试另一种解析方式…");
                String processId = "inspect-" + java.util.UUID.randomUUID();
                inspectProcess = processId;
                try {
                    YoutubeDLRequest request = request(url, processId);
                    if (!api.isEmpty()) request.addOption("--extractor-args", "twitter:api=" + api);
                    request.addOption("--dump-single-json");
                    // Retain partial playlists, but never confuse the literal `null` with success.
                    request.addOption("--dump-json");
                    request.addOption("--ignore-errors");
                    request.addOption("--skip-download");
                    if (inspectCancelled) throw new InterruptedException("解析已取消");
                    YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, processId, false, null);
                    JSONObject root = metadataFromResponse(response.getOut(), response.getErr(), response.getExitCode());
                    if (inspectCancelled) throw new InterruptedException("解析已取消");
                    List<Media> results = mediaFromMetadata(root, url);
                    if (results.isEmpty()) throw new IOException("源站没有返回可下载的完整视频。\n" + cleanLog(response.getErr()));
                    for (Media media : results) media.twitterApi = api;
                    return results;
                } catch (Exception error) {
                    if (inspectCancelled || error instanceof InterruptedException) throw new InterruptedException("解析已取消");
                    String reason = cleanLog(error.getMessage() == null ? error.toString() : error.getMessage());
                    failures.append(api.isEmpty() ? "默认解析" : "X / " + api).append(":\n")
                            .append(reason.substring(0, Math.min(reason.length(), 3500))).append("\n\n");
                } finally { inspectProcess = null; cleanupRequest(processId); }
            }
            if (signedIn) failures.append("检测到 X 登录凭据；本次保留该登录状态，没有改用匿名访问。失效 Cookie 可在设置中清除或重新导入。\n");
            throw new IOException(failures.toString().trim());
        } finally { inspectProcess = null; RUNTIME_LOCK.readLock().unlock(); }
    }

    private boolean hasTwitterLoginCookie() throws IOException {
        return new AccountStore(app).hasTwitterLogin();
    }

    static JSONObject metadataFromResponse(String output, String stderr, int exitCode) throws Exception {
        try {
            JSONObject data = lastJsonObject(output == null ? "" : output);
            if (hasMedia(data)) return data;
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

    private List<Media> mediaFromMetadata(JSONObject root, String url) throws Exception {
        List<Media> results = new ArrayList<>();
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

    private Media parseMedia(JSONObject data, String source, int playlistIndex) throws Exception {
        String live = data.optString("live_status");
        if (data.optBoolean("is_live") || "is_live".equals(live) || "is_upcoming".equals(live)) {
            throw new Exception("暂不下载直播或尚未发布的视频；请在直播结束、完整回放可用后重试。");
        }
        Media media = new Media();
        media.url = source;
        media.playlistIndex = playlistIndex;
        media.id = data.optString("id", "video");
        media.title = data.optString("title", "未命名视频");
        media.uploader = data.optString("uploader", data.optString("channel", ""));
        media.duration = (long) data.optDouble("duration", 0);
        media.thumbnail = data.optString("thumbnail", "");
        media.width = Math.max(0,data.optInt("width",0)); media.height = Math.max(0,data.optInt("height",0));
        media.filesize = data.optLong("filesize",-1);
        if(media.filesize<=0){media.filesize=data.optLong("filesize_approx",-1);media.filesizeEstimated=media.filesize>0;}
        String host = new URI(source).getHost().toLowerCase(Locale.ROOT);
        media.platform = host.equals("x.com") || host.endsWith(".x.com") || host.contains("twitter.com") ? "X / Twitter"
                : host.contains("bilibili.com") || host.equals("b23.tv") ? "哔哩哔哩"
                : host.contains("douyin.com") ? "抖音 · 实验性" : host;
        media.qualities.add(new Quality("best", "最高可用画质", "bv*+ba/b"));
        TreeSet<Integer> heights = new TreeSet<>(Collections.reverseOrder());
        JSONArray formats = data.optJSONArray("formats");
        if (formats != null) for (int index = 0; index < formats.length(); index++) {
            JSONObject format = formats.optJSONObject(index);
            if (format == null || "none".equals(format.optString("vcodec"))) continue;
            int height = format.optInt("height", 0);
            if (height > 0) heights.add(height);
        }
        for (int height : heights) media.qualities.add(new Quality(String.valueOf(height), height + "p", 
                "bv*[height=" + height + "]+ba/b[height=" + height + "]"));
        return media;
    }

    public static String normalizeUrl(String text) { return CoreRules.normalizeUrl(text); }
    public static String cleanLog(String text) { return CoreRules.cleanLog(text); }
    public static String userMessage(Throwable error) { return CoreRules.userMessage(error); }
    public static String inspectionMessage(Throwable error) { return CoreRules.inspectionMessage(error); }

    public static final class Quality {
        public String id, label, selector;
        public Quality(String id, String label, String selector) { this.id = id; this.label = label; this.selector = selector; }
        public JSONObject toJson() throws Exception { return new JSONObject().put("id", id).put("label", label).put("selector", selector); }
        public static Quality fromJson(JSONObject json) { return new Quality(json.optString("id", "best"), json.optString("label", "最高可用画质"), json.optString("selector", "bv*+ba/b")); }
    }

    public static final class Media {
        public String url = "", id = "", title = "", uploader = "", platform = "";
        public String twitterApi = "", thumbnail = "";
        public int width, height;
        public long filesize=-1;
        public boolean filesizeEstimated;
        public long duration;
        public int playlistIndex;
        public List<Quality> qualities = new ArrayList<>();
        public JSONObject toJson() throws Exception {
            JSONArray choices = new JSONArray(); for (Quality quality : qualities) choices.put(quality.toJson());
            return new JSONObject().put("url", url).put("id", id).put("title", title).put("uploader", uploader)
                    .put("thumbnail",thumbnail).put("width",width).put("height",height).put("filesize",filesize).put("filesizeEstimated",filesizeEstimated)
                    .put("platform", platform).put("duration", duration).put("playlistIndex", playlistIndex).put("twitterApi", twitterApi).put("qualities", choices);
        }
        public static Media fromJson(JSONObject json) {
            Media media = new Media(); media.url = json.optString("url"); media.id = json.optString("id");
            media.title = json.optString("title"); media.uploader = json.optString("uploader"); media.platform = json.optString("platform");
            media.duration = json.optLong("duration"); media.playlistIndex = json.optInt("playlistIndex");
            media.twitterApi = json.optString("twitterApi", "");
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
