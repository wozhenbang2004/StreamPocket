package dev.streampocket;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded filename language. User text is never interpreted as a yt-dlp expression. */
public final class NamingTemplate {
    public static final String DEFAULT = "{title} [{id}]";
    public static final int MAX_NAME_BYTES = 180;
    private static final Pattern TOKEN = Pattern.compile("\\{(title|id|uploader|platform|part)\\}");
    private NamingTemplate() {}

    public static String validate(String template) {
        if (template == null || template.trim().isEmpty()) throw new IllegalArgumentException("请输入命名模板");
        String value = template.trim();
        if (value.length() > 500) throw new IllegalArgumentException("命名模板最多 500 个字符");
        String literals = TOKEN.matcher(value).replaceAll("");
        if (literals.indexOf('{') >= 0 || literals.indexOf('}') >= 0)
            throw new IllegalArgumentException("可用字段：{title}、{id}、{uploader}、{platform}、{part}");
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || hasControl(value))
            throw new IllegalArgumentException("命名模板不能包含路径分隔符或控制字符");
        if (".".equals(value) || "..".equals(value)) throw new IllegalArgumentException("命名模板不是有效文件名");
        return value;
    }

    /** The displayed base name; the final extension follows the downloaded container. */
    public static Map<String, String> fields(String title, String id, String uploader, String platform, int part) {
        Map<String, String> fields = new HashMap<>();
        fields.put("title", title); fields.put("id", id); fields.put("uploader", uploader); fields.put("platform", platform);
        fields.put("part", part > 0 ? String.valueOf(part) : "");
        return fields;
    }

    /** Pure metadata entry point, also used by non-media file tasks. */
    public static String preview(Map<String, String> fields, String template) {
        String value = validate(template);
        Matcher match = TOKEN.matcher(value); StringBuffer rendered = new StringBuffer();
        while (match.find()) {
            String replacement = fields == null ? "" : fields.get(match.group(1));
            match.appendReplacement(rendered, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        match.appendTail(rendered);
        return cleanName(rendered.toString());
    }

    public static String outputPattern(Map<String, String> fields, String template) {
        return patternForName(preview(fields, template));
    }

    /** Use only on a snapshotted, rendered base name; keep the engine's extension expression. */
    public static String patternForName(String name) {
        return cleanName(name).replace("%", "%%") + ".%(ext)s";
    }

    private static String cleanName(String source) {
        StringBuilder clean = new StringBuilder();
        String value = source == null ? "" : source;
        for (int at = 0; at < value.length();) {
            int code = value.codePointAt(at); at += Character.charCount(code);
            if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT
                    || (code >= 0xD800 && code <= 0xDFFF) || "/\\:*?\"<>|".indexOf(code) >= 0) clean.append('_');
            else clean.appendCodePoint(code);
        }
        String name = clean.toString().trim().replaceAll("[. ]+$", "");
        while (name.startsWith(".")) name = name.substring(1);
        if (name.trim().isEmpty()) name = "未命名";
        String root = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (root.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) name = "_" + name;
        name = truncateUtf8(name, MAX_NAME_BYTES).replaceAll("[. ]+$", "");
        return name.isEmpty() ? "未命名" : name;
    }

    static String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder(); int used = 0;
        for (int at = 0; at < value.length();) {
            int code = value.codePointAt(at); String next = new String(Character.toChars(code));
            int bytes = next.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) break;
            result.append(next); used += bytes; at += Character.charCount(code);
        }
        return result.toString();
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++)
            if (Character.isISOControl(value.charAt(i)) || Character.getType(value.charAt(i)) == Character.FORMAT) return true;
        return false;
    }
}
