package dev.streampocket;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ordered links from real share text, including repeated X aliases and tracking URLs. */
public final class LinkBatch {
    private LinkBatch() {}
    public static final int MAX_LINKS = 100;
    private static final Pattern LINK = Pattern.compile(
            "(?i)(?:https?://|(?<![\\w.@/])(?:(?:www|m|mobile|music|v|vm|vt|old|np|clips|player|go)\\.)?(?:" + VideoPlatform.shareDomainPattern()
                    + ")/)[^\\s<>\\\"『』「」。，！？；、,;]+?(?=https?://|[\\s<>\\\"『』「」。，！？；、,;]|$)");
    private static final Pattern FILE_LINK = Pattern.compile("(?i)https?://[^\\s<>\\\"『』「」]+");
    private static final Pattern TWEET = Pattern.compile("/(?:[^/]+/status|i/web/status)/(\\d+)(/(?:video|photo)/\\d+)?(?:/.*)?");

    public static Result parse(String text) {
        return parse(text, false);
    }

    /** Direct files retain every signed query byte and bypass video aliases. */
    public static Result parseFiles(String text) {
        return parse(text, true);
    }

    private static Result parse(String text, boolean files) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException(files ? "请先粘贴文件链接。" : "请先粘贴视频链接或分享文案。");
        if (text.length() > 65536) throw new IllegalArgumentException("分享文案过长，请分批粘贴（每批最多 100 个链接）。");
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        List<String> invalid = new ArrayList<>();
        int duplicates = 0;
        Matcher match = (files ? FILE_LINK : LINK).matcher(text);
        while (match.find()) {
            String candidate = match.group();
            if (!candidate.regionMatches(true, 0, "http", 0, 4)) candidate = "https://" + candidate;
            try {
                String url = files ? DirectFileRules.normalizeUrl(candidate) : CommonVideoLinks.normalizeVideo(candidate);
                String key = files ? url : identity(url);
                if (unique.containsKey(key)) duplicates++;
                else unique.put(key, url);
                if (unique.size() > MAX_LINKS) throw new TooManyLinks();
            } catch (TooManyLinks limit) {
                throw new IllegalArgumentException("每批最多 100 个不同链接，请分批粘贴。");
            } catch (IllegalArgumentException bad) { invalid.add(candidate); }
        }
        if (unique.isEmpty()) throw new IllegalArgumentException(invalid.isEmpty()
                ? (files ? "未找到文件链接。请按行粘贴完整的 HTTP 或 HTTPS 地址。" : "未找到视频链接。可一次粘贴多个链接或包含链接的分享文案。")
                : (files ? "未找到有效文件链接，不支持本地地址或含账号密码的链接。" : "未找到公开网站的有效视频链接，不支持本地地址或含账号密码的链接。"));
        return new Result(new ArrayList<>(unique.values()), invalid, duplicates);
    }

    static String identity(String url) {
        try {
            URI uri = new URI(url);
            String common = CommonVideoLinks.identity(url);
            if (!common.isEmpty()) return common;
            String youtubeId = YouTubeLinks.videoId(url);
            if (!youtubeId.isEmpty()) return "youtube:video:" + youtubeId;
            String pornhubId = PornhubLinks.identity(url);
            if (!pornhubId.isEmpty()) return pornhubId;
            String heyboxId = HeyboxLinks.postId(url);
            if (!heyboxId.isEmpty()) return "heybox:post:" + heyboxId;
            if (CoreRules.isTwitterUrl(url)) {
                Matcher tweet = TWEET.matcher(uri.getPath());
                if (tweet.matches()) return "x:status:" + tweet.group(1) + (tweet.group(2) == null ? "" : tweet.group(2));
            }
            // Only remove known tracking fields for comparison. The engine receives original URL bytes.
            if (!CoreRules.isBilibiliUrl(url) && !SourceScheduler.platform(url).equals("douyin")) return url;
            String query = uri.getRawQuery();
            List<String> kept = new ArrayList<>();
            if (query != null) for (String part : query.split("&"))
                if (!part.matches("(?i)(?:utm_[^=]*|spm_id_from|vd_source|share_source|share_medium|share_from|share_token|timestamp|unique_k|is_from_webapp|sender_device|web_id|s|t)=.*")) kept.add(part);
            java.util.Collections.sort(kept);
            return uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath() + (kept.isEmpty() ? "" : "?" + join(kept, "&"));
        } catch (Exception invalid) { return url; }
    }

    static String join(Iterable<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) { if (result.length() > 0) result.append(separator); result.append(value); }
        return result.toString();
    }

    private static final class TooManyLinks extends IllegalArgumentException {}
    public static final class Result {
        public final List<String> urls, invalid;
        public final int duplicates;
        Result(List<String> urls, List<String> invalid, int duplicates) {
            this.urls = java.util.Collections.unmodifiableList(urls);
            this.invalid = java.util.Collections.unmodifiableList(invalid);
            this.duplicates = duplicates;
        }
    }
}
