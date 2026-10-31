package dev.streampocket;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java input validation and safe user-facing error rules. */
public final class CoreRules {
    private CoreRules() {}
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"『』「」]+", Pattern.CASE_INSENSITIVE);

    public static String normalizeUrl(String sharedText) {
        if (sharedText == null) throw new IllegalArgumentException("请先粘贴视频链接。");
        Matcher matcher = URL.matcher(sharedText.trim());
        if (!matcher.find()) throw new IllegalArgumentException("未找到 http 或 https 视频链接。可以粘贴包含链接的分享文案。");
        String value = matcher.group().replaceAll("[。，“”，！!？?；;、）)\\]】》>]+$", "");
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || !host.contains(".")
                    || host.startsWith("[") || host.contains(":")) throw new IllegalArgumentException();
            if (host.matches("[0-9.]+")) {
                String[] octets = host.split("\\.");
                if (octets.length != 4) throw new IllegalArgumentException();
                int[] ip = new int[4];
                for (int i = 0; i < 4; i++) { ip[i] = Integer.parseInt(octets[i]); if (ip[i] > 255) throw new IllegalArgumentException(); }
                if (ip[0] == 0 || ip[0] == 10 || ip[0] == 127 || ip[0] >= 224 ||
                        (ip[0] == 169 && ip[1] == 254) || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) ||
                        (ip[0] == 192 && ip[1] == 168) || (ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127)) throw new IllegalArgumentException();
            }
            if (uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            String authority = host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            // Keep already encoded path/query bytes intact; only drop the fragment and normalize authority.
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + authority + path + query).toASCIIString();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("请使用公开网站的有效视频链接，不支持本地地址或包含账号密码的链接。");
        }
    }

    public static String cleanLog(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)(cookie|authorization|proxy-authorization)[:=][^\\r\\n]+", "$1: [已隐藏]")
                .replaceAll("(https?://|socks5h?://)[^\\s/@:]+:[^\\s/@]+@", "$1[已隐藏]@")
                .replaceAll("(https?://[^\\s?]+)\\?[^\\s]+", "$1?[参数已隐藏]");
    }

    public static boolean isTwitterUrl(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("x.com") || host.endsWith(".x.com") || host.equals("twitter.com") || host.endsWith(".twitter.com");
        } catch (Exception invalid) { return false; }
    }

    public static boolean isBilibiliUrl(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("bilibili.com") || host.endsWith(".bilibili.com") || host.equals("b23.tv");
        } catch (Exception invalid) { return false; }
    }

    public static boolean isTwitterAuthCookieLine(String line) {
        if (line.startsWith("#HttpOnly_")) line = line.substring(10);
        else if (line.startsWith("#")) return false;
        String[] fields = line.split("\t", -1);
        if (fields.length != 7) return false;
        String host = fields[0].toLowerCase(Locale.ROOT);
        while (host.startsWith(".")) host = host.substring(1);
        return isTwitterUrl("https://" + host) && fields[5].equals("auth_token") && !fields[6].trim().isEmpty();
    }

    public static String zipappVersion(String module) {
        Matcher match = Pattern.compile("(?m)^__version__\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(module);
        return match.find() ? match.group(1) : "";
    }

    public static boolean needsBundledCore(String installed, String bundled) {
        Matcher current = Pattern.compile("^(20\\d{2}\\.\\d{2}\\.\\d{2})").matcher(installed == null ? "" : installed);
        return !current.find() || current.group(1).compareTo(bundled) < 0;
    }

    public static String inspectionMessage(Throwable error) {
        if (SourceFailure.kind(error) != SourceFailure.Kind.OTHER) return userMessage(error);
        String text = cleanLog(error == null ? "" : error.toString()).toLowerCase(Locale.ROOT);
        if (text.contains("certificate") || text.contains("ssl")) return "解析连接的证书验证失败，请查看错误详情。视频尚未开始下载。";
        if (text.contains("no video could be found in this tweet") || text.contains("tweet is unavailable") || text.contains("tombstone"))
            return "X 没有向当前访问状态返回此帖的视频。帖子可能受限或已失效；如在 X 中能播放，可在设置的账号管理中登录后重试。";
        if (text.contains("requested format") || text.contains("unsupported url")) return "这个链接暂时无法解析，请查看错误详情。";
        String message = userMessage(error);
        if (message.startsWith("下载未完成")) return "解析失败，尚未开始下载。点击下方查看具体错误。";
        return message;
    }

    public static String userMessage(Throwable error) {
        String text = cleanLog(error == null ? "" : error.toString());
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("no space") || lower.contains("enospc")) return "手机存储空间不足。腾出空间后可重试，临时文件会保留。";
        if (lower.contains("ffmpeg") || lower.contains("dlopen") || lower.contains("libc++") || lower.contains("native")) return "视频合并组件无法运行，可能与本机系统或原生库兼容性有关。请查看详情。";
        SourceFailure.Kind kind = SourceFailure.kind(error);
        if (kind == SourceFailure.Kind.RATE_LIMIT) return "平台请求过于频繁，已进入冷却。稍后重试，登录状态会保留。";
        if (kind == SourceFailure.Kind.TIMEOUT) return "连接源站超时。请检查网络与代理后重试；这不代表登录已失效。";
        if (kind == SourceFailure.Kind.NETWORK) return "连接源站失败。请检查网络与代理，稍后重试；登录状态会保留。";
        if (kind == SourceFailure.Kind.AUTH) return "源站要求登录或当前账号权限不足。请在账号管理中检查此平台的登录状态。";
        if (lower.contains("captcha") || lower.contains("verification") || lower.contains("verify") || lower.contains("验证")) return "平台要求验证。请在账号管理中正常完成网页登录与验证，保存后重试。抖音目前属于实验性支持。";
        if (lower.contains("fresh cookies")) return "未能获取视频详情，可能与会话或解析接口有关。可更新解析器，或在账号管理中重新登录并保存后重试。";
        if (lower.contains("unsupported url") || lower.contains("no video") || lower.contains("not available") || lower.contains("requested format")) return "当前链接或所选画质暂不可下载。请尝试最高可用画质，或更新内核后重试。";
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("network") || lower.contains("unable to download") || lower.contains("403") || lower.contains("429")) return "网络连接或平台访问失败。请检查网络与代理，稍后重试；频繁请求也可能触发平台限制。";
        return "下载未完成，请查看详情并重试。已下载的临时数据会保留。";
    }

}
