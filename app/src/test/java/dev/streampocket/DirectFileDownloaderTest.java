package dev.streampocket;

import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Response fixtures test metadata and saved-file recovery; no native execution is claimed. */
public final class DirectFileDownloaderTest {
    private static final String URL = "https://files.example.com/get?sig=a%2Bb";

    @Test public void headUnsupportedFallsBackToActualGetAndExtendedName() throws Exception {
        ArrayDeque<FakeConnection> responses = new ArrayDeque<>();
        responses.add(response(405, Collections.emptyMap(), new byte[0]));
        responses.add(response(206, Map.of("Content-Type", "application/pdf", "Content-Length", "1",
                "Content-Range", "bytes 0-0/123", "Content-Disposition", "attachment; filename*=UTF-8''%E6%96%87%E4%BB%B6.pdf",
                "ETag", "\"version-1\""), new byte[]{'%'}));
        DirectFileDownloader.Probe probe = probe(responses);
        assertEquals("文件.pdf", probe.spec.name); assertEquals(123, probe.spec.size);
        assertTrue(probe.spec.ranges); assertEquals(URL, probe.spec.url); assertTrue(responses.isEmpty());
        assertEquals(probe.spec.toJson().toString(), DirectFileSpec.fromJson(probe.spec.toJson()).toJson().toString());
    }

    @Test public void missingLengthAndIgnoredRangeDoNotPretendResumeSupport() throws Exception {
        ArrayDeque<FakeConnection> responses = new ArrayDeque<>();
        responses.add(response(200, Map.of(), new byte[0]));
        responses.add(response(200, Map.of("Content-Type", "application/octet-stream"), new byte[]{1,2,3}));
        DirectFileSpec spec = probe(responses).spec;
        assertEquals(-1, spec.size); assertFalse(spec.ranges);
    }

    @Test public void methodSignedHeadRejectionDoesNotBlockValidGet() throws Exception {
        for (int status : new int[]{401, 403, 404}) {
            ArrayDeque<FakeConnection> responses = new ArrayDeque<>();
            responses.add(response(status, Map.of(), new byte[0]));
            responses.add(response(206, Map.of("Content-Type", "application/zip", "Content-Length", "1",
                    "Content-Range", "bytes 0-0/900", "ETag", "\"signed-get\""), new byte[]{'P'}));
            DirectFileSpec spec = probe(responses).spec;
            assertEquals("HEAD " + status + " must defer to the GET response", 900, spec.size);
            assertEquals("application/zip", spec.mime); assertTrue(spec.ranges);
            assertTrue("The valid GET was not attempted", responses.isEmpty());
        }
    }

    @Test public void headRateLimitDoesNotTriggerImmediateGet() throws Exception {
        ArrayDeque<FakeConnection> responses = new ArrayDeque<>();
        responses.add(response(429, Map.of(), new byte[0]));
        FakeConnection untouched = response(206, Map.of("Content-Range", "bytes 0-0/10", "Content-Length", "1"), new byte[]{1});
        responses.add(untouched);
        try { probe(responses); fail("Rate limit was ignored"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("429")); }
        assertSame("A rate-limited HEAD must not cause another request", untouched, responses.peek());
    }

    @Test public void htmlAndPrivateRedirectAreNotDownloadCandidates() throws Exception {
        for (FakeConnection get : new FakeConnection[]{response(200, Map.of("Content-Type", "text/html"), "login".getBytes(StandardCharsets.UTF_8)),
                response(200, Map.of("Content-Type", "application/zip"), "<!doctype html><html>login</html>".getBytes(StandardCharsets.UTF_8)),
                response(302, Map.of("Location", "http://127.0.0.1/private"), new byte[0])}) {
            ArrayDeque<FakeConnection> responses = new ArrayDeque<>();
            responses.add(response(200, Map.of(), new byte[0])); responses.add(get);
            try { probe(responses); fail("Unsafe/error response accepted"); } catch (IOException | IllegalArgumentException expected) { }
        }
    }

    @Test public void changedValidatorCannotReuseSparsePartial() throws Exception {
        DirectFileSpec previous = new DirectFileSpec(URL, "large.zip", "application/zip", 100, "\"old\"", "", true);
        DirectFileSpec current = new DirectFileSpec(URL, "large.zip", "application/zip", 100, "\"new\"", "", true);
        try { DirectFileDownloader.requireSameIdentity(previous, current); fail("Changed version accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("断点已保留")); }
        List<String> args = DirectFileDownloader.arguments(new File("/native/libaria2c.so"), new File("/private/task"),
                new File("/cert.pem"), URL, previous, 4, "");
        assertTrue(args.contains("--always-resume=true")); assertTrue(args.contains("--header=If-Match: \"old\""));
        assertTrue(args.contains("--allow-overwrite=false")); assertEquals(URL, args.get(args.size()-1));
        assertEquals("--", args.get(args.size()-2));
    }

    @Test public void savedFailureReusesOnlyDigestConfirmedCompleteFile() throws Exception {
        File directory = Files.createTempDirectory("direct-file-marker-").toFile();
        try {
            File payload = new File(directory, "payload"); Files.write(payload.toPath(), new byte[]{1,2,3});
            DirectFileSpec spec = new DirectFileSpec(URL, "document.bin", "application/octet-stream", 3);
            assertNull("Bare files are not evidence of completion", DirectFileDownloader.completedFile(directory, spec));
            DirectFileDownloader.markCompleted(directory, spec, payload, new DirectFileDownloader.Attempt("fixture", () -> false));
            assertEquals(payload, DirectFileDownloader.completedFile(directory, spec));
            Files.write(payload.toPath(), new byte[]{3,2,1});
            try { DirectFileDownloader.completedFile(directory, spec); fail("Corrupted cache reused"); }
            catch (IOException expected) { assertTrue(payload.isFile()); }
        } finally { for(File file : Objects.requireNonNull(directory.listFiles())) file.delete(); directory.delete(); }
    }

    @Test public void nativeReadoutReportsMeasurementsInsteadOfSparseFileLength() throws Exception {
        List<JSONObject> measured = new ArrayList<>();
        DirectFileDownloader.parseProgress("[#abcdef 1024B/8192B(12%) CN:4 DL:512B ETA:14s]", measured::add);
        assertEquals(1, measured.size()); assertEquals(1024, measured.get(0).getLong("downloadedBytes"));
        assertEquals(8192, measured.get(0).getLong("totalBytes")); assertFalse(measured.get(0).getBoolean("transferComplete"));
        assertEquals(512, measured.get(0).getLong("speedBytesPerSecond"));
        DirectFileDownloader.parseProgress("Download complete: payload", measured::add);
        assertEquals("Log completion cannot publish 100%", 1, measured.size());
    }

    @Test public void cancelledIntegrityCheckCanRetryCacheWithoutAnotherTransfer() throws Exception {
        File work = Files.createTempDirectory("direct-file-pending-").toFile();
        File directory = new File(work, "direct-http"); assertTrue(directory.mkdir());
        try {
            byte[] data = new byte[300000]; Arrays.fill(data, (byte) 65);
            File payload = new File(directory, "payload"); Files.write(payload.toPath(), data);
            DirectFileSpec spec = new DirectFileSpec(URL, "large.bin", "application/octet-stream", data.length, "\"completed-source\"", "", true);
            DirectFileDownloader.recordTransferComplete(directory, spec, payload, "native-fixture", "aria2-exit-0");
            AtomicInteger checks = new AtomicInteger();
            DirectFileDownloader.Attempt stopped = new DirectFileDownloader.Attempt("interrupted-hash", () -> checks.incrementAndGet() >= 3);
            List<JSONObject> progress = new ArrayList<>();
            try { DirectFileDownloader.recoverIntegrity(directory, spec, stopped, progress::add); fail("Hash was not interrupted"); }
            catch (InterruptedIOException expected) { assertTrue(expected.getMessage().contains("校验")); }
            assertTrue(new File(directory, "integrity-pending.json").isFile());
            assertFalse(new File(directory, "complete.json").exists());
            assertEquals("processing", progress.get(0).getString("phase"));
            assertTrue(progress.get(0).getBoolean("transferComplete"));
            // Null Context makes a second network/native initialization fail: only the checkpoint path is usable.
            File reused = DirectFileDownloader.download(null, URL, spec, work, "retry-integrity", () -> false, ignored -> {});
            assertEquals(payload, reused); assertEquals(data.length, reused.length());
            assertTrue(new File(directory, "complete.json").isFile());
            assertFalse(new File(directory, "integrity-pending.json").exists());
        } finally { removeFiles(directory); work.delete(); }
    }

    @Test public void fullyWrittenStagingProofCanRecoverBeforeRename() throws Exception {
        File work = Files.createTempDirectory("direct-file-proof-stage-").toFile();
        File directory = new File(work, "direct-http"); assertTrue(directory.mkdir());
        try {
            File payload = new File(directory, "payload"); Files.write(payload.toPath(), new byte[]{1,2,3});
            DirectFileSpec spec = new DirectFileSpec(URL, "file.bin", "application/octet-stream", 3);
            DirectFileDownloader.recordTransferComplete(directory, spec, payload, "fixture", "aria2-exit-0");
            assertTrue(new File(directory, "integrity-pending.json").renameTo(new File(directory, "integrity-pending.json.tmp")));
            assertEquals(payload, DirectFileDownloader.download(null, URL, spec, work, "retry-staged-proof", () -> false, ignored -> {}));
            assertTrue(new File(directory, "complete.json").isFile());
        } finally { removeFiles(directory); work.delete(); }
    }

    @Test public void corruptProofOrChangedPayloadSizeCannotBecomeComplete() throws Exception {
        for (boolean corruptProof : new boolean[]{false, true}) {
            File directory = Files.createTempDirectory("direct-file-invalid-proof-").toFile();
            try {
                File payload = new File(directory, "payload"); Files.write(payload.toPath(), new byte[]{1,2,3});
                DirectFileSpec spec = new DirectFileSpec(URL, "file.bin", "application/octet-stream", 3);
                DirectFileDownloader.recordTransferComplete(directory, spec, payload, "fixture", "aria2-exit-0");
                if (corruptProof) Files.write(new File(directory, "integrity-pending.json").toPath(), "{\"schema\":1}".getBytes(StandardCharsets.UTF_8));
                else Files.write(payload.toPath(), new byte[]{1,2,3,4});
                try { DirectFileDownloader.recoverIntegrity(directory, spec, new DirectFileDownloader.Attempt("retry", () -> false), ignored -> {}); fail("Invalid completed proof accepted"); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("已保留")); }
                assertTrue(payload.isFile()); assertTrue(new File(directory, "integrity-pending.json").isFile());
                assertFalse(new File(directory, "complete.json").exists());
            } finally { removeFiles(directory); }
        }
    }

    private static void removeFiles(File directory) {
        File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete();
    }

    private static DirectFileDownloader.Probe probe(ArrayDeque<FakeConnection> responses) throws Exception {
        return DirectFileDownloader.probe(null, URL, new DirectFileDownloader.Attempt("fixture", () -> false),
                (url, proxy) -> { FakeConnection next = responses.poll(); if(next == null)throw new AssertionError("Unexpected request"); return next; }, false);
    }
    private static FakeConnection response(int status, Map<String,String> headers, byte[] body) throws Exception {
        return new FakeConnection(status, headers, body);
    }
    private static final class FakeConnection extends HttpURLConnection {
        final int status; final Map<String,String> headers; final byte[] body;
        FakeConnection(int status, Map<String,String> headers, byte[] body) throws Exception { super(new URL(URL)); this.status=status;this.headers=headers;this.body=body; }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String key) { return headers.get(key); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { }
    }
}
