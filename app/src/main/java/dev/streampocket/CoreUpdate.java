package dev.streampocket;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A verified yt-dlp zipapp and one recoverable replacement, independent of Android. */
final class CoreUpdate {
    static final String API = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final int CONNECT_TIMEOUT_MS = 15_000, READ_TIMEOUT_MS = 20_000;
    private final File binary, backup, journal, journalTemp, download;

    CoreUpdate(File binary) {
        this.binary = binary;
        File directory = binary.getParentFile();
        backup = new File(directory, "yt-dlp.rollback");
        journal = new File(directory, "yt-dlp.update-state");
        journalTemp = new File(directory, "yt-dlp.update-state.tmp");
        download = new File(directory, "yt-dlp.download");
    }

    static final class Release {
        final String version, url, sha256;
        final long size;
        Release(String version, String url, long size, String digest) throws IOException {
            this.version = version; this.url = url; this.size = size;
            sha256 = digest == null ? "" : digest.toLowerCase(Locale.ROOT).replaceFirst("^sha256:", "");
            if (url == null) throw new IOException("下载支持的官方更新信息无效");
            try {
                URI uri = new URI(url);
                if (version == null || !version.matches("20\\d{2}\\.\\d{2}\\.\\d{2}") || size <= 0 || size > MAX_BYTES
                        || digest == null || !digest.startsWith("sha256:") || !sha256.matches("[0-9a-f]{64}")
                        || !"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                        || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                        || !("/yt-dlp/yt-dlp/releases/download/" + version + "/yt-dlp").equals(uri.getRawPath()))
                    throw new IOException("下载支持的官方更新信息无效");
            } catch (IllegalArgumentException | java.net.URISyntaxException invalid) {
                throw new IOException("下载支持的官方更新信息无效", invalid);
            }
        }
    }

    interface Metadata {
        String version();
        String name();
        void write(String version, String name) throws IOException;
    }
    interface HealthCheck { void check(String expectedVersion) throws Exception; }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }

    File prepare(Release release, String userAgent) throws Exception {
        return prepare(release, userAgent, url -> (HttpURLConnection) url.openConnection());
    }

    /** Network and archive preparation must run before taking the runtime write lock. */
    File prepare(Release release, String userAgent, Connections connections) throws Exception {
        File directory = binary.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备下载支持目录");
        boolean ready = false;
        try {
            String url = release.url;
            for (int redirects = 0; redirects < 5; redirects++) {
                if (!allowedRedirect(url)) throw new IOException("下载支持的重定向来源无效");
                interrupted();
                HttpURLConnection connection = connections.open(new URL(url));
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept-Encoding", "identity");
                try {
                    int code = connection.getResponseCode();
                    if (code >= 300 && code <= 399) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("下载支持缺少重定向地址");
                        url = new URL(new URL(url), location).toString();
                        continue;
                    }
                    if (code != 200) throw new IOException("下载支持更新暂不可用（HTTP " + code + "）");
                    long length = connection.getContentLengthLong();
                    if (length >= 0 && length != release.size) throw new IOException("下载支持文件大小不匹配");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long count = 0;
                    try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(download)) {
                        byte[] buffer = new byte[32768]; int read;
                        while ((read = input.read(buffer)) != -1) {
                            interrupted(); count += read;
                            if (count > release.size || count > MAX_BYTES) throw new IOException("下载支持文件过大");
                            output.write(buffer, 0, read); digest.update(buffer, 0, read);
                        }
                        if (count != release.size) throw new IOException("下载支持文件不完整");
                        if (!release.sha256.equals(hex(digest.digest()))) throw new IOException("下载支持文件校验失败");
                        output.getFD().sync();
                    }
                    if (!release.version.equals(archiveVersion(download))) throw new IOException("下载支持的文件版本不匹配");
                    ready = true;
                    return download;
                } finally { connection.disconnect(); }
            }
            throw new IOException("下载支持的重定向次数过多");
        } finally { if (!ready) download.delete(); }
    }

    static boolean allowedRedirect(String value) {
        try {
            URI uri = new URI(value); String host = uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("github.com".equals(host) || "release-assets.githubusercontent.com".equals(host)
                    || "objects.githubusercontent.com".equals(host));
        } catch (Exception invalid) { return false; }
    }

    static String archiveVersion(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry main = zip.getEntry("__main__.py"), version = zip.getEntry("yt_dlp/version.py");
            if (main == null || main.isDirectory() || version == null || version.isDirectory())
                throw new IOException("下载支持文件不是可执行的解析器");
            String source;
            try (InputStream input = zip.getInputStream(version)) {
                source = new String(readBounded(input, 32768), StandardCharsets.UTF_8);
            }
            Matcher match = Pattern.compile("(?m)^__version__\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(source);
            if (!match.find()) throw new IOException("下载支持文件缺少版本");
            return match.group(1);
        }
    }

    boolean needsRecovery() { return journal.exists(); }
    void discardDownload() { download.delete(); }

    /** Caller holds the runtime write lock; the old file is retained until health and metadata commit. */
    void activate(Release release, Metadata metadata, HealthCheck health) throws Exception {
        if (needsRecovery()) throw new IOException("上次下载支持更新尚未恢复");
        if (!binary.isFile() || !download.isFile()) throw new IOException("下载支持文件尚未准备好");
        deleteIfPresent(backup); deleteIfPresent(journalTemp);
        Properties previous = new Properties();
        previous.setProperty("schema", "1");
        String oldVersion = metadata.version(), oldName = metadata.name();
        if (oldVersion != null) previous.setProperty("version", oldVersion);
        if (oldName != null) previous.setProperty("name", oldName);
        try (FileOutputStream output = new FileOutputStream(journalTemp)) {
            previous.store(output, null); output.getFD().sync();
        }
        if (journalTemp.length() > 16384) throw new IOException("下载支持的原版本记录无效");
        if (!journalTemp.renameTo(journal)) throw new IOException("无法保存下载支持的恢复记录");
        try {
            interrupted();
            if (!binary.renameTo(backup)) throw new IOException("无法保留当前下载支持");
            if (!download.renameTo(binary)) throw new IOException("无法切换下载支持");
            health.check(release.version);
            metadata.write(release.version, release.version);
            // Removing the journal commits the transaction. A leftover backup alone is obsolete.
            if (!journal.delete()) throw new IOException("无法完成下载支持更新");
        } catch (Exception | Error failure) {
            try { recover(metadata); }
            catch (Exception | Error recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            throw failure;
        }
        backup.delete();
    }

    /** Run before wrapper initialization, and again if a rollback could not finish in this process. */
    void recover(Metadata metadata) throws IOException {
        if (!journal.exists()) { deleteIfPresent(backup); deleteIfPresent(journalTemp); return; }
        Properties previous = new Properties();
        try (InputStream input = new FileInputStream(journal)) {
            previous.load(new java.io.ByteArrayInputStream(readBounded(input, 16384)));
        }
        if (!"1".equals(previous.getProperty("schema"))) throw new IOException("下载支持的恢复记录无效");
        if (backup.exists() && !backup.renameTo(binary)) throw new IOException("无法恢复上一个下载支持版本");
        if (!binary.isFile()) throw new IOException("上一个下载支持版本暂时无法恢复");
        // A missing backup with a journal means the old file was never moved, or was already restored.
        metadata.write(previous.getProperty("version"), previous.getProperty("name"));
        if (!journal.delete()) throw new IOException("无法完成下载支持恢复");
    }

    /** The version probe uses the same interpreter as extraction, with a bounded wait and output. */
    static void checkVersion(ProcessBuilder builder, String expected, long timeoutMs) throws Exception {
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
        }, "core-update-version");
        reader.setDaemon(true); reader.start();
        try {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (true) {
                try { process.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (System.nanoTime() >= deadline) throw new IOException("下载支持启动检查超时");
                    Thread.sleep(40);
                }
            }
            reader.join(500);
            if (reader.isAlive()) throw new IOException("下载支持启动检查的输出未结束");
            String output; boolean saturated;
            synchronized (captured) { output = captured.toString(StandardCharsets.UTF_8.name()).trim(); saturated = captured.size() >= 8192; }
            if (process.exitValue() != 0 || saturated || !expected.equals(output))
                throw new IOException("新版下载支持未通过启动检查");
        } finally {
            process.destroy();
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("下载支持文件内容过大");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("下载支持更新已取消");
    }
    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("无法清理下载支持的临时文件");
    }
}
