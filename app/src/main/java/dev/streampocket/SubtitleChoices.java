package dev.streampocket;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Source-provided subtitles only. A language is never a wildcard or a translation request. */
final class SubtitleChoices {
    static final String PREFER_ORIGINAL = "prefer-original";
    static final String ORIGINAL = "original";
    static final String AUTOMATIC = "automatic";

    static final class Language {
        final String code;
        boolean original, automatic;
        Language(String code, boolean original, boolean automatic) {
            this.code = code; this.original = original; this.automatic = automatic;
        }
        String label() {
            String display = Locale.forLanguageTag(code).getDisplayName(Locale.SIMPLIFIED_CHINESE);
            if (display.isEmpty() || display.equalsIgnoreCase(code)) display = code;
            else display += " · " + code;
            String source = original && automatic ? "平台 / 自动" : original ? "平台字幕" : automatic ? "自动" : "来源未记录";
            return display + "（" + source + "）";
        }
        boolean available(String mode) {
            // Legacy saved results have the language but no source classification.
            return (!original && !automatic) || PREFER_ORIGINAL.equals(mode)
                    || (ORIGINAL.equals(mode) ? original : automatic);
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("code", code).put("original", original).put("automatic", automatic);
        }
    }

    static boolean validLanguage(String code) {
        return code != null && code.matches("[A-Za-z0-9_-]{1,40}") && !"live_chat".equals(code);
    }

    static String mode(String value) {
        return ORIGINAL.equals(value) || AUTOMATIC.equals(value) ? value : PREFER_ORIGINAL;
    }

    static String modeLabel(String mode) {
        return ORIGINAL.equals(mode) ? "仅平台字幕" : AUTOMATIC.equals(mode) ? "仅自动字幕" : "优先平台，缺少时用自动字幕";
    }

    static List<Language> fromMetadata(JSONObject info) {
        Map<String, Language> languages = new TreeMap<>();
        for (String kind : new String[]{"subtitles", "automatic_captions"}) {
            JSONObject source = info.optJSONObject(kind);
            if (source == null) continue;
            java.util.Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String code = keys.next();
                JSONArray formats = source.optJSONArray(code);
                if (!validLanguage(code) || formats == null || formats.length() == 0) continue;
                boolean usable = false;
                for (int i = 0; i < formats.length(); i++) {
                    JSONObject entry = formats.optJSONObject(i);
                    if (entry != null && ((!entry.isNull("url") && !entry.optString("url").isEmpty())
                            || (!entry.isNull("data") && !entry.optString("data").isEmpty()))) { usable = true; break; }
                }
                if (!usable) continue;
                Language language = languages.get(code);
                if (language == null) { language = new Language(code, false, false); languages.put(code, language); }
                if ("subtitles".equals(kind)) language.original = true; else language.automatic = true;
            }
        }
        List<Language> result = new ArrayList<>(languages.values());
        Collections.sort(result, Comparator.comparingInt((Language language) -> priority(language.code)).thenComparing(language -> language.code));
        return result;
    }

    private static int priority(String code) {
        String lower = code.toLowerCase(Locale.ROOT);
        return lower.equals("zh") || lower.startsWith("zh-") ? 0 : lower.equals("en") || lower.startsWith("en-") ? 1 : 2;
    }

    static List<Language> fromJson(JSONArray array) {
        List<Language> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String code = item.optString("code");
            if (validLanguage(code) && seen.add(code)) result.add(new Language(code, item.optBoolean("original"), item.optBoolean("automatic")));
        }
        return result;
    }

    static List<String> selected(List<Language> available, List<String> requested, String legacy, String mode) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> input = requested.isEmpty() && validLanguage(legacy) ? Collections.singletonList(legacy) : requested;
        for (String code : input) {
            if (!validLanguage(code)) continue;
            if (available.isEmpty() && code.equals(legacy)) { result.add(code); continue; }
            for (Language item : available) if (item.code.equals(code) && item.available(mode(mode))) { result.add(code); break; }
        }
        return new ArrayList<>(result);
    }

    private SubtitleChoices() {}
}
