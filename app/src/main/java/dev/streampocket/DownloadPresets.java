package dev.streampocket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Named intent is resolved against each item's real capabilities before queue creation. */
public final class DownloadPresets {
    public static final List<String> RULES = Collections.unmodifiableList(Arrays.asList(
            "best", "cap-1080", "cap-720", "audio-m4a", "audio-mp3", "compatible-mp4"));
    private DownloadPresets() {}

    public static final class Preset {
        public final String id, name, rule, subtitleMode, namingTemplate;
        public final boolean saveCover, saveSubtitles;
        public final List<String> subtitleLanguages;

        public Preset(String id, String name, String rule, boolean saveCover, boolean saveSubtitles,
                String subtitleMode, List<String> subtitleLanguages, String namingTemplate) {
            this.id = id == null ? "" : id.trim();
            if (!this.id.isEmpty() && !this.id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("预设标识无效");
            this.name = validName(name);
            if (!RULES.contains(rule)) throw new IllegalArgumentException("不支持的下载预设规则");
            this.rule = rule; this.saveCover = saveCover; this.saveSubtitles = saveSubtitles;
            String mode = subtitleMode == null || subtitleMode.isEmpty() ? SubtitleChoices.PREFER_ORIGINAL : subtitleMode;
            if (!Arrays.asList(SubtitleChoices.PREFER_ORIGINAL, SubtitleChoices.ORIGINAL, SubtitleChoices.AUTOMATIC).contains(mode))
                throw new IllegalArgumentException("字幕来源设置无效");
            this.subtitleMode = mode;
            LinkedHashSet<String> languages = new LinkedHashSet<>();
            if (subtitleLanguages != null) for (String language : subtitleLanguages) {
                if (!SubtitleChoices.validLanguage(language)) throw new IllegalArgumentException("字幕语言无效");
                languages.add(language);
            }
            if (languages.size() > 20) throw new IllegalArgumentException("每个预设最多选择 20 种字幕语言");
            this.subtitleLanguages = Collections.unmodifiableList(new ArrayList<>(languages));
            this.namingTemplate = NamingTemplate.validate(namingTemplate == null ? NamingTemplate.DEFAULT : namingTemplate);
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("rule", rule)
                    .put("saveCover", saveCover).put("saveSubtitles", saveSubtitles).put("subtitleMode", subtitleMode)
                    .put("subtitleLanguages", new JSONArray(subtitleLanguages)).put("namingTemplate", namingTemplate);
        }

        public static Preset fromJson(JSONObject json) {
            if (json == null) throw new IllegalArgumentException("预设内容为空");
            for (String field : Arrays.asList("id", "name", "rule", "subtitleMode", "namingTemplate"))
                if (json.has(field) && !(json.opt(field) instanceof String)) throw new IllegalArgumentException("预设字段类型错误：" + field);
            for (String field : Arrays.asList("saveCover", "saveSubtitles"))
                if (json.has(field) && !(json.opt(field) instanceof Boolean)) throw new IllegalArgumentException("预设字段类型错误：" + field);
            List<String> languages = new ArrayList<>();
            if (json.has("subtitleLanguages")) {
                JSONArray array = json.optJSONArray("subtitleLanguages");
                if (array == null) throw new IllegalArgumentException("字幕语言列表无效");
                for (int i = 0; i < array.length(); i++) {
                    Object language = array.opt(i);
                    if (!(language instanceof String)) throw new IllegalArgumentException("字幕语言列表无效");
                    languages.add((String) language);
                }
            }
            return new Preset(json.optString("id"), json.optString("name"), json.optString("rule", "best"),
                    json.optBoolean("saveCover"), json.optBoolean("saveSubtitles"),
                    json.optString("subtitleMode", SubtitleChoices.PREFER_ORIGINAL), languages,
                    json.optString("namingTemplate", NamingTemplate.DEFAULT));
        }
    }

    public static final class Result {
        public final Engine.Media media;
        public final Engine.Quality quality;
        public final String skipReason;
        private Result(Engine.Media media, Engine.Quality quality, String reason) {
            this.media = media; this.quality = quality; this.skipReason = reason;
        }
        public boolean applied() { return quality != null; }
    }

    /** Does not mutate either the source metadata or its available quality list. */
    public static Result apply(Engine.Media media, String rule) {
        if (media == null) return new Result(null, null, "内容不存在");
        if (!RULES.contains(rule)) return new Result(media, null, "不支持的预设规则");
        if (media.directFile()) return new Result(media, null, "普通文件保留原文件名和设置");
        if (media.album) return new Result(media, null, "图集保留原设置");
        if (rule.startsWith("audio-") && !media.hasAudio) return new Result(media, null, "此内容没有可用音轨");
        for (Engine.Quality quality : media.downloadChoices()) if (rule.equals(quality.id))
            return new Result(media, copy(quality), "");
        if ("cap-1080".equals(rule) || "cap-720".equals(rule)) {
            int height = "cap-1080".equals(rule) ? 1080 : 720;
            boolean eligible = false;
            for (Engine.Quality quality : media.qualities) try {
                int available = Integer.parseInt(quality.id); if (available > 0 && available <= height) eligible = true;
            } catch (NumberFormatException ignored) { /* Named choices do not prove a matching source height. */ }
            if (!eligible) return new Result(media, null, "源站没有返回 " + height + "p 以内的可用视频");
            return new Result(media, new Engine.Quality(rule, height + "p 以内",
                    "bv*[height<=" + height + "]+ba/b[height<=" + height + "]"), "");
        }
        return new Result(media, null, "compatible-mp4".equals(rule) ? "此内容没有兼容优先格式" : "此内容没有匹配的下载选项");
    }

    /** The caller can queue or preview the returned snapshot without altering the inspection inbox. */
    public static Result copyMediaAndApply(Engine.Media source, Preset preset) throws Exception {
        if (source == null || preset == null) throw new IllegalArgumentException("请选择内容和预设");
        Engine.Media media = Engine.Media.fromJson(new JSONObject(source.toJson().toString()));
        Result chosen = apply(media, preset.rule);
        if (!chosen.applied()) return chosen;
        media.presetId = preset.id; media.presetName = preset.name; media.namingTemplate = preset.namingTemplate;
        media.saveCover = preset.saveCover && !media.thumbnail.isEmpty();
        media.subtitleMode = preset.subtitleMode;
        media.subtitleLanguages = new ArrayList<>(preset.subtitleLanguages);
        // An empty language preference uses the item's default; it never means download every language.
        if (media.subtitleLanguages.isEmpty() && !media.subtitleLanguage.isEmpty()) media.subtitleLanguages.add(media.subtitleLanguage);
        media.saveSubtitles = preset.saveSubtitles && !media.album && !media.selectedSubtitles().isEmpty();
        return new Result(media, chosen.quality, "");
    }

    public static String validName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 60) throw new IllegalArgumentException("预设名称需为 1–60 个字符");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("预设名称不能包含控制字符");
        return value;
    }

    private static Engine.Quality copy(Engine.Quality quality) {
        try { return Engine.Quality.fromJson(new JSONObject(quality.toJson().toString())); }
        catch (Exception invalid) { throw new IllegalArgumentException("下载选项无效", invalid); }
    }
}
