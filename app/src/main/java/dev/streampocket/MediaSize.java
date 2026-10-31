package dev.streampocket;

import java.util.Locale;
import org.json.JSONObject;

/** Shared, bounded byte labels for the queue, library and player information. */
public final class MediaSize {
    private MediaSize() {}

    public static String format(long bytes) {
        if (bytes < 0) return "未知大小";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    public static String taskSummary(JSONObject task) {
        String status = task.optString("status");
        if ("queued".equals(status)) return "";
        if ("completed".equals(status)) {
            long bytes = task.optLong("size", -1);
            return bytes >= 0 ? "文件大小 " + format(bytes) : "文件大小待获取";
        }
        long total = task.optLong("totalBytes", -1), downloaded = task.optLong("downloadedBytes", -1);
        boolean approximateTotal=task.has("totalBytesEstimated")?task.optBoolean("totalBytesEstimated"):task.optBoolean("progressEstimated");
        String partial=(task.optBoolean("downloadedBytesEstimated")?"约 ":"")+format(Math.max(0,downloaded));
        if (total > 0) return "已下载 " + partial + " / "
                + (approximateTotal ? "约 " : "") + format(total);
        if (downloaded > 0) return "已下载 " + partial + " · 总大小待获取";
        return "";
    }
}
