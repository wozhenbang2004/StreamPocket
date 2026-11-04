package dev.streampocket;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Metadata rules for subtitles explicitly attached to a completed download. */
final class SubtitleTracks {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final Pattern SUBRIP_TIME = Pattern.compile("(?m)^\\s*\\d+:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d+:\\d{2}:\\d{2}[,.]\\d{3}");
    private static final Pattern TTML_ROOT = Pattern.compile("<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?tt(?:\\s|>)");
    private static final Set<String> LANGUAGES = knownLanguages();

    private SubtitleTracks() {}

    static String mimeType(String name, String mime) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        if (dot >= 0) {
            switch (value.substring(dot + 1)) {
                case "srt": return "application/x-subrip";
                case "vtt": return "text/vtt";
                case "ass": case "ssa": return "text/x-ssa";
                case "ttml": case "dfxp": return "application/ttml+xml";
                default: return ""; // A cover, LRC lyric or other attachment is not a supported subtitle.
            }
        }
        value = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "application/x-subrip": case "application/srt": case "text/srt": return "application/x-subrip";
            case "text/vtt": return "text/vtt";
            case "text/x-ssa": case "text/x-ass": case "application/x-ass": case "application/x-ssa": return "text/x-ssa";
            case "application/ttml+xml": return "application/ttml+xml";
            default: return "";
        }
    }

    static boolean localUri(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getFragment() != null || uri.getUserInfo() != null || uri.getPath() == null || uri.getPath().isEmpty()) return false;
            if ("content".equals(uri.getScheme())) return uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty();
            return "file".equals(uri.getScheme()) && uri.getRawAuthority() == null && uri.getPath().startsWith("/") && uri.getQuery() == null;
        } catch (Exception invalid) { return false; }
    }

    static String language(String explicit, String name, String requested) {
        String language = languageTag(explicit);
        if (!language.isEmpty()) return language;
        String file = name == null ? "" : name;
        int extension = file.lastIndexOf('.');
        if (extension > 0) {
            int separator = file.lastIndexOf('.', extension - 1);
            if (separator >= 0) language = languageTag(file.substring(separator + 1, extension));
        }
        return language.isEmpty() ? languageTag(requested) : language;
    }

    static String languageTag(String value) {
        if (value == null) return "";
        String tag = value.trim().replace('_', '-');
        // yt-dlp marks the original YouTube language with this suffix.
        if (tag.toLowerCase(Locale.ROOT).endsWith("-orig")) tag = tag.substring(0, tag.length() - 5);
        if (!tag.matches("[A-Za-z]{2,3}(?:-[A-Za-z]{2,4}|-[0-9]{3}){0,2}")) return "";
        try {
            Locale locale = new Locale.Builder().setLanguageTag(tag).build();
            return LANGUAGES.contains(locale.getLanguage()) ? locale.toLanguageTag() : "";
        } catch (RuntimeException invalid) { return ""; }
    }

    private static Set<String> knownLanguages() {
        Set<String> languages = new HashSet<>();
        for(String language:Locale.getISOLanguages()) {
            languages.add(language); languages.add(new Locale(language).getISO3Language());
        }
        // Common caption languages without an ISO 639-1 code.
        java.util.Collections.addAll(languages,"yue","cmn","fil","nan","hak");
        return languages;
    }

    static String label(String explicit, String language, String name) {
        if (explicit != null && !explicit.trim().isEmpty()) return explicit.trim();
        if (language != null && !language.isEmpty()) {
            String display = Locale.forLanguageTag(language).getDisplayName(Locale.SIMPLIFIED_CHINESE);
            if (!display.isEmpty()) return display;
        }
        return name == null || name.trim().isEmpty() ? "已下载字幕" : name.trim();
    }

    /** A bounded header check rejects HTML error pages and files with the wrong format. */
    static boolean validHeader(String mime, byte[] header, int length) {
        if (length <= 0) return false;
        String text;
        if (length >= 2 && (header[0] & 255) == 255 && (header[1] & 255) == 254)
            text = new String(header, 2, length - 2, StandardCharsets.UTF_16LE);
        else if (length >= 2 && (header[0] & 255) == 254 && (header[1] & 255) == 255)
            text = new String(header, 2, length - 2, StandardCharsets.UTF_16BE);
        else text = new String(header, 0, length, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.trim();
        if (text.isEmpty() || text.indexOf('\0') >= 0) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("<html") || lower.startsWith("<!doctype html")) return false;
        switch (mime) {
            case "text/vtt": return text.matches("(?s)^WEBVTT(?:\\s.*)?$");
            case "application/x-subrip": return SUBRIP_TIME.matcher(text).find();
            case "text/x-ssa": return lower.contains("[script info]") || lower.contains("[events]");
            case "application/ttml+xml": return TTML_ROOT.matcher(text).find();
            default: return false;
        }
    }

    static boolean causedBy(Throwable failure, Throwable subtitleError) {
        if(subtitleError==null) return false;
        for(int depth=0;failure!=null && depth<32;depth++,failure=failure.getCause())
            if(failure==subtitleError) return true;
        return false;
    }
}
