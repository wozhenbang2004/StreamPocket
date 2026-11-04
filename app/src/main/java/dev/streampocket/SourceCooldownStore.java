package dev.streampocket;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small, app-private cooldown journal. Authentication and full source URLs are never stored here. */
public final class SourceCooldownStore implements SourceScheduler.Persistence {
    static final String PREFERENCES = "source-cooldowns-v1";
    private static final String ENTRIES = "entries";
    private final SharedPreferences preferences;

    public SourceCooldownStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE));
    }
    SourceCooldownStore(SharedPreferences preferences) { this.preferences = preferences; }

    @Override public List<SourceScheduler.Cooldown> load() {
        Set<String> saved = preferences.getStringSet(ENTRIES, Collections.emptySet());
        List<SourceScheduler.Cooldown> result = new ArrayList<>();
        if (saved == null) return result;
        for (String value : saved) {
            if (value == null || value.length() > 320) continue;
            String[] fields = value.split("\\t", -1);
            if (fields.length != 4) continue;
            try {
                SourceScheduler.Cooldown row = new SourceScheduler.Cooldown(fields[0],
                        Long.parseLong(fields[1]), Long.parseLong(fields[2]), Integer.parseInt(fields[3]));
                if (SourceScheduler.validCooldown(row)) result.add(row);
            } catch (NumberFormatException ignored) { /* Ignore an incomplete or corrupt individual row. */ }
            if (result.size() == SourceScheduler.MAX_SAVED_COOLDOWNS) break;
        }
        return result;
    }

    @Override public void save(List<SourceScheduler.Cooldown> cooldowns) throws IOException {
        Set<String> values = new HashSet<>();
        for (SourceScheduler.Cooldown row : cooldowns) {
            if (!SourceScheduler.validCooldown(row)) continue;
            values.add(row.key + "\t" + row.savedAtWallMillis + "\t" + row.deadlineWallMillis + "\t" + row.failures);
            if (values.size() == SourceScheduler.MAX_SAVED_COOLDOWNS) break;
        }
        // Synchronous commit makes the deadline durable before the outcome returns. The scheduler
        // calls this outside its admission monitor and treats a failed write as non-fatal.
        if (!preferences.edit().putStringSet(ENTRIES, values).commit()) throw new IOException("无法保存平台冷却状态");
    }
}
