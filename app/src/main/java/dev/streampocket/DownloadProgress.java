package dev.streampocket;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tracks selected media streams, rather than mistaking each stream for the whole task. */
final class DownloadProgress {
    static final String PLAN_PREFIX = "SP_PLAN:";
    static final String DOWNLOAD_PREFIX = "SP_PROGRESS:";
    static final String POST_PREFIX = "SP_POST:";
    static final String TRANSFER_MODE_PREFIX = "SP_TRANSFER_MODE:";
    private static final String FIELDS = "format_id,filesize,filesize_approx,vcodec,acodec,protocol";
    static final String PLAN_TEMPLATE = "before_dl:" + PLAN_PREFIX + "{\"single\":%(.{" + FIELDS
            + "})j,\"formats\":%(requested_formats.:.{" + FIELDS + "}|[])j}";
    static final String DOWNLOAD_TEMPLATE = "download:" + DOWNLOAD_PREFIX
            + "{\"info\":%(info.{format_id,vcodec,acodec,protocol})j,\"progress\":%(progress.{status,downloaded_bytes,total_bytes,total_bytes_estimate,eta,speed,fragment_index,fragment_count})j}";
    static final String POST_TEMPLATE = "postprocess:" + POST_PREFIX + "%(progress.{status,postprocessor})j";
    private static final Pattern ARIA_PROGRESS = Pattern.compile("^\\s*\\[#\\w+\\s+([^\\s/]+)/([^\\s(\\]]+)(?:\\(([0-9]+(?:\\.[0-9]+)?)%\\))?([^\\]]*)\\]\\s*$");
    private static final Pattern ARIA_SPEED = Pattern.compile("(?:^|\\s)DL:([^\\s\\]]+)");
    private static final Pattern ARIA_ETA = Pattern.compile("(?:^|\\s)ETA:([^\\s\\]]+)");
    private static final Pattern ARIA_CONNECTIONS = Pattern.compile("(?:^|\\s)CN:(\\d+)(?=\\s|$)");
    private static final Pattern ARIA_ID = Pattern.compile("^\\s*\\[#(\\w+)");
    private static final Pattern BYTE_SIZE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)((?:[KMGT]i?B)|B)?");
    private static final Pattern DURATION = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?");
    private static final Pattern ANSI_COLOR = Pattern.compile("\\u001B\\[[0-9;]*m");
    private final LinkedHashMap<String, Stream> streams = new LinkedHashMap<>();
    private final LinkedHashMap<String, Stream> ariaStreams = new LinkedHashMap<>();
    private Stream current;
    private String phase = "preparing";
    private boolean retrying, hasProgress;
    private double highestOverall = -1;
    private long eta = -1;
    private double speed = -1;
    private String transferStrategy = "native";
    private boolean compatibilityOverride;
    private int configuredConnections = 4, activeConnections = -1;

    /** A configured upper bound is separate from observed live connections. */
    synchronized void setTransferPolicy(String strategy, int connections) {
        transferStrategy = "parallel-http".equals(strategy) ? "parallel-http" : "native";
        configuredConnections = connections == 1 || connections == 4 || connections == 8 ? connections : 4;
        activeConnections = -1; compatibilityOverride = false;
    }

    static boolean isProtocolLine(String line) {
        return line.startsWith(PLAN_PREFIX) || line.startsWith(DOWNLOAD_PREFIX) || line.startsWith(POST_PREFIX)
                || line.startsWith(TRANSFER_MODE_PREFIX);
    }

    synchronized boolean accept(String line) {
        try {
            if (line.startsWith(TRANSFER_MODE_PREFIX)) {
                if (!"native-http".equals(line.substring(TRANSFER_MODE_PREFIX.length()).trim())) return false;
                // A before-download preflight selected compatibility HTTP. Keep
                // it for later HTTP media in this task, even after another plan.
                transferStrategy = "native"; compatibilityOverride = true; activeConnections = -1;
                return true;
            }
            if (line.startsWith(PLAN_PREFIX)) {
                if (!hasProgress) readPlan(new JSONObject(line.substring(PLAN_PREFIX.length())));
                return true;
            }
            if (line.startsWith(DOWNLOAD_PREFIX)) {
                readProgress(new JSONObject(line.substring(DOWNLOAD_PREFIX.length()))); return true;
            }
            if (line.startsWith(POST_PREFIX)) {
                JSONObject post = new JSONObject(line.substring(POST_PREFIX.length()));
                String processor = post.optString("postprocessor", "");
                if (processor.contains("Merger")) phase = "merging";
                else if (transferComplete() || processor.contains("Fixup")) phase = "processing";
                else return false;
                retrying = false; speed = -1; eta = -1; activeConnections = -1; return true;
            }
        } catch (Exception malformed) { return false; }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("retrying") || lower.contains("retry attempt") || lower.contains("重新连接")) {
            retrying = true; speed = -1; eta = -1; activeConnections = -1; return true;
        }
        if (lower.contains("extraction will be delegated to ffmpeg")) {
            Stream stream = current == null || current.finished ? firstUnfinished() : current;
            if (stream != null) stream.delegatedToFfmpeg = true;
            activeConnections = -1; return true;
        }
        Matcher aria = ARIA_PROGRESS.matcher(ANSI_COLOR.matcher(line).replaceAll(""));
        if (aria.matches()) {
            Matcher identifier = ARIA_ID.matcher(aria.group()); identifier.find();
            Stream identified = ariaStreams.get(identifier.group(1));
            if (identified != null && identified.finished) return false;
            if (identified != null) selectCurrent(identified);
            if (current == null || current.finished) selectCurrent(firstUnfinished());
            if (current == null) return false;
            // Native fragment downloads do not emit aria2 connection counts.
            // Ignore a delayed or unrelated summary rather than attach it to HLS.
            if (fragmentProtocol(current.protocol) || current.delegatedToFfmpeg) return false;
            ariaStreams.put(identifier.group(1), current);
            double bytes = byteSize(aria.group(1)), total = byteSize(aria.group(2));
            if (current.total <= 0 && total > 0) { current.total = (long) total; current.estimated = roundedSize(aria.group(2)); }
            double percent = aria.group(3) == null ? bytes >= 0 && total > 0 ? bytes * 100 / total : -1 : Double.parseDouble(aria.group(3));
            if (percent >= 0 && percent <= 100) {
                current.stageHeld = percent < current.stagePercent;
                current.stagePercent = Math.max(current.stagePercent, percent);
            }
            if (bytes >= 0) {
                if (bytes >= current.downloaded) { current.downloaded = (long) bytes; current.bytesEstimated = roundedSize(aria.group(1)); }
            } else if (percent >= 0 && percent <= 100 && current.total > 0) {
                current.downloaded = Math.max(current.downloaded, (long) (current.total * percent / 100)); current.bytesEstimated = true;
            }
            Matcher rate = ARIA_SPEED.matcher(aria.group(4));
            speed = rate.find() ? positiveFinite(byteSize(rate.group(1).replaceFirst("/s$", ""))) : -1;
            Matcher remaining = ARIA_ETA.matcher(aria.group(4));
            eta = remaining.find() ? durationSeconds(remaining.group(1)) : -1;
            Matcher connections = ARIA_CONNECTIONS.matcher(aria.group(4));
            activeConnections = !compatibilityOverride && connections.find() ? connectionCount(connections.group(1)) : -1;
            phase = current.phase; retrying = false; hasProgress = true; return true;
        }
        if (line.contains("[Merger]")) { phase = "merging"; retrying = false; speed = -1; eta = -1; activeConnections = -1; return true; }
        if (line.contains("[Fixup")) { phase = "processing"; retrying = false; speed = -1; eta = -1; activeConnections = -1; return true; }
        return false;
    }

    private void readPlan(JSONObject plan) {
        streams.clear(); ariaStreams.clear(); current = null;
        JSONArray formats = plan.optJSONArray("formats");
        if (formats != null && formats.length() > 0) {
            for (int i = 0; i < formats.length(); i++) addStream(formats.optJSONObject(i), i);
        } else addStream(plan.optJSONObject("single"), 0);
        phase = "preparing"; speed = -1; eta = -1; activeConnections = -1;
    }

    private Stream addStream(JSONObject info, int fallbackIndex) {
        if (info == null) return null;
        String id = info.optString("format_id", "stream-" + fallbackIndex);
        Stream stream = new Stream(id, streamPhase(info));
        stream.protocol = protocol(info);
        stream.total = positive(info, "filesize");
        if (stream.total <= 0) { stream.total = positive(info, "filesize_approx"); stream.estimated = stream.total > 0; }
        streams.put(id, stream); return stream;
    }

    private void readProgress(JSONObject event) {
        JSONObject info = event.optJSONObject("info"), progress = event.optJSONObject("progress");
        if (info == null || progress == null) return;
        String id = info.optString("format_id", "");
        Stream stream = streams.get(id);
        if (stream == null) {
            if (id.isEmpty()) stream = firstUnfinished();
            else stream = addStream(info, streams.size());
        }
        if (stream == null || stream.finished) return;
        selectCurrent(stream); hasProgress = true; retrying = false; activeConnections = -1;
        String protocol = protocol(info);
        if (!protocol.isEmpty()) stream.protocol = protocol;
        long total = positive(progress, "total_bytes");
        if (total > 0) { stream.total = total; stream.estimated = false; }
        else if (stream.total <= 0 || stream.estimated) {
            long estimate = positive(progress, "total_bytes_estimate");
            if (estimate > 0) { stream.total = estimate; stream.estimated = true; }
        }
        long measured = Math.max(0, progress.optLong("downloaded_bytes", 0));
        stream.downloaded = stream.bytesEstimated ? measured : Math.max(stream.downloaded, measured);
        stream.bytesEstimated = false;
        double percent = stream.total > 0 ? stream.downloaded * 100.0 / stream.total : -1;
        if (percent < 0 && progress.optLong("fragment_count", 0) > 0)
            percent = progress.optLong("fragment_index", 0) * 100.0 / progress.optLong("fragment_count");
        if (percent >= 0) { stream.stageHeld = percent < stream.stagePercent; stream.stagePercent = Math.max(stream.stagePercent, Math.min(100, percent)); }
        eta = progress.has("eta") && !progress.isNull("eta") ? Math.max(-1, progress.optLong("eta", -1)) : -1;
        speed = positiveFinite(progress.optDouble("speed", -1));
        if ("finished".equals(progress.optString("status"))) {
            stream.finished = true; stream.stagePercent = 100; stream.stageHeld = false; speed = -1; eta = -1;
            if (stream.downloaded > 0) { stream.total = stream.downloaded; stream.estimated = false; }
        }
        phase = transferComplete() ? "processing" : stream.phase;
    }

    synchronized Snapshot snapshot() {
        int completed = 0, index = 0, currentIndex = 0;
        long downloaded = 0, total = 0;
        boolean known = !streams.isEmpty(), estimated = false, totalEstimated = false, downloadedEstimated = false;
        for (Stream stream : streams.values()) {
            index++; if (stream == current) currentIndex = index;
            if (stream.finished) completed++;
            known &= stream.total > 0; estimated |= stream.estimated || stream.bytesEstimated;
            totalEstimated |= stream.estimated;
            downloadedEstimated |= !stream.finished && stream.bytesEstimated;
            total += Math.max(0, stream.total);
            downloaded += stream.finished && stream.total > 0 ? stream.total : Math.min(stream.downloaded, stream.total > 0 ? stream.total : Long.MAX_VALUE);
        }
        boolean complete = !streams.isEmpty() && completed == streams.size();
        boolean downloading = !(phase.equals("merging") || phase.equals("processing") || phase.equals("saving"));
        double overall = known && total > 0 && !complete && downloading ? Math.min(100, downloaded * 100.0 / total) : -1;
        // A corrected estimate can increase the denominator. Keep the visible
        // high-water mark, but do not relabel that held estimate as exact bytes.
        if (overall >= 0 && overall < highestOverall) estimated = true;
        // A finished download is not yet a saved task. Processing has no invented percentage.
        if (overall >= 0 && overall < 100) { highestOverall = Math.max(highestOverall, overall); overall = highestOverall; }
        else if (overall >= 100 && !complete && downloading) { overall = highestOverall; estimated = true; }
        else overall = -1;
        String visiblePhase = retrying ? "retrying" : phase;
        String label = retrying ? "正在重试连接" : label(phase);
        double stage = current == null || !downloading || complete ? -1 : current.stagePercent;
        StringBuilder detail = new StringBuilder(label);
        if (stage >= 0) detail.append(String.format(Locale.ROOT, current != null && (current.estimated || current.stageHeld || current.bytesEstimated) ? " 约 %.1f%%" : " %.1f%%", stage));
        if (streams.size() > 1 && currentIndex > 0 && downloading)
            detail.append(" · 媒体 ").append(currentIndex).append('/').append(streams.size());
        if (complete && phase.equals("processing")) detail = new StringBuilder("音视频数据已下载，正在处理文件");
        if (retrying && current != null) detail.append(" · ").append(label(current.phase));
        if (speed > 0 && !Double.isInfinite(speed) && !Double.isNaN(speed) && downloading && !complete && !retrying)
            detail.append(String.format(Locale.ROOT, speed >= 1048576 ? " · %.1f MB/s" : " · %.0f KB/s", speed / (speed >= 1048576 ? 1048576 : 1024)));
        if (eta > 0 && downloading && !complete && !retrying) detail.append(" · 本阶段约 ").append(eta).append(" 秒");
        return new Snapshot(visiblePhase, label, detail.toString(), overall, stage, completed, streams.size(),
                downloaded, known ? total : -1, complete, estimated, speed, eta,
                downloading && !complete ? transferMode() : "auto", configuredConnections,
                downloading && !complete && !retrying ? activeConnections : -1,
                known && totalEstimated, downloadedEstimated);
    }

    synchronized Snapshot saving() { phase = "saving"; retrying = false; speed = -1; eta = -1; activeConnections = -1; return snapshot(); }
    private void selectCurrent(Stream stream) { if (current != stream) { current = stream; speed = -1; eta = -1; activeConnections = -1; } }
    private String transferMode() {
        if (current != null && current.delegatedToFfmpeg) return "auto";
        Stream selected = current == null ? firstUnfinished() : current;
        if (selected != null && fragmentProtocol(selected.protocol)) return current != null || compatibilityOverride ? "fragments" : "auto";
        if (compatibilityOverride) return "native-http";
        if (current == null) return "auto";
        if (current.protocol.equals("http") || current.protocol.equals("https"))
            return transferStrategy.equals("parallel-http") ? "parallel-http" : "native-http";
        return "auto";
    }
    private static boolean fragmentProtocol(String protocol) {
        return protocol.equals("m3u8") || protocol.equals("m3u8_native") || protocol.equals("hls")
                || protocol.equals("dash") || protocol.equals("http_dash_segments")
                || protocol.equals("http_dash_segments_generator") || protocol.equals("native_dash_segments");
    }
    private static String protocol(JSONObject info) {
        String value = info.optString("protocol", "").trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9_+.-]{1,64}") ? value : "";
    }
    private static int connectionCount(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return -1; }
    }
    private Stream firstUnfinished() { for (Stream stream : streams.values()) if (!stream.finished) return stream; return null; }
    private boolean transferComplete() { if (streams.isEmpty()) return false; for (Stream stream : streams.values()) if (!stream.finished) return false; return true; }
    private static long positive(JSONObject data, String key) { return Math.max(-1, data.optLong(key, -1)); }
    private static double positiveFinite(double value) { return value > 0 && Double.isFinite(value) ? value : -1; }
    private static double byteSize(String text) {
        Matcher size = BYTE_SIZE.matcher(text);
        if (!size.matches()) return -1;
        String unit = size.group(2);
        int exponent = unit == null || unit.equals("B") ? 0 : "KMGT".indexOf(unit.charAt(0)) + 1;
        double value = Double.parseDouble(size.group(1)) * Math.pow(unit != null && unit.contains("i") ? 1024 : 1000, exponent);
        return Double.isFinite(value) && value >= 0 && value <= Long.MAX_VALUE ? value : -1;
    }
    private static boolean roundedSize(String text) { return text.contains(".") || !(text.matches("[0-9]+B?")); }
    private static long durationSeconds(String text) {
        Matcher duration = DURATION.matcher(text);
        if (!duration.matches() || text.isEmpty()) return -1;
        try {
            long hours = duration.group(1) == null ? 0 : Long.parseLong(duration.group(1));
            long minutes = duration.group(2) == null ? 0 : Long.parseLong(duration.group(2));
            long seconds = duration.group(3) == null ? 0 : Long.parseLong(duration.group(3));
            return Math.addExact(Math.addExact(Math.multiplyExact(hours, 3600), Math.multiplyExact(minutes, 60)), seconds);
        } catch (ArithmeticException | NumberFormatException invalid) { return -1; }
    }
    private static String streamPhase(JSONObject info) {
        boolean video = hasCodec(info, "vcodec"), audio = hasCodec(info, "acodec");
        return video && audio ? "media" : audio ? "audio" : video ? "video" : "media";
    }
    private static boolean hasCodec(JSONObject info, String name) {
        String codec = info.optString(name, "none"); return !codec.isEmpty() && !codec.equals("none") && !codec.equals("null");
    }
    private static String label(String phase) {
        switch (phase) {
            case "video": return "下载视频";
            case "audio": return "下载音频";
            case "media": return "下载音视频";
            case "merging": return "正在合并音视频";
            case "processing": return "正在处理文件";
            case "saving": return "正在保存到下载目录";
            default: return "正在获取下载信息";
        }
    }
    private static final class Stream {
        final String id, phase; String protocol = ""; long total = -1, downloaded; double stagePercent = -1;
        boolean finished, estimated, stageHeld, bytesEstimated, delegatedToFfmpeg;
        Stream(String id, String phase) { this.id = id; this.phase = phase; }
    }
    static final class Snapshot {
        final String phase, stageLabel, detail, transferMode; final double progress, stageProgress, speedBytesPerSecond;
        final int completedStreams, totalStreams, configuredConnections, activeConnections; final long downloadedBytes, totalBytes, etaSeconds;
        final boolean transferComplete, estimated, totalBytesEstimated, downloadedBytesEstimated;
        Snapshot(String phase, String stageLabel, String detail, double progress, double stageProgress, int completed,
                 int total, long bytes, long size, boolean complete, boolean estimated, double speed, long eta,
                 String transferMode, int configuredConnections, int activeConnections,
                 boolean totalBytesEstimated, boolean downloadedBytesEstimated) {
            this.phase = phase; this.stageLabel = stageLabel; this.detail = detail; this.progress = progress; this.stageProgress = stageProgress;
            completedStreams = completed; totalStreams = total; downloadedBytes = bytes; totalBytes = size;
            transferComplete = complete; this.estimated = estimated; speedBytesPerSecond = speed; etaSeconds = eta;
            this.transferMode = transferMode; this.configuredConnections = configuredConnections; this.activeConnections = activeConnections;
            this.totalBytesEstimated = totalBytesEstimated; this.downloadedBytesEstimated = downloadedBytesEstimated;
        }
        String boundary() { return phase + ':' + completedStreams + ':' + stageLabel + ':' + transferMode + ':' + activeConnections; }
        String status() { return phase.equals("merging") || phase.equals("processing") ? "merging" : phase.equals("saving") ? "saving" : "downloading"; }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("phase", phase).put("stageLabel", stageLabel).put("detail", detail).put("progress", progress)
                    .put("stageProgress", stageProgress).put("completedStreams", completedStreams).put("totalStreams", totalStreams)
                    .put("downloadedBytes", downloadedBytes).put("totalBytes", totalBytes).put("transferComplete", transferComplete)
                    .put("progressEstimated", estimated).put("speedBytesPerSecond", Double.isFinite(speedBytesPerSecond) ? speedBytesPerSecond : -1)
                    .put("transferMode", transferMode).put("configuredConnections", configuredConnections).put("activeConnections", activeConnections)
                    .put("totalBytesEstimated", totalBytesEstimated).put("downloadedBytesEstimated", downloadedBytesEstimated)
                    .put("etaSeconds", etaSeconds).put("status", status()).put("userError", "");
        }
    }
}
