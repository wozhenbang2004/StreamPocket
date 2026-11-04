package dev.streampocket;

import android.content.SharedPreferences;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real adapter with a SharedPreferences contract fake, not an Android device. */
public final class SourceCooldownStoreTest {
    private static final long WALL = 1_800_000_000_000L;

    @Test public void newAdapterRestoresOnlySourceMetadata() throws Exception {
        Preferences fake = new Preferences();
        SourceScheduler first = new SourceScheduler(() -> 900_000, () -> WALL);
        first.setPersistence(new SourceCooldownStore(fake.api));
        first.outcomeForUrl("https://x.com/user/status/123?token=must-not-be-saved", new Exception("HTTP Error 429"));
        String raw = fake.values.toString();
        assertFalse(raw.contains("https://"));
        assertFalse(raw.contains("user"));
        assertFalse(raw.contains("token"));
        SourceScheduler second = new SourceScheduler(() -> 5, () -> WALL + 10_000);
        second.setPersistence(new SourceCooldownStore(fake.api));
        SourceSchedulerCooldownSelfTest.assertCooling(second, "https://x.com/user/status/123", 50);
        List<SourceScheduler.Cooldown> rows = new SourceCooldownStore(fake.api).load();
        assertEquals(1, rows.size());
        assertEquals("x", rows.get(0).key);
        assertEquals(WALL + 60_000, rows.get(0).deadlineWallMillis);
        second.outcomeForUrl("https://x.com/user/status/123", null);
        assertTrue(new SourceCooldownStore(fake.api).load().isEmpty());
    }

    @Test public void malformedValuesAndLargeJournalsStayBounded() throws Exception {
        Preferences fake = new Preferences();
        Set<String> raw = new HashSet<>();
        raw.add("not\ta\tvalid\trow");
        raw.add("https://private.example/v?cookie=secret\t" + WALL + "\t" + (WALL + 10_000) + "\t1");
        raw.add("x\t" + WALL + "\t" + Long.MAX_VALUE + "\t1");
        raw.add("x\t" + WALL + "\t" + (WALL + 10_000) + "\t99");
        fake.values.put("entries", raw);
        SourceCooldownStore store = new SourceCooldownStore(fake.api);
        assertTrue(store.load().isEmpty());
        List<SourceScheduler.Cooldown> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(new SourceScheduler.Cooldown("host" + i + ".example", WALL, WALL + 10_000, 1));
        store.save(rows);
        assertEquals(SourceScheduler.MAX_SAVED_COOLDOWNS, store.load().size());
        assertEquals(SourceScheduler.MAX_SAVED_COOLDOWNS, ((Set<?>) fake.values.get("entries")).size());
    }

    @Test public void failedCommitLeavesInMemoryAdmissionUsable() throws Exception {
        Preferences fake = new Preferences();
        SourceScheduler scheduler = new SourceScheduler(() -> 10_000, () -> WALL);
        SourceCooldownStore store = new SourceCooldownStore(fake.api);
        scheduler.setPersistence(store);
        fake.failCommit = true;
        try {
            store.save(List.of(new SourceScheduler.Cooldown("x", WALL, WALL + 60_000, 1)));
            fail("A failed durable write was reported as successful");
        } catch (IOException expected) { /* The scheduler, rather than the adapter, owns degradation. */ }
        scheduler.outcomeForUrl("https://x.com/u/status/1", new Exception("HTTP Error 429"));
        SourceSchedulerCooldownSelfTest.assertCooling(scheduler, "https://x.com/u/status/1", 60);
    }

    private static final class Preferences {
        final Map<String, Object> values = new HashMap<>();
        boolean failCommit;
        final SharedPreferences api = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getStringSet")) return values.getOrDefault(args[0], args[1]);
                    if (method.getName().equals("edit")) return editor();
                    throw new UnsupportedOperationException(method.getName());
                });
        private SharedPreferences.Editor editor() {
            Map<String, Object> changes = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("putStringSet")) {
                            changes.put((String) args[0], new HashSet<>((Set<?>) args[1])); return proxy;
                        }
                        if (method.getName().equals("commit")) {
                            if (failCommit) return false;
                            values.putAll(changes); return true;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
