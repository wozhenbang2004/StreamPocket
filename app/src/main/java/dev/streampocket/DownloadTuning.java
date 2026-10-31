package dev.streampocket;

import java.net.URI;
import java.io.File;
import java.util.Locale;

/** Small, explicit bounds for connections inside one download. */
public final class DownloadTuning {
    public static final String PREFERENCE = "download_connections";
    public static final int DEFAULT_CONNECTIONS = 4;

    private DownloadTuning() {}

    public static int connections(int stored) {
        return stored == 1 || stored == 4 || stored == 8 ? stored : DEFAULT_CONNECTIONS;
    }

    public static boolean parallelHttpEligible(String url, String proxy, int count) {
        if (connections(count) == 1) return false;
        try {
            URI source = new URI(url);
            String host = source.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            boolean supported = CoreRules.isTwitterUrl(url) || CoreRules.isBilibiliUrl(url) || host.equals("douyin.com") || host.endsWith(".douyin.com")
                    || host.equals("iesdouyin.com") || host.endsWith(".iesdouyin.com");
            if (!supported || !("https".equalsIgnoreCase(source.getScheme()) || "http".equalsIgnoreCase(source.getScheme()))) return false;
            // aria2 accepts HTTP proxies; native yt-dlp retains SOCKS / other proxy support.
            return proxy == null || proxy.trim().isEmpty() || "http".equalsIgnoreCase(new URI(proxy.trim()).getScheme());
        } catch (Exception invalid) { return false; }
    }

    /** Existing sequential bytes stay on their established path after an upgrade. */
    public static boolean hasNativeResume(File taskDirectory) {
        File[] files = taskDirectory.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (!file.isFile() || file.length() <= 0) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".part") || name.contains(".part-frag") || name.endsWith(".ytdl")
                    || name.endsWith(".mp4") || name.endsWith(".m4a") || name.endsWith(".mkv") || name.endsWith(".webm")) return true;
        }
        return false;
    }

    public static String ariaArguments(int count) {
        int bounded = connections(count);
        return "aria2c:-x" + bounded + " -s" + bounded + " -j1 --min-split-size=1M"
                + " --max-tries=3 --retry-wait=1 --connect-timeout=15 --timeout=15"
                + " --auto-save-interval=1 --summary-interval=1";
    }
}
