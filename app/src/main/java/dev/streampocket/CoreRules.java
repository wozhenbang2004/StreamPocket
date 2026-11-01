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
        if(text.contains("没有返回可用视频")||text.contains("no video"))return "源站没有返回可用视频。请确认复制的是具体视频页面，并在原平台检查内容是否仍能播放。链接记录已保留，可重新解析。";
        String message = userMessage(error);
        if (message.startsWith("操作未完成")) return "暂时未能解析出视频。链接已留在失败记录，可重新解析；错误详情可查看或复制。";
        return message;
    }

    public static String userMessage(Throwable error) {
        String text = cleanLog(error == null ? "" : error.toString());
        String lower = text.toLowerCase(Locale.ROOT);
        SourceFailure.Kind kind = SourceFailure.kind(error);
        if(kind==SourceFailure.Kind.STORAGE)return "手机存储空间不足。请腾出空间后重试，已有任务记录会保留。";
        if(kind==SourceFailure.Kind.PERMISSION)return "没有读取或保存文件的权限。请重新授予文件访问权限；来自其他应用的文件可重新选择或导入后重试。";
        if(kind==SourceFailure.Kind.NOT_FOUND)return lower.contains("filenotfound")||lower.contains("no such file")||lower.contains("file does not exist")?"找不到原文件，可能已被移动或删除。请恢复文件或重新下载，已有记录仍保留。":"找不到链接对应的内容。请在原平台确认内容仍有效，再重新复制链接并解析。";
        if(kind==SourceFailure.Kind.CERTIFICATE)return "无法验证网站的安全证书。请检查手机时间、网络或代理配置后重试。";
        if(kind==SourceFailure.Kind.ACCESS)return "源站拒绝访问此内容（403）。请先在原平台确认能否观看，再检查地区、账号权限或代理配置后重试。";
        if (kind == SourceFailure.Kind.RATE_LIMIT) return "平台请求过于频繁，已进入冷却。稍后重试，登录状态会保留。";
        if (kind == SourceFailure.Kind.TIMEOUT) return "连接源站超时，暂时没有收到响应。请检查网络或代理后重试，链接记录已保留。";
        if (kind == SourceFailure.Kind.NETWORK) return lower.contains("resolve host")||lower.contains("name resolution")||lower.contains("unknownhost")?"无法解析网站地址。请检查网络、DNS 或代理配置后重试，链接记录已保留。":"无法连接源站，或连接已中断。请检查网络、代理和源站是否可访问，再重试。";
        if (kind == SourceFailure.Kind.AUTH) return "源站要求登录或当前账号权限不足。请在账号管理中检查此平台的登录状态。";
        if(kind==SourceFailure.Kind.UNSUPPORTED)return lower.contains("requested format")?"所选画质暂时不可用。请重新解析链接，再选择其他可用画质。":"暂时无法从这个链接取得视频。请复制具体视频页面的链接，或更新解析器后重新解析。";
        if (lower.contains("ffmpeg") || lower.contains("dlopen") || lower.contains("libc++") || lower.contains("native")) return "视频处理组件暂时无法运行。请在设置中检查解析器与组件状态，更新后重试；详情保留了原始错误。";
        if (lower.contains("captcha") || lower.contains("verification") || lower.contains("verify") || lower.contains("验证")) return "平台要求验证。请在账号管理中正常完成网页登录与验证，保存后重试。抖音目前属于实验性支持。";
        if (lower.contains("fresh cookies")) return "未能获取视频详情，可能与会话或解析接口有关。可更新解析器，或在账号管理中重新登录并保存后重试。";
        if (lower.contains("unsupported url") || lower.contains("no video") || lower.contains("not available") || lower.contains("requested format")) return "当前链接或所选画质暂不可下载。请尝试最高可用画质，或更新内核后重试。";
        if (lower.contains("network") || lower.contains("unable to download")) return "网络或源站暂时不可用。请确认原链接能正常访问，再检查网络或代理后重试。";
        return "操作未完成，已有记录保留。可稍后重试，或查看并复制错误详情。";
    }

}
