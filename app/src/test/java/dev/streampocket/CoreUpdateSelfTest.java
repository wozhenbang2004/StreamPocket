package dev.streampocket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline regression: compile with CoreUpdate.java and run without Android or JUnit. */
public final class CoreUpdateSelfTest {
    private static final String OLD = "2026.10.26", NEW = "2026.11.01";
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "probe".equals(args[0])) {
            if ("hang".equals(args[1])) Thread.sleep(30_000);
            if ("fail".equals(args[1])) System.exit(2);
            if ("overflow".equals(args[1])) { System.out.print(NEW + " ".repeat(9000) + "unexpected trailing output"); return; }
            System.out.println(args[1]); return;
        }
        if (args.length > 0 && "crash".equals(args[0])) { crashFixture(new File(args[1]), args[2]); return; }
        File directory = Files.createTempDirectory("streampocket-core-update-test-").toFile();
        try {
            releaseValidation();
            preparation(new File(directory, "download"));
            rollbackAndCommit(new File(directory, "transaction"));
            processDeath(new File(directory, "crash-switch"), "switch");
            processDeath(new File(directory, "crash-metadata"), "metadata");
            healthProbe();
            System.out.println("PASS: " + checks + " core-update checks (offline HTTP faults, validation, rollback, process-death recovery, bounded version probe)");
        } finally { delete(directory); }
    }

    private static void releaseValidation() throws Exception {
        byte[] bytes = zip(NEW, true);
        String digest = digest(bytes);
        reject(() -> new CoreUpdate.Release(NEW, url(NEW), 0, digest));
        reject(() -> new CoreUpdate.Release(NEW, url(NEW), CoreUpdate.MAX_BYTES + 1, digest));
        reject(() -> new CoreUpdate.Release(NEW, url(NEW), bytes.length, null));
        reject(() -> new CoreUpdate.Release(NEW, null, bytes.length, digest));
        reject(() -> new CoreUpdate.Release(null, url(NEW), bytes.length, digest));
        reject(() -> new CoreUpdate.Release(NEW, url(NEW), bytes.length, digest.substring(7)));
        reject(() -> new CoreUpdate.Release(NEW, "https://github.com/other/yt-dlp/releases/download/" + NEW + "/yt-dlp", bytes.length, digest));
        reject(() -> new CoreUpdate.Release(NEW, url(NEW) + "?token=untrusted", bytes.length, digest));
        reject(() -> new CoreUpdate.Release(NEW, url(OLD), bytes.length, digest));
        reject(() -> new CoreUpdate.Release("nightly", url("nightly"), bytes.length, digest));
        for (String unsafe : new String[]{"http://github.com/a", "https://github.com.evil.example/a", "https://github.com@evil.example/a", "https://github.com:444/a", "https://localhost/a"})
            check(!CoreUpdate.allowedRedirect(unsafe), "Unsafe redirect allowed: " + unsafe);
        check(CoreUpdate.allowedRedirect("https://release-assets.githubusercontent.com/asset?sig=fixture"), "Official signed asset redirect rejected");
    }

    private static void preparation(File directory) throws Exception {
        directory.mkdirs(); File binary = new File(directory, "yt-dlp"); byte[] old = zip(OLD, true); Files.write(binary.toPath(), old);
        CoreUpdate core = new CoreUpdate(binary); byte[] bytes = zip(NEW, true); CoreUpdate.Release release = release(bytes);
        FakeConnection valid = new FakeConnection(bytes);
        File prepared = core.prepare(release, "StreamPocket/test", address -> valid);
        check(Arrays.equals(bytes, Files.readAllBytes(prepared.toPath())), "Download bytes changed");
        check(valid.getConnectTimeout() == 15_000 && valid.getReadTimeout() == 20_000 && !valid.getInstanceFollowRedirects(), "HTTP operation is not bounded");
        check(valid.disconnected, "HTTP connection leaked");
        check(Arrays.equals(old, Files.readAllBytes(binary.toPath())), "Preparing changed the running core");
        core.discardDownload();

        byte[] damaged = bytes.clone(); damaged[damaged.length / 2] ^= 1;
        failedDownload(directory, core, release, new FakeConnection(damaged));
        failedDownload(directory, core, release, new FakeConnection(Arrays.copyOf(bytes, bytes.length - 8)));
        failedDownload(directory, core, release, new FakeConnection(Arrays.copyOf(bytes, bytes.length + 8)));
        FakeConnection wrongLength = new FakeConnection(bytes); wrongLength.length = bytes.length + 1;
        failedDownload(directory, core, release, wrongLength);
        FakeConnection timedOut = new FakeConnection(bytes); timedOut.failure = new SocketTimeoutException("fixture read timeout");
        failedDownload(directory, core, release, timedOut);
        FakeConnection unavailable = new FakeConnection(bytes); unavailable.code = 503;
        failedDownload(directory, core, release, unavailable);
        byte[] html = "<html>not a zipapp</html>".getBytes(StandardCharsets.UTF_8);
        failedDownload(directory, core, release(html), new FakeConnection(html));
        byte[] oldArchive = zip(OLD, true);
        failedDownload(directory, core, release(oldArchive), new FakeConnection(oldArchive));
        byte[] noMain = zip(NEW, false);
        failedDownload(directory, core, release(noMain), new FakeConnection(noMain));
        FakeConnection redirected = new FakeConnection(bytes); redirected.code = 302; redirected.location = "https://evil.example/core";
        final int[] requests = {0};
        reject(() -> core.prepare(release, "test", address -> { requests[0]++; return redirected; }));
        check(requests[0] == 1 && redirected.disconnected, "Untrusted redirect was requested");
        FakeConnection loop = new FakeConnection(bytes); loop.code = 302; loop.location = url(NEW);
        requests[0] = 0;
        reject(() -> core.prepare(release, "test", address -> { requests[0]++; return loop; }));
        check(requests[0] == 5, "Redirect loop was not bounded");
        check(Arrays.equals(old, Files.readAllBytes(binary.toPath())), "A failed download changed the installed core");
    }

    private static void failedDownload(File directory, CoreUpdate core, CoreUpdate.Release release, FakeConnection connection) throws Exception {
        reject(() -> core.prepare(release, "test", address -> connection));
        check(connection.disconnected, "Failed HTTP connection leaked");
        check(!new File(directory, "yt-dlp.download").exists(), "Failed preparation retained partial data");
    }

    private static void rollbackAndCommit(File directory) throws Exception {
        directory.mkdirs(); File binary = new File(directory, "yt-dlp"); byte[] old = zip(OLD, true); Files.write(binary.toPath(), old);
        CoreUpdate core = new CoreUpdate(binary); byte[] bytes = zip(NEW, true); CoreUpdate.Release release = release(bytes);
        MemoryMetadata metadata = new MemoryMetadata(OLD, "Original display name");
        prepare(core, release, bytes);
        reject(() -> core.activate(release, metadata, expected -> { check(expected.equals(CoreUpdate.archiveVersion(binary)), "Probe did not see switched file"); throw new IOException("broken interpreter"); }));
        check(Arrays.equals(old, Files.readAllBytes(binary.toPath())) && OLD.equals(metadata.version) && "Original display name".equals(metadata.name), "Health failure lost the exact prior core or metadata");
        check(!core.needsRecovery(), "Successful rollback left a pending journal");

        prepare(core, release, bytes); metadata.failWrites = 1;
        reject(() -> core.activate(release, metadata, expected -> {}));
        check(Arrays.equals(old, Files.readAllBytes(binary.toPath())) && OLD.equals(metadata.version), "Metadata failure did not roll back both stores");
        prepare(core, release, bytes); metadata.version = null; metadata.name = null;
        reject(() -> core.activate(release, metadata, expected -> { throw new IOException("fixture"); }));
        check(metadata.version == null && metadata.name == null, "Rollback invented metadata keys that were originally absent");

        prepare(core, release, bytes); metadata.failWrites = 2;
        reject(() -> core.activate(release, metadata, expected -> {}));
        check(core.needsRecovery() && Arrays.equals(old, Files.readAllBytes(binary.toPath())), "Failed metadata restoration did not retain a retryable journal and old file");
        new CoreUpdate(binary).recover(metadata);
        check(metadata.version == null && metadata.name == null && !core.needsRecovery(), "Repeated recovery was not idempotent");

        prepare(core, release, bytes);
        core.activate(release, metadata, expected -> check(NEW.equals(CoreUpdate.archiveVersion(binary)), "Health check saw old file"));
        check(NEW.equals(metadata.version) && NEW.equals(metadata.name) && NEW.equals(CoreUpdate.archiveVersion(binary)), "Successful update did not commit file and metadata");
        check(!core.needsRecovery() && !new File(directory, "yt-dlp.rollback").exists(), "Successful update left a rollback transaction");
        // A process killed after commit but before backup cleanup must keep the new core.
        Files.write(new File(directory, "yt-dlp.rollback").toPath(), old);
        new CoreUpdate(binary).recover(metadata);
        check(NEW.equals(CoreUpdate.archiveVersion(binary)) && NEW.equals(metadata.version), "Obsolete backup downgraded a committed update");
    }

    private static void processDeath(File directory, String phase) throws Exception {
        directory.mkdirs(); File binary = new File(directory, "yt-dlp"); byte[] old = zip(OLD, true); Files.write(binary.toPath(), old);
        DiskMetadata metadata = new DiskMetadata(directory); metadata.write(OLD, "Exact old name");
        Process child = javaProcess("crash", directory.getAbsolutePath(), phase).start();
        check(child.waitFor(8, TimeUnit.SECONDS), "Crash fixture hung");
        check(child.exitValue() == 42, "Crash fixture did not stop at intended transaction boundary");
        CoreUpdate restarted = new CoreUpdate(binary);
        check(restarted.needsRecovery() && NEW.equals(CoreUpdate.archiveVersion(binary)), "Crash did not leave an uncommitted replacement");
        restarted.recover(metadata);
        check(Arrays.equals(old, Files.readAllBytes(binary.toPath())) && OLD.equals(metadata.version()) && "Exact old name".equals(metadata.name()), "Restart failed to recover previous bytes and metadata after " + phase);
        check(!restarted.needsRecovery(), "Restart did not finish recovery");
    }

    private static void crashFixture(File directory, String phase) throws Exception {
        CoreUpdate core = new CoreUpdate(new File(directory, "yt-dlp")); byte[] bytes = zip(NEW, true);
        CoreUpdate.Release release = release(bytes); prepare(core, release, bytes);
        DiskMetadata disk = new DiskMetadata(directory);
        CoreUpdate.Metadata metadata = new CoreUpdate.Metadata() {
            public String version() { return disk.version(); }
            public String name() { return disk.name(); }
            public void write(String version, String name) throws IOException { disk.write(version, name); if ("metadata".equals(phase)) Runtime.getRuntime().halt(42); }
        };
        core.activate(release, metadata, expected -> { if ("switch".equals(phase)) Runtime.getRuntime().halt(42); });
        throw new AssertionError("Crash fixture reached commit");
    }

    private static void healthProbe() throws Exception {
        CoreUpdate.checkVersion(javaProcess("probe", NEW), NEW, 3000); checks++;
        reject(() -> CoreUpdate.checkVersion(javaProcess("probe", OLD), NEW, 3000));
        reject(() -> CoreUpdate.checkVersion(javaProcess("probe", "fail"), NEW, 3000));
        reject(() -> CoreUpdate.checkVersion(javaProcess("probe", "overflow"), NEW, 3000));
        long started = System.nanoTime();
        reject(() -> CoreUpdate.checkVersion(javaProcess("probe", "hang"), NEW, 150));
        check(System.nanoTime() - started < 3_000_000_000L, "Timed-out version probe blocked rollback");
    }

    private static ProcessBuilder javaProcess(String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(Arrays.asList(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(), "-cp", System.getProperty("java.class.path"), CoreUpdateSelfTest.class.getName()));
        command.addAll(Arrays.asList(args)); return new ProcessBuilder(command);
    }
    private static void prepare(CoreUpdate core, CoreUpdate.Release release, byte[] bytes) throws Exception { core.prepare(release, "test", address -> new FakeConnection(bytes)); }
    private static CoreUpdate.Release release(byte[] bytes) throws Exception { return new CoreUpdate.Release(NEW, url(NEW), bytes.length, digest(bytes)); }
    private static String url(String version) { return "https://github.com/yt-dlp/yt-dlp/releases/download/" + version + "/yt-dlp"; }
    private static String digest(byte[] bytes) throws Exception { StringBuilder result = new StringBuilder("sha256:"); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString(); }
    private static byte[] zip(String version, boolean main) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("#!/usr/bin/env python3\n".getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            if (main) { zip.putNextEntry(new ZipEntry("__main__.py")); zip.write("pass\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
            zip.putNextEntry(new ZipEntry("yt_dlp/version.py")); zip.write(("__version__ = '" + version + "'\n").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static class MemoryMetadata implements CoreUpdate.Metadata {
        String version, name; int failWrites;
        MemoryMetadata(String version, String name) { this.version = version; this.name = name; }
        public String version() { return version; }
        public String name() { return name; }
        public void write(String version, String name) throws IOException {
            this.version = version; this.name = name;
            if (failWrites > 0) { failWrites--; throw new IOException("fixture metadata write failure"); }
        }
    }
    private static final class DiskMetadata implements CoreUpdate.Metadata {
        private final File file;
        DiskMetadata(File directory) { file = new File(directory, "metadata.properties"); }
        private Properties read() { Properties p = new Properties(); try (InputStream input = new FileInputStream(file)) { p.load(input); } catch (IOException e) { throw new IllegalStateException(e); } return p; }
        public String version() { return read().getProperty("version"); }
        public String name() { return read().getProperty("name"); }
        public void write(String version, String name) throws IOException {
            Properties p = new Properties(); if (version != null) p.setProperty("version", version); if (name != null) p.setProperty("name", name);
            try (FileOutputStream output = new FileOutputStream(file)) { p.store(output, null); output.getFD().sync(); }
        }
    }
    private static final class FakeConnection extends HttpURLConnection {
        final byte[] bytes; int code = 200; long length = -1; boolean disconnected; String location; IOException failure;
        FakeConnection(byte[] bytes) throws IOException { super(new URL(url(NEW))); this.bytes = bytes; }
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
        public void connect() {}
        public int getResponseCode() { return code; }
        public long getContentLengthLong() { return length; }
        public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        public InputStream getInputStream() throws IOException { if (failure != null) throw failure; return new ByteArrayInputStream(bytes); }
    }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked operation) throws Exception { boolean rejected = false; try { operation.run(); } catch (IOException expected) { rejected = true; } check(rejected, "Expected a recoverable rejection"); }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
    private static void delete(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete(); }
}
