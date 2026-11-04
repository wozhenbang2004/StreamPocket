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
        String text = cleanLog(error == null ? "" : error.toString()).toLowerCase(Locale.ROOT);
        if (SourceFailure.kind(error) == SourceFailure.Kind.NO_FORMATS) {
            if (text.contains("no video could be found in this tweet"))
                return "暂时无法取得这个 X 视频。请先确认原帖能播放，再重新解析。失败链接已保留。";
            return "暂时没有可下载的画质。请先确认原链接能播放，再重新解析或更新下载支持。失败链接已保留。";
        }
        if (SourceFailure.kind(error) != SourceFailure.Kind.OTHER) return userMessage(error);
        if(text.contains("小红书未返回所选笔记"))return "未取得这条小红书笔记。请重新复制完整分享链接，并确认原平台能打开；链接记录已保留。";
        if (text.contains("certificate") || text.contains("ssl")) return "无法安全连接网站，请检查手机时间和网络后重试。";
        if (text.contains("no video could be found in this tweet") || text.contains("tweet is unavailable") || text.contains("tombstone"))
            return "暂时无法取得这个 X 视频。请先确认原帖能播放，再重新解析。失败链接已保留。";
        if(text.contains("没有返回可用视频")||text.contains("no video"))return "没有找到可下载的视频。请确认原链接能播放，再重新复制视频链接。记录已保留。";
        String message = userMessage(error);
        if (message.startsWith("操作未完成")) return "暂时无法解析这个视频，链接已保留，可稍后重试。";
        return message;
    }

    public static String inspectionMessage(Throwable error, String url) {
        String youtube = YouTubeLinks.matches(url) ? YouTubeLinks.failureMessage(error) : "";
        return youtube.isEmpty() ? inspectionMessage(error) : youtube;
    }

    public static String userMessage(Throwable error, String url) {
        String youtube = YouTubeLinks.matches(url) ? YouTubeLinks.failureMessage(error) : "";
        return youtube.isEmpty() ? userMessage(error) : youtube;
    }

    public static String userMessage(Throwable error) {
        String local=error==null||error.getMessage()==null?"":error.getMessage();
        if(local.startsWith("请复制具体的 Twitch 剪辑"))return local;
        if(local.startsWith("保存文件夹")||local.startsWith("文件夹授权")||local.startsWith("文件夹需要")||local.startsWith("请选择可写入的文件夹")||local.startsWith("未能保存文件夹设置")||local.startsWith("未能恢复默认保存位置"))return cleanLog(local);
        String text = cleanLog(error == null ? "" : error.toString());
        String lower = text.toLowerCase(Locale.ROOT);
        SourceFailure.Kind kind = SourceFailure.kind(error);
        if(kind==SourceFailure.Kind.STORAGE)return "手机存储空间不足。请腾出空间后重试，已有任务记录会保留。";
        if(kind==SourceFailure.Kind.PERMISSION)return "没有读取或保存文件的权限。请重新授予文件访问权限；来自其他应用的文件可重新选择或导入后重试。";
        if(kind==SourceFailure.Kind.NOT_FOUND)return lower.contains("filenotfound")||lower.contains("no such file")||lower.contains("file does not exist")?"找不到原文件，可能已被移动或删除。请恢复文件或重新下载，已有记录仍保留。":"找不到链接对应的内容。请在原平台确认内容仍有效，再重新复制链接并解析。";
        if(kind==SourceFailure.Kind.CERTIFICATE)return "无法验证网站的安全证书。请检查手机时间、网络或代理配置后重试。";
        if(kind==SourceFailure.Kind.ACCESS)return "网站暂时拒绝访问。请先确认原平台能播放，再检查登录状态或网络。";
        if (kind == SourceFailure.Kind.RATE_LIMIT) return "平台请求过于频繁，已进入冷却。稍后重试，登录状态会保留。";
        if (kind == SourceFailure.Kind.TIMEOUT) return "连接源站超时，暂时没有收到响应。请检查网络或代理后重试，链接记录已保留。";
        if (kind == SourceFailure.Kind.NETWORK) return lower.contains("resolve host")||lower.contains("name resolution")||lower.contains("unknownhost")||lower.contains("name or service not known")||lower.contains("nodename nor servname provided")?"无法解析网站地址。请检查网络、DNS 或代理配置后重试，链接记录已保留。":"无法连接源站，或连接已中断。请检查网络、代理和源站是否可访问，再重试。";
        if (kind == SourceFailure.Kind.AUTH) return "源站要求账号权限，请检查当前账号能否在原平台播放这条内容。";
        if(lower.contains("没有下载到媒体文件"))return "源站返回了网页，没有下载到媒体。请确认原链接能播放后重试，记录已保留。";
        if(lower.contains("文件已截断")||lower.contains("图片已截断")||lower.contains("媒体容器不完整")||lower.contains("明显短于原内容")||lower.contains("图集图片数量不完整"))
            return "下载内容不完整，未保存损坏文件。请重试，已有任务记录会保留。";
        if(lower.contains("缺少所选的媒体轨道")||lower.contains("下载文件时长无效")||lower.contains("下载文件无法识别")||lower.contains("图片文件无法识别"))
            return "下载文件无法正常读取，未保存为成功结果。请重新解析后重试。";
        if(lower.contains("图集信息已失效")||lower.contains("未能刷新所选内容"))return "内容信息已失效，请重新解析原链接后重试；记录已保留。";
        if(lower.contains("字幕文件为空或过大")||lower.contains("源站没有返回有效字幕"))return "源站没有返回有效字幕，已保存的媒体和其他附件会保留。";
        if(lower.contains("unable to obtain file audio codec")||lower.contains("does not contain any audio"))
            return "没有找到可提取的音轨，或音频信息无法读取。可改为下载视频，或重新解析后重试。";
        if(kind==SourceFailure.Kind.NO_FORMATS)return "暂时没有可下载的画质。请确认原链接能播放，再重新解析或更新下载支持。";
        if(kind==SourceFailure.Kind.UNSUPPORTED)return lower.contains("requested format")?"所选画质暂时不可用。请重新解析链接，再选择其他可用画质。":"暂时无法下载这个链接，请复制视频页面链接或更新下载支持后重试。";
        if (lower.contains("ffmpeg") || lower.contains("dlopen") || lower.contains("libc++") || lower.contains("native")) return "下载功能暂时不可用，请在设置中更新下载支持后重试。";
        if (lower.contains("captcha") || lower.contains("verification") || lower.contains("verify") || lower.contains("验证")) return "平台要求验证。请在账号管理中完成登录和验证，再重试。";
        if (lower.contains("fresh cookies")) return "暂时没有取得视频信息。请更新下载支持，或重新登录后重试。";
        if (lower.contains("unsupported url") || lower.contains("no video") || lower.contains("not available") || lower.contains("requested format")) return "当前链接或画质暂不可下载。请尝试最高可用画质，或更新下载支持。";
        if (lower.contains("network") || lower.contains("unable to download")) return "网络或源站暂时不可用。请确认原链接能正常访问，再检查网络或代理后重试。";
        return "操作未完成，已有记录保留。可稍后重试，或查看并复制错误详情。";
    }

}
