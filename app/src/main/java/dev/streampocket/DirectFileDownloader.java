package dev.streampocket;

import android.content.Context;
import android.os.Build;
import com.yausername.aria2c.Aria2c;
import com.yausername.youtubedl_android.YoutubeDL;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File downloads use the bundled aria2 engine; Java only inspects and validates its result. */
public final class DirectFileDownloader {
    private static final int TIMEOUT = 15000;
    private static final ConcurrentHashMap<String, Attempt> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Attempt> DIRECTORIES = new ConcurrentHashMap<>();
    private static final Pattern READOUT = Pattern.compile("\\[#[0-9a-f]+\\s+(\\d+)B?/(\\d+)B?\\((?:[0-9.]+)%\\).*?CN:(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEED = Pattern.compile("\\bDL:(\\d+)B?", Pattern.CASE_INSENSITIVE);
    private DirectFileDownloader() {}

    public static DirectFileSpec inspect(Context context, String url, BooleanSupplier cancelled) throws Exception {
        Attempt attempt = new Attempt("inspection-" + UUID.randomUUID(), cancelled);
        Thread watcher = watch(attempt);
        try { return probe(context, url, attempt, DirectFileDownloader::open, true).spec; }
        finally { attempt.finished = true; attempt.disconnect(); watcher.interrupt(); }
    }

    public static boolean stop(String attemptId) {
        Attempt attempt = ACTIVE.get(attemptId);
        if (attempt == null) return false;
        attempt.stop(); return true;
    }

    public static File download(Context context, String url, DirectFileSpec selected, File workingDir,
                                String attemptId, BooleanSupplier cancelled, Consumer<JSONObject> progress) throws Exception {
        if (selected == null || !DirectFileRules.normalizeUrl(url).equals(selected.url))
            throw new IOException("文件链接与任务信息不一致，请重新检查链接");
        if (attemptId == null || attemptId.isEmpty()) throw new IllegalArgumentException("Missing attempt identity");
        File directory = new File(workingDir, "direct-http").getCanonicalFile();
        if (!workingDir.getCanonicalFile().equals(directory.getParentFile())) throw new IOException("文件缓存目录不在当前任务内");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建文件下载目录");
        Attempt attempt = new Attempt(attemptId, cancelled);
        Attempt old = DIRECTORIES.putIfAbsent(directory.getPath(), attempt);
        if (old != null) {
            if (old.finished && !old.running()) {
                DIRECTORIES.remove(directory.getPath(), old); ACTIVE.remove(old.id, old);
            }
            if (DIRECTORIES.putIfAbsent(directory.getPath(), attempt) != null)
                throw new IOException("上一次文件下载尚未退出，已有数据已保留，请稍后重试");
        }
        if (ACTIVE.putIfAbsent(attemptId, attempt) != null) {
            DIRECTORIES.remove(directory.getPath(), attempt);
            throw new IOException("这次下载已在执行");
        }
        Thread watcher = watch(attempt);
        try {
            attempt.check();
            File completed = completedFile(directory, selected, attempt::cancelled);
            if (completed != null) return completed;
            File verified = recoverIntegrity(directory, selected, attempt, progress);
            if (verified != null) return verified;
            File payload = new File(directory, "payload"), control = new File(directory, "payload.aria2");
            File resume = new File(directory, "resume.json");
            boolean partial = payload.exists() || control.exists();
            if (partial && (!control.isFile() || !resume.isFile()))
                throw new IOException("发现尚未确认完成的文件数据，原件已保留。请新建任务重新下载");

            emit(progress, -1, selected.size, -1, -1, "正在检查文件与断点");
            Probe current = probe(context, selected.url, attempt, DirectFileDownloader::open, true);
            if (partial) {
                DirectFileSpec previous = DirectFileSpec.fromJson(readJson(resume));
                requireSameIdentity(previous, current.spec);
                if (!current.spec.ranges) throw new IOException("网站暂不支持安全续传，原有断点已保留。可新建任务重新下载");
            } else writeJson(resume, current.spec.toJson());
            attempt.check();
            if (current.spec.size == 0) {
                if (!payload.createNewFile()) throw new IOException("无法创建空文件");
                recordTransferComplete(directory, current.spec, payload, attempt.id, "empty-response");
                return recoverIntegrity(directory, selected, attempt, progress);
            }

            YoutubeDL.getInstance().init(context.getApplicationContext());
            Aria2c.getInstance().init(context.getApplicationContext());
            File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File binary = new File(nativeDir, "libaria2c.so");
            File packages = new File(context.getNoBackupFilesDir(), "youtubedl-android/packages");
            File certificate = new File(packages, "python/usr/etc/tls/cert.pem");
            if (!binary.isFile() || !certificate.isFile()) throw new IOException("内置文件下载组件或安全证书缺失，请更新应用后重试");
            int connections = DownloadTuning.connections(context.getSharedPreferences("config", Context.MODE_PRIVATE)
                    .getInt(DownloadTuning.PREFERENCE, DownloadTuning.DEFAULT_CONNECTIONS));
            String proxy = proxy(context);
            List<String> args = arguments(binary, directory, certificate, current.url, current.spec, connections, proxy);
            args.add(1, "--stop-with-process=" + android.os.Process.myPid());
            ProcessBuilder builder = new ProcessBuilder(args).directory(directory).redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("LD_LIBRARY_PATH", new File(packages, "python/usr/lib") + ":"
                    + new File(packages, "ffmpeg/usr/lib") + ":" + new File(packages, "aria2c/usr/lib"));
            environment.put("SSL_CERT_FILE", certificate.getAbsolutePath());
            environment.put("HOME", context.getFilesDir().getAbsolutePath());
            environment.put("TMPDIR", context.getCacheDir().getAbsolutePath());
            environment.put("PATH", nativeDir + ":" + environment.getOrDefault("PATH", ""));
            // Do not inherit a shell/user proxy different from the app's explicit configuration.
            for (String key : new String[]{"http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "no_proxy", "NO_PROXY"})
                environment.remove(key);
            Process process = builder.start();
            synchronized (attempt) {
                attempt.process = process;
                if (attempt.stopped || cancelled.getAsBoolean()) process.destroy();
            }
            emit(progress, -1, current.spec.size, -1, -1, "正在下载文件");
            StringBuilder diagnostics = new StringBuilder();
            Thread reader = new Thread(() -> readOutput(process, attempt, progress, diagnostics), "direct-file-output");
            reader.setDaemon(true); reader.start();
            int exit;
            try {
                for (;;) {
                    // A completed native process is evidence even if Stop arrived in the same tick.
                    try { exit = process.exitValue(); break; }
                    catch (IllegalThreadStateException running) {
                        if (attempt.cancelled()) {
                            attempt.stop(); terminate(attempt);
                            // A process that completed before the signal may still have exited successfully.
                            exit = process.exitValue(); break;
                        }
                        Thread.sleep(80);
                    }
                }
                if (exit == 0) {
                    // No cancellation check or interruptible join may intervene between observing
                    // successful native exit and recording the still-private verification checkpoint.
                    recordTransferComplete(directory, current.spec, payload, attempt.id, "aria2-exit-0");
                }
            } finally {
                if (attempt.cancelled()) attempt.stop();
                if (attempt.cancelled() || attempt.running()) terminate(attempt);
                reader.join(1000);
            }
            if (exit != 0) {
                attempt.check();
                String detail;
                synchronized (diagnostics) { detail = diagnostics.toString(); }
                throw new IOException("文件下载未完成（内核退出码 " + exit + "），已有数据已保留\n" + CoreRules.cleanLog(detail));
            }
            return recoverIntegrity(directory, selected, attempt, progress);
        } finally {
            attempt.finished = true; attempt.disconnect(); watcher.interrupt();
            if (attempt.running()) terminate(attempt);
            // A failed stop cannot grant a second attempt access to the same sparse file.
            if (!attempt.running()) {
                ACTIVE.remove(attemptId, attempt); DIRECTORIES.remove(directory.getPath(), attempt);
            }
        }
    }

    static void requireSameIdentity(DirectFileSpec previous, DirectFileSpec current) throws IOException {
        if (!previous.url.equals(current.url) || !DirectFileRules.sameIdentity(previous.etag, previous.lastModified,
                previous.size, current.etag, current.lastModified, current.size))
            throw new IOException("源文件已变化或缺少可核对的续传标识，原有断点已保留。请新建任务重新下载");
    }

    static List<String> arguments(File binary, File directory, File certificate, String url,
                                   DirectFileSpec spec, int connections, String proxy) {
        List<String> args = new ArrayList<>(Arrays.asList(binary.getAbsolutePath(), "--no-conf=true", "--no-netrc=true",
                "--dir=" + directory.getAbsolutePath(), "--out=payload", "--auto-file-renaming=false",
                "--allow-overwrite=false", "--continue=true", "--always-resume=true", "--file-allocation=none",
                "--auto-save-interval=1", "--max-tries=3", "--retry-wait=2", "--connect-timeout=15", "--timeout=15",
                "--max-concurrent-downloads=1", "--max-connection-per-server=" + (spec.ranges ? connections : 1),
                "--split=" + (spec.ranges ? connections : 1), "--min-split-size=1M", "--check-certificate=true",
                "--ca-certificate=" + certificate.getAbsolutePath(), "--http-accept-gzip=false",
                "--header=Accept-Encoding: identity", "--follow-torrent=false", "--follow-metalink=false",
                "--enable-color=false", "--human-readable=false", "--summary-interval=1",
                "--truncate-console-readout=false", "--show-console-readout=true", "--console-log-level=notice",
                "--download-result=hide", "--user-agent=StreamPocket file download"));
        String etag = DirectFileRules.strongEtag(spec.etag);
        if (!etag.isEmpty()) args.add("--header=If-Match: " + etag);
        else if (!spec.lastModified.isEmpty()) args.add("--header=If-Unmodified-Since: " + spec.lastModified);
        if (!proxy.isEmpty()) args.add("--all-proxy=" + proxy);
        args.add("--"); args.add(DirectFileRules.normalizeUrl(url));
        return args;
    }

    interface ConnectionFactory { HttpURLConnection connect(URL url, String proxy) throws Exception; }
    static final class Probe {
        final DirectFileSpec spec;
        final String url;
        Probe(DirectFileSpec spec, String url) { this.spec = spec; this.url = url; }
    }

    /** A bounded GET probe is required: Accept-Ranges alone does not demonstrate safe ranges. */
    static Probe probe(Context context, String raw, Attempt attempt, ConnectionFactory factory, boolean resolveDns) throws Exception {
        String original = DirectFileRules.normalizeUrl(raw), current = original;
        String selectedProxy = context == null ? "" : proxy(context);
        // HEAD is advisory; the actual GET response supplies authoritative file metadata.
        HttpURLConnection head = null;
        int headStatus = -1;
        try {
            head = connection(current, "HEAD", selectedProxy, attempt, factory, resolveDns);
            headStatus = head.getResponseCode();
        } catch (IOException unavailable) {
            attempt.check(); // A server may support GET while mishandling HEAD.
        } finally { if (head != null) head.disconnect(); attempt.connection = null; }
        // A rate limit is a reason to wait, not to issue a second method immediately.
        if (headStatus == 429) throw statusError(headStatus);

        for (int redirect = 0; redirect <= 8; redirect++) {
            attempt.check();
            HttpURLConnection response = connection(current, "GET", selectedProxy, attempt, factory, resolveDns);
            try {
                response.setRequestProperty("Range", "bytes=0-0");
                int status = response.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = response.getHeaderField("Location");
                    if (location == null || location.length() > 16384) throw new IOException("网站重定向地址无效");
                    String next = DirectFileRules.normalizeUrl(new URI(current).resolve(location).toString());
                    if (current.startsWith("https:") && next.startsWith("http:")) throw new IOException("文件链接重定向到不安全的 HTTP 地址，未开始下载");
                    current = next;
                    continue;
                }
                if (status != 200 && status != 206 && status != 416) throw statusError(status);
                String type = DirectFileRules.mime(response.getHeaderField("Content-Type"));
                if (DirectFileRules.htmlMime(type)) throw new IOException("此链接返回的是网页，未将登录页或错误页保存为文件");
                String encoding = DirectFileRules.header(response.getHeaderField("Content-Encoding"));
                if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity")) throw new IOException("网站返回了压缩传输内容，暂无法安全检查文件范围");
                long size = DirectFileRules.length(response.getHeaderField("Content-Length"));
                boolean ranges = status == 206;
                if (ranges) size = DirectFileRules.rangeTotal(status, response.getHeaderField("Content-Range"), 0, size);
                if (status == 416) {
                    if (!"bytes */0".equals(DirectFileRules.header(response.getHeaderField("Content-Range")))) throw statusError(status);
                    size = 0; ranges = false;
                } else if (status == 200) {
                    byte[] prefix = new byte[1024]; int count;
                    try (InputStream in = response.getInputStream()) { count = in.read(prefix); }
                    if (count > 0 && DirectFileRules.looksHtml(prefix, count)) throw new IOException("此链接返回的是网页，未保存为文件");
                }
                String name = DirectFileRules.filename(current, response.getHeaderField("Content-Disposition"));
                DirectFileSpec spec = new DirectFileSpec(original, name, type, size,
                        response.getHeaderField("ETag"), response.getHeaderField("Last-Modified"), ranges);
                attempt.check(); return new Probe(spec, current);
            } finally { response.disconnect(); attempt.connection = null; }
        }
        throw new IOException("网站重定向次数过多，未开始下载");
    }

    private static HttpURLConnection connection(String raw, String method, String proxy, Attempt attempt,
                                                ConnectionFactory factory, boolean resolveDns) throws Exception {
        String url = DirectFileRules.normalizeUrl(raw);
        if (resolveDns) for (InetAddress address : InetAddress.getAllByName(new URI(url).getHost()))
            if (!DirectFileRules.publicAddress(address)) throw new IOException("文件链接指向本地或私有网络地址，未开始下载");
        HttpURLConnection connection = factory.connect(new URL(url), proxy);
        attempt.connection = connection; attempt.check();
        connection.setConnectTimeout(TIMEOUT); connection.setReadTimeout(TIMEOUT);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "StreamPocket file download");
        return connection;
    }

    private static HttpURLConnection open(URL url, String proxy) throws Exception {
        if (proxy.isEmpty()) return (HttpURLConnection) url.openConnection();
        URI server = new URI(proxy);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(server.getHost(), server.getPort() < 0 ? 8080 : server.getPort())));
        if (server.getRawUserInfo() != null) {
            String basic = android.util.Base64.encodeToString(server.getUserInfo().getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            connection.setRequestProperty("Proxy-Authorization", "Basic " + basic);
        }
        return connection;
    }

    private static String proxy(Context context) throws IOException {
        String value = context.getSharedPreferences("config", Context.MODE_PRIVATE).getString("proxy", "");
        if (value == null || value.trim().isEmpty()) return "";
        value = value.trim();
        try {
            URI uri = new URI(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() == 0
                    || uri.getPort() > 65535 || value.matches("(?s).*[\\r\\n].*")) throw new Exception();
            return value;
        } catch (Exception invalid) { throw new IOException("普通文件下载暂只支持 HTTP 代理，请调整应用内的网络设置后重试"); }
    }

    private static IOException statusError(int status) {
        return new IOException("文件链接返回 HTTP " + status + "，未将错误响应保存为文件");
    }

    static final class Attempt {
        final String id;
        final BooleanSupplier external;
        volatile HttpURLConnection connection;
        volatile Process process;
        volatile boolean stopped, finished;
        Attempt(String id, BooleanSupplier external) { this.id = id; this.external = external; }
        boolean cancelled() { return stopped || external.getAsBoolean() || Thread.currentThread().isInterrupted(); }
        void check() throws InterruptedIOException { if (cancelled()) throw new InterruptedIOException("文件下载已停止，已有数据已保留"); }
        void disconnect() { HttpURLConnection active = connection; if (active != null) active.disconnect(); }
        synchronized void stop() { stopped = true; disconnect(); if (process != null) process.destroy(); }
        boolean running() { Process active = process; if (active == null) return false; try { active.exitValue(); return false; } catch (IllegalThreadStateException running) { return true; } }
    }

    private static Thread watch(Attempt attempt) {
        Thread watcher = new Thread(() -> {
            try { while (!attempt.finished) { if (attempt.cancelled()) { attempt.stop(); return; } Thread.sleep(100); } }
            catch (InterruptedException done) { /* Owner finished. */ }
        }, "direct-file-cancel");
        watcher.setDaemon(true); watcher.start(); return watcher;
    }

    private static void terminate(Attempt attempt) throws IOException {
        Process process = attempt.process;
        if (process == null || !attempt.running()) return;
        process.destroy();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (attempt.running() && System.nanoTime() < deadline) java.util.concurrent.locks.LockSupport.parkNanos(25_000_000);
        if (attempt.running() && Build.VERSION.SDK_INT >= 26) process.destroyForcibly();
        deadline = System.nanoTime() + 3_000_000_000L;
        while (attempt.running() && System.nanoTime() < deadline) java.util.concurrent.locks.LockSupport.parkNanos(25_000_000);
        if (attempt.running()) throw new IOException("下载进程尚未确认退出，原有数据已保留，暂不允许重复下载");
    }

    private static void readOutput(Process process, Attempt attempt, Consumer<JSONObject> callback, StringBuilder diagnostics) {
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder(); int value;
            while ((value = reader.read()) != -1) {
                if (value == '\n' || value == '\r') {
                    if (line.length() > 0) {
                        String text = line.toString(); line.setLength(0);
                        if (!attempt.cancelled()) parseProgress(text, callback);
                        synchronized (diagnostics) {
                            diagnostics.append(CoreRules.cleanLog(text)).append('\n');
                            if (diagnostics.length() > 6000) diagnostics.delete(0, diagnostics.length() - 4000);
                        }
                    }
                } else if (line.length() < 8192) line.append((char) value);
            }
        } catch (Exception ignored) { /* Exit status, result validation and the owner determine completion. */ }
    }

    static void parseProgress(String line, Consumer<JSONObject> callback) {
        Matcher match = READOUT.matcher(line);
        if (!match.find()) return;
        try {
            Matcher speed = SPEED.matcher(line);
            emit(callback, Long.parseLong(match.group(1)), Long.parseLong(match.group(2)),
                    speed.find() ? Long.parseLong(speed.group(1)) : -1,
                    Integer.parseInt(match.group(3)), "正在下载文件");
        } catch (NumberFormatException ignored) { /* A malformed native readout is not a measurement. */ }
    }

    private static void emit(Consumer<JSONObject> callback, long done, long total, long speed, int connections, String label) {
        if (callback == null) return;
        try {
            double percent = done >= 0 && total > 0 ? Math.min(99, 100.0 * done / total) : -1;
            JSONObject patch = new JSONObject().put("status", "downloading").put("phase", label.equals("正在检查文件与断点") ? "preparing" : "media")
                    .put("stageLabel", label).put("detail", label).put("progress", percent).put("stageProgress", percent)
                    .put("totalBytes", total).put("totalBytesEstimated", false)
                    .put("downloadedBytesEstimated", false).put("progressEstimated", false)
                    .put("transferComplete", false).put("speedBytesPerSecond", speed).put("etaSeconds", -1)
                    .put("activeConnections", connections).put("transferMode", "direct-http");
            // Preparation and process start do not measure bytes; retain the previous attempt's counter.
            if (done >= 0) patch.put("downloadedBytes", done);
            callback.accept(patch);
        } catch (Exception ignored) { /* A UI/persistence callback cannot kill the native-output reader. */ }
    }

    static File completedFile(File directory, DirectFileSpec spec) throws Exception {
        return completedFile(directory, spec, () -> false);
    }

    private static File completedFile(File directory, DirectFileSpec spec, BooleanSupplier cancelled) throws Exception {
        File marker = new File(directory, "complete.json"), payload = new File(directory, "payload");
        if (!marker.isFile()) return null;
        JSONObject saved = readJson(marker);
        if (!fingerprint(spec.url).equals(saved.optString("url")) || !payload.isFile()
                || payload.length() != saved.optLong("bytes", -1)
                || !digest(payload, cancelled).equals(saved.optString("sha256")))
            throw new IOException("完整缓存校验失败，文件已保留，未重新发布");
        rejectHtml(payload);
        return payload;
    }

    /** Only call after this attempt confirms native exit-0 (or an actual empty HTTP response). */
    static void recordTransferComplete(File directory, DirectFileSpec observed, File payload,
                                       String attemptId, String confirmation) throws Exception {
        if (!payload.isFile() || new File(directory, "payload.aria2").exists()
                || (observed.size >= 0 && payload.length() != observed.size))
            throw new IOException("下载内核尚未确认文件完整，已有数据已保留");
        if (!(confirmation.equals("aria2-exit-0") || (confirmation.equals("empty-response")
                && observed.size == 0 && payload.length() == 0))) throw new IOException("文件完成证明无效");
        long bytes = payload.length();
        JSONObject proof = new JSONObject().put("schema", 1).put("confirmation", confirmation)
                .put("attempt", attemptId).put("source", observed.toJson()).put("bytes", bytes)
                .put("recordSha256", integrityRecordHash(observed, bytes, attemptId, confirmation));
        File destination = new File(directory, "integrity-pending.json");
        File staged = new File(directory, "integrity-pending.json.tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                output.write(proof.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            if (!staged.renameTo(destination)) throw new IOException("无法提交待校验记录");
        } catch (Exception failure) {
            // A fully written staging record can be read and checked on retry after a rename/crash failure.
            // Partial or missing records are never enough to bless a bare payload as complete.
            throw new IOException("传输已结束，但待校验记录未能提交；原文件已保留，恢复存储后可重试核对", failure);
        }
    }

    /** Resume verification, without another network request or native transfer. */
    static File recoverIntegrity(File directory, DirectFileSpec selected, Attempt attempt,
                                 Consumer<JSONObject> progress) throws Exception {
        File proof = new File(directory, "integrity-pending.json");
        File staged = new File(directory, "integrity-pending.json.tmp");
        if (!proof.exists() && !staged.exists()) return null;
        File payload = new File(directory, "payload");
        long bytes;
        try {
            File record = proof.exists() ? proof : staged;
            if (!record.isFile()) throw new IOException("待校验记录不是文件");
            JSONObject state = readJson(record);
            DirectFileSpec observed = DirectFileSpec.fromJson(state.optJSONObject("source"));
            bytes = state.optLong("bytes", -1);
            String confirmation = state.optString("confirmation"), owner = state.optString("attempt");
            if (state.optInt("schema") != 1 || owner.isEmpty() || !observed.url.equals(selected.url) || bytes < 0
                    || !(confirmation.equals("aria2-exit-0") || (confirmation.equals("empty-response") && bytes == 0 && observed.size == 0))
                    || !integrityRecordHash(observed, bytes, owner, confirmation).equals(state.optString("recordSha256"))
                    || !payload.isFile() || new File(directory, "payload.aria2").exists()
                    || payload.length() != bytes || (observed.size >= 0 && bytes != observed.size))
                throw new IOException("文件身份、大小或完成证明不一致");
        } catch (Exception invalid) {
            throw new IOException("待校验记录或文件大小不匹配，原文件已保留，未重新下载或发布", invalid);
        }
        emitIntegrity(progress, bytes);
        attempt.check();
        rejectHtml(payload);
        markCompleted(directory, selected, payload, attempt);
        // complete.json is already durable; leftover pending records cannot invalidate that result.
        proof.delete(); staged.delete();
        return payload;
    }

    private static String integrityRecordHash(DirectFileSpec spec, long bytes, String owner, String confirmation) throws Exception {
        return fingerprint(spec.url + "\n" + spec.name + "\n" + spec.mime + "\n" + spec.size + "\n" + spec.etag
                + "\n" + spec.lastModified + "\n" + spec.ranges + "\n" + bytes + "\n" + owner + "\n" + confirmation);
    }

    private static void emitIntegrity(Consumer<JSONObject> progress, long bytes) {
        if (progress == null) return;
        try {
            progress.accept(new JSONObject().put("status", "downloading").put("phase", "processing")
                    .put("stageLabel", "正在校验文件").put("detail", "文件传输已完成，正在校验完整缓存")
                    .put("transferComplete", true).put("progress", -1).put("stageProgress", -1)
                    .put("downloadedBytes", bytes).put("totalBytes", bytes).put("totalBytesEstimated", false)
                    .put("speedBytesPerSecond", -1).put("etaSeconds", -1).put("activeConnections", 0));
        } catch (Exception ignored) { /* A delayed UI update is not a reason to lose a durable checkpoint. */ }
    }

    static void markCompleted(File directory, DirectFileSpec selected, File payload, Attempt attempt) throws Exception {
        attempt.check();
        String sha = digest(payload, attempt::cancelled);
        writeJson(new File(directory, "complete.json"), new JSONObject().put("url", fingerprint(selected.url))
                .put("bytes", payload.length()).put("sha256", sha));
    }

    private static void rejectHtml(File payload) throws Exception {
        byte[] prefix = new byte[4096];
        try (InputStream input = new FileInputStream(payload)) {
            int count = input.read(prefix);
            if (count > 0 && DirectFileRules.looksHtml(prefix, count)) throw new IOException("下载结果是网页，未将登录页或错误页发布为文件");
        }
    }

    static String digest(File file, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] block = new byte[131072]; int count;
        try (InputStream input = new FileInputStream(file)) {
            while ((count = input.read(block)) != -1) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("文件校验已停止");
                digest.update(block, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String fingerprint(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255)); return out.toString(); }

    private static JSONObject readJson(File file) throws Exception {
        if (file.length() > 65536) throw new IOException("文件断点记录无效，原有数据已保留");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) != -1) { if (bytes.size() + count > 65536) throw new IOException("文件记录过大"); bytes.write(block, 0, count); }
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }

    private static void writeJson(File destination, JSONObject value) throws Exception {
        File staged = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                output.write(value.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            if (!staged.renameTo(destination)) throw new IOException("无法保存文件断点记录");
        } finally { staged.delete(); }
    }
}
