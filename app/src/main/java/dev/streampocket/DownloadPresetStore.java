package dev.streampocket;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small named user preferences; one checked commit makes imports and edits atomic. */
public final class DownloadPresetStore {
    static final String PREFERENCES = "download-presets";
    public static final int MAX_PRESETS = 100;
    private DownloadPresetStore() {}

    public static synchronized List<DownloadPresets.Preset> all(Context context) {
        String saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("items", "");
        if (saved == null || saved.isEmpty()) return new ArrayList<>();
        return parse(saved);
    }

    public static synchronized DownloadPresets.Preset save(Context context, DownloadPresets.Preset input) throws IOException {
        if (input == null) throw new IllegalArgumentException("请选择要保存的预设");
        DownloadPresets.Preset preset = withIdentity(input);
        List<DownloadPresets.Preset> presets = all(context);
        boolean replaced = false;
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).id.equals(preset.id)) {
            presets.set(i, preset); replaced = true; break;
        }
        if (!replaced) presets.add(preset);
        validateList(presets); write(context, presets); return preset;
    }

    public static synchronized void rename(Context context, String id, String name) throws IOException {
        List<DownloadPresets.Preset> presets = all(context);
        for (DownloadPresets.Preset preset : presets) if (preset.id.equals(id)) {
            save(context, new DownloadPresets.Preset(preset.id, name, preset.rule, preset.saveCover, preset.saveSubtitles,
                    preset.subtitleMode, preset.subtitleLanguages, preset.namingTemplate)); return;
        }
        throw new IllegalArgumentException("预设已不存在");
    }

    public static synchronized void remove(Context context, String id) throws IOException {
        List<DownloadPresets.Preset> presets = all(context);
        if (!presets.removeIf(preset -> preset.id.equals(id))) throw new IllegalArgumentException("预设已不存在");
        write(context, presets);
    }

    public static synchronized String exportJson(Context context) { return encode(all(context)); }

    /** Merge by stable ID. Validate the complete result before touching persisted preferences. */
    public static synchronized void importJson(Context context, String json) throws IOException {
        List<DownloadPresets.Preset> imported = parse(json), merged = all(context);
        for (DownloadPresets.Preset preset : imported) {
            boolean replaced = false;
            for (int i = 0; i < merged.size(); i++) if (merged.get(i).id.equals(preset.id)) {
                merged.set(i, preset); replaced = true; break;
            }
            if (!replaced) merged.add(preset);
        }
        validateList(merged); write(context, merged);
    }

    static List<DownloadPresets.Preset> parse(String json) {
        if (json == null || json.length() > 262144) throw new IllegalArgumentException("预设文件为空或过大");
        try {
            JSONObject root = new JSONObject(json);
            if (!(root.opt("schemaVersion") instanceof Number) || ((Number) root.opt("schemaVersion")).doubleValue() != 1d)
                throw new IllegalArgumentException("不支持的预设文件版本");
            JSONArray items = root.optJSONArray("presets");
            if (items == null || items.length() > MAX_PRESETS) throw new IllegalArgumentException("预设列表无效或超过 100 项");
            List<DownloadPresets.Preset> presets = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                DownloadPresets.Preset preset = DownloadPresets.Preset.fromJson(items.optJSONObject(i));
                if (preset.id.isEmpty()) throw new IllegalArgumentException("导入预设缺少标识");
                presets.add(preset);
            }
            validateList(presets); return presets;
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("预设文件格式无效", invalid); }
    }

    private static DownloadPresets.Preset withIdentity(DownloadPresets.Preset input) {
        return input.id.isEmpty() ? new DownloadPresets.Preset(UUID.randomUUID().toString(), input.name, input.rule,
                input.saveCover, input.saveSubtitles, input.subtitleMode, input.subtitleLanguages, input.namingTemplate) : input;
    }

    private static void validateList(List<DownloadPresets.Preset> presets) {
        if (presets.size() > MAX_PRESETS) throw new IllegalArgumentException("最多保存 100 个下载预设");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (DownloadPresets.Preset preset : presets) {
            if (preset.id.isEmpty() || !ids.add(preset.id)) throw new IllegalArgumentException("存在重复或无效的预设标识");
            if (!names.add(preset.name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("已有同名预设，请换一个名称");
        }
    }

    private static String encode(List<DownloadPresets.Preset> presets) {
        try {
            JSONArray items = new JSONArray(); for (DownloadPresets.Preset preset : presets) items.put(preset.toJson());
            return new JSONObject().put("schemaVersion", 1).put("presets", items).toString();
        } catch (Exception invalid) { throw new IllegalArgumentException("无法导出下载预设", invalid); }
    }

    private static void write(Context context, List<DownloadPresets.Preset> presets) throws IOException {
        if (!context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString("items", encode(presets)).commit())
            throw new IOException("下载预设未能保存，请重试");
    }
}
