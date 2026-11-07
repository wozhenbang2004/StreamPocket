package dev.streampocket;

import android.app.Application;
import android.content.Context;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, manifest = Config.NONE)
public final class DownloadPresetStoreTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(DownloadPresetStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void saveRenameDeleteAndRoundTripKeepStableIds() throws Exception {
        assertTrue(DownloadPresetStore.all(context).isEmpty());
        DownloadPresets.Preset saved = DownloadPresetStore.save(context, DownloadPresetsTest.preset("", "手机", "cap-720"));
        assertFalse(saved.id.isEmpty());
        DownloadPresetStore.rename(context, saved.id, "手机观看");
        String exported = DownloadPresetStore.exportJson(context);
        DownloadPresetStore.remove(context, saved.id); assertTrue(DownloadPresetStore.all(context).isEmpty());
        DownloadPresetStore.importJson(context, exported);
        List<DownloadPresets.Preset> restored = DownloadPresetStore.all(context);
        assertEquals(1, restored.size()); assertEquals(saved.id, restored.get(0).id);
        assertEquals("手机观看", restored.get(0).name); assertEquals("cap-720", restored.get(0).rule);
        DownloadPresetStore.importJson(context, exported);
        assertEquals(1, DownloadPresetStore.all(context).size());
    }

    @Test public void invalidBulkImportLeavesExistingPresetsUntouched() throws Exception {
        DownloadPresetStore.save(context, DownloadPresetsTest.preset("phone", "手机", "best"));
        String before = DownloadPresetStore.exportJson(context);
        JSONObject incoming = new JSONObject().put("schemaVersion", 1).put("presets", new JSONArray()
                .put(DownloadPresetsTest.preset("audio", "声音", "audio-m4a").toJson())
                .put(DownloadPresetsTest.preset("collision", "手机", "best").toJson()));
        try { DownloadPresetStore.importJson(context, incoming.toString()); fail(); }
        catch (IllegalArgumentException expected) {}
        assertEquals(before, DownloadPresetStore.exportJson(context));
        incoming = new JSONObject(before).put("schemaVersion", 100);
        try { DownloadPresetStore.importJson(context, incoming.toString()); fail(); }
        catch (IllegalArgumentException expected) {}
        assertEquals(before, DownloadPresetStore.exportJson(context));
    }

    @Test public void anAppliedTaskSnapshotDoesNotChangeWhenPresetIsRenamedOrRemoved() throws Exception {
        DownloadPresets.Preset saved = DownloadPresetStore.save(context, DownloadPresetsTest.preset("phone", "手机", "best"));
        DownloadPresets.Result applied = DownloadPresets.copyMediaAndApply(DownloadPresetsTest.video(), saved);
        String snapshot = applied.media.toJson().toString();
        DownloadPresetStore.rename(context, saved.id, "已改名"); DownloadPresetStore.remove(context, saved.id);
        assertEquals(snapshot, applied.media.toJson().toString()); assertEquals("手机", applied.media.presetName);
    }

    @Test public void duplicateIdsAndWrongTypesAreRejectedBeforePersistence() throws Exception {
        JSONObject preset = DownloadPresetsTest.preset("p", "一", "best").toJson();
        String duplicate = new JSONObject().put("schemaVersion", 1).put("presets", new JSONArray().put(preset).put(preset)).toString();
        try { DownloadPresetStore.importJson(context, duplicate); fail(); } catch (IllegalArgumentException expected) {}
        assertTrue(DownloadPresetStore.all(context).isEmpty());
        String malformed = new JSONObject().put("schemaVersion", 1).put("presets", new JSONArray().put(preset.put("saveSubtitles", "yes"))).toString();
        try { DownloadPresetStore.importJson(context, malformed); fail(); } catch (IllegalArgumentException expected) {}
        assertTrue(DownloadPresetStore.all(context).isEmpty());
    }
}
