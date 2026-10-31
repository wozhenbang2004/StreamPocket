package dev.streampocket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DownloadTuningTest {
    @Test public void boundsUserConnectionsAndScopesParallelToSupportedSites() {
        assertEquals(4, DownloadTuning.connections(128));
        assertEquals(1, DownloadTuning.connections(1));
        assertEquals(8, DownloadTuning.connections(8));
        assertTrue(DownloadTuning.parallelHttpEligible("https://x.com/user/status/123", "", 4));
        assertTrue(DownloadTuning.parallelHttpEligible("https://www.douyin.com/video/123", "http://127.0.0.1:8080", 8));
        assertTrue(DownloadTuning.parallelHttpEligible("https://www.bilibili.com/video/BV1nx411u79K", "", 4));
        assertTrue(DownloadTuning.parallelHttpEligible("https://b23.tv/abc", "", 4));
        for (String url : new String[]{"https://bilibili.com.example.org/video/BV1", "https://b23.tv.example.org/a", "https://evilx.com/status/123", "https://douyin.com.example.org/video/123", "file:///tmp/video"})
            assertFalse(url, DownloadTuning.parallelHttpEligible(url, "", 4));
    }

    @Test public void existingNativeBytesKeepTheirEstablishedResumePath() throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("streampocket-native-resume-");
        try {
            java.nio.file.Files.createDirectories(root.resolve("parallel-http"));
            java.nio.file.Files.write(root.resolve("parallel-http/fixture.mp4.part"),new byte[]{1});
            assertFalse("Sparse parallel child directories are not native partials",DownloadTuning.hasNativeResume(root.toFile()));
            java.nio.file.Files.write(root.resolve("fixture.f16.mp4.part"),new byte[]{1});
            assertTrue(DownloadTuning.hasNativeResume(root.toFile()));
            java.nio.file.Files.delete(root.resolve("fixture.f16.mp4.part"));
            java.nio.file.Files.write(root.resolve("fixture.f16.mp4"),new byte[]{1});
            assertTrue("Completed video track must be reused while its audio is unfinished",DownloadTuning.hasNativeResume(root.toFile()));
        } finally {
            java.nio.file.Files.deleteIfExists(root.resolve("fixture.f16.mp4"));
            java.nio.file.Files.deleteIfExists(root.resolve("fixture.f16.mp4.part"));
            java.nio.file.Files.delete(root.resolve("parallel-http/fixture.mp4.part"));
            java.nio.file.Files.delete(root.resolve("parallel-http"));
            java.nio.file.Files.delete(root);
        }
    }

    @Test public void standardAndSocksKeepNativeDownloader() {
        assertFalse(DownloadTuning.parallelHttpEligible("https://x.com/u/status/123", "", 1));
        assertFalse(DownloadTuning.parallelHttpEligible("https://x.com/u/status/123", "socks5://127.0.0.1:1080", 4));
        assertFalse(DownloadTuning.parallelHttpEligible("https://x.com/u/status/123", "socks5h://127.0.0.1:1080", 4));
        assertFalse(DownloadTuning.parallelHttpEligible("https://x.com/u/status/123", "https://proxy.example.com:443", 4));
        assertTrue(DownloadTuning.ariaArguments(4).contains("-x4 -s4 -j1"));
        assertFalse(DownloadTuning.ariaArguments(4).contains("-x16"));
    }
}
